(ns web.game
  (:require
   [cheshire.core :as json]
   [medley.core :refer [find-first]]
   [clj-time.core :as t]
   ; [clojure.stacktrace :as stacktrace]
   [game.core :as core]
   [game.core.diffs :refer [public-diffs public-states]]
   [jinteki.utils :refer [side-from-str]]
   [web.app-state :as app-state]
   [web.lobby :as lobby]
   [web.stats :as stats]
   [web.ws :as ws]
   [game.main :as main]
   [clojure.string :as str]))

(defn send-state-diffs!
  "Sends diffs generated by public-diffs to all connected clients."
  [{:keys [gameid players spectators]}
   {:keys [runner-diff corp-diff spect-diff]}]
  (doseq [{:keys [uid side]} players]
    (ws/chsk-send! uid [:netrunner/diff (json/generate-string
                                          {:gameid gameid
                                           :diff (if (= side "Corp")
                                                   corp-diff
                                                   runner-diff)})]))
  (doseq [uid (keep :uid spectators)]
    (ws/chsk-send! uid [:netrunner/diff (json/generate-string
                                          {:gameid gameid
                                           :diff spect-diff})])))

(defn update-and-send-diffs!
  "Updates the old-states atom with the new game state, then sends a :netrunner/diff
  message to game clients."
  [f {state :state :as game} & args]
  (when (and state @state)
    (let [old-state @state
          _ (apply f state args)
          diffs (public-diffs old-state state)]
      (swap! state update :history conj (:hist-diff diffs))
      (send-state-diffs! game diffs))))

(defn send-state!
  "Sends full states generated by public-states to either the client specified or all connected clients."
  ([event {:keys [players spectators]} {:keys [runner-state corp-state spect-state]}]
   (doseq [{:keys [uid side]} players]
     (ws/chsk-send! uid [event (json/generate-string
                                 (if (= side "Corp")
                                   corp-state
                                   runner-state))]))
   (doseq [uid (keep :uid spectators)]
     (ws/chsk-send! uid [event (json/generate-string spect-state)])))
  ([event {:keys [players]} {:keys [runner-state corp-state spect-state]} uid]
   (let [player (some #(= (:uid %) uid) players)]
     (ws/chsk-send! uid [event (json/generate-string
                                 (if player
                                   (if (= (:side player) "Corp")
                                     corp-state
                                     runner-state)
                                   spect-state))]))))

(defn- is-starter-deck?
  [player]
  (let [id (get-in player [:deck :identity :title])
        card-cnt (reduce + (map :qty (get-in player [:deck :cards])))]
    (or (and (= id "The Syndicate: Profit over Principle")
             (= card-cnt 34))
        (and (= id "The Catalyst: Convention Breaker")
             (= card-cnt 30)))))

(defn- check-for-starter-decks
  "Starter Decks can require 6 or 7 agenda points"
  [game]
  (if (and (= (:format game) "system-gateway")
           (every? is-starter-deck? (:players game)))
    (do
      (swap! (:state game) assoc-in [:runner :agenda-point-req] 6)
      (swap! (:state game) assoc-in [:corp :agenda-point-req] 6)
      game)
    game))

(defn strip-deck [player]
  (-> player
      (update :deck select-keys [:_id :identity :name :hash])
      (update-in [:deck :_id] str)
      (update-in [:deck :identity] select-keys [:title :faction])))

(defmethod ws/-msg-handler :game/start
  [{{db :system/db} :ring-req
    uid :uid}]
  (when-let [{:keys [gameid players started]} (app-state/uid-in-lobby-as-player? uid)]
    (when (and (= uid (-> players first :uid))
               (not started))
      (let [stripped-players (mapv strip-deck players)
            start-date (t/now)
            new-app-state
            (swap! app-state/app-state update :lobbies
                   (fn [lobbies]
                     (if-let [lobby (get lobbies gameid)]
                       (as-> lobby g
                         (merge g {:started true
                                   :original-players stripped-players
                                   :ending-players stripped-players
                                   :start-date (java.util.Date.)
                                   :last-update start-date
                                   :last-update-only-actions start-date
                                   :state (core/init-game g)})
                         (check-for-starter-decks g)
                         (update g :players #(mapv strip-deck %))
                         (assoc lobbies gameid g))
                       lobbies)))
            game? (get-in new-app-state [:lobbies gameid])]
        (when game?
          (stats/game-started db game?)
          (lobby/send-lobby-state game?)
          (lobby/broadcast-lobby-list)
          (send-state! :game/start game? (public-states (:state game?))))))))

(defmethod ws/-msg-handler :game/leave
  [{{db :system/db user :user} :ring-req
    uid :uid
    gameid :?data
    ?reply-fn :?reply-fn}]
  (let [{:keys [started state] :as lobby} (app-state/get-lobby gameid)]
    (when (and started state)
      ; The game will not exist if this is the last player to leave.
      (when-let [lobby? (lobby/leave-lobby! db user uid nil lobby)]
        (update-and-send-diffs!
          main/handle-notification lobby? (str (:username user) " has left the game.")))))
  (lobby/send-lobby-list uid)
  (lobby/broadcast-lobby-list)
  (when ?reply-fn (?reply-fn true)))

(defn uid-in-lobby-as-original-player? [uid]
  (find-first
    (fn [lobby]
      (some #(= uid (:uid %)) (:original-players lobby)))
    (vals (:lobbies @app-state/app-state))))

(defmethod ws/-msg-handler :game/rejoin
  [{{{:keys [_id] :as user} :user} :ring-req
    uid :uid
    ?data :?data}]
  (let [{:keys [original-players started players] :as lobby} (uid-in-lobby-as-original-player? uid)
        original-player (find-first #(= _id (get-in % [:user :_id])) original-players)]
    (when (and started
               original-player
               (< (count (remove #(= _id (get-in % [:user :_id])) players)) 2))
      (let [?data (assoc ?data :request-side "Any Side")
            lobby? (lobby/join-lobby! user uid ?data nil lobby)
            side (keyword (str (str/lower-case (:side original-player)) "-state"))]
        (when lobby?
          (lobby/send-lobby-list uid)
          (lobby/broadcast-lobby-list)
          (ws/chsk-send! uid [:lobby/select {:started (:started lobby?)
                                             :state (json/generate-string
                                                      ; side works here because user cannot rejoin as a spectator
                                                      (get (public-states (:state lobby)) side))}])
          (update-and-send-diffs! main/handle-rejoin lobby? user))))))

; (defn- active-game?
;   [gameid-str uid]
;   (if (nil? gameid-str)
;     false
;     (try
;       (let [gameid (java.util.UUID/fromString gameid-str)
;             game-from-gameid (lobby/game-for-id gameid)
;             game-from-uid (lobby/game-for-client uid)]
;         (and game-from-uid
;              game-from-gameid
;              (= (:gameid game-from-uid) (:gameid game-from-gameid))))
;       (catch Exception _ false))))

(defmethod ws/-msg-handler :game/concede
  [{uid :uid
    gameid :?data}]
  (let [{:keys [players] :as lobby} (app-state/get-lobby gameid)
        side (some #(when (= uid (:uid %)) (:side %)) players)]
    (when (and lobby side (app-state/uid-in-lobby? uid lobby))
      (update-and-send-diffs! main/handle-concede lobby (side-from-str side)))))

; (defmethod ws/-msg-handler :netrunner/mute-spectators
;   [{{{:keys [username]} :user}      :ring-req
;     uid                       :uid
;     {:keys [gameid-str mute-state]} :?data}]
;   (when (active-game? gameid-str uid)
;     (let [gameid (java.util.UUID/fromString gameid-str)
;           {:keys [state] :as game} (lobby/game-for-id gameid)
;           message (if mute-state "muted" "unmuted")]
;       (when (lobby/player? uid game)
;         (lobby/refresh-lobby-assoc-in gameid [:mute-spectators] mute-state)
;         (main/handle-notification state (str username " " message " spectators."))
;         (update-and-send-diffs! game)))))

; (defmethod ws/-msg-handler :netrunner/action
;   [{{user :user} :ring-req
;     uid                           :uid
;     {:keys [gameid-str command args]} :?data}]
;   (when (active-game? gameid-str uid)
;     (try
;       (let [gameid (java.util.UUID/fromString gameid-str)
;             {:keys [players state] :as game} (lobby/game-for-id gameid)
;             side (some #(when (= uid (:uid %)) (:side %)) players)
;             spectator (lobby/spectator? uid game)]
;         (if (and state side)
;             (let [old-state @state]
;               (try
;                 (main/handle-action user command state (side-from-str side) args)
;                 (lobby/refresh-lobby-assoc-in gameid [:last-update] (t/now))
;                 (update-and-send-diffs! game)
;                 (catch Exception e
;                   (reset! state old-state)
;                   (throw e))))
;             (when-not spectator
;               (println "handle-game-action unknown state or side")
;               (println "\tGameID:" gameid)
;               (println "\tGameID by ClientID:" (:gameid (lobby/game-for-client uid)))
;               (println "\tClientID:" uid)
;               (println "\tSide:" side)
;               (println "\tPlayers:" (map #(select-keys % [:uid :side]) players))
;               (println "\tSpectators" (map #(select-keys % [:uid]) (:spectators game)))
;               (println "\tCommand:" command)
;               (println "\tArgs:" args "\n"))))
;       (catch Exception e
;         (ws/broadcast-to! [uid] :netrunner/error nil)
;         (println (str "Caught exception"
;                       "\nException Data: " (or (ex-data e) (.getMessage e))
;                       "\nCommand: " command
;                       "\nGameId: " gameid-str
;                       "\nStacktrace: " (with-out-str (stacktrace/print-stack-trace e 100))))))))

; (defmethod ws/-msg-handler :game/resync
;   [{uid :uid
;     {:keys [gameid-str]} :?data}]
;   (when (active-game? gameid-str uid)
;     (let [gameid (java.util.UUID/fromString gameid-str)
;           {:keys [players state] :as game} (lobby/game-for-id gameid)]
;       (if state
;           (send-state! :game/resync game (public-states (:state game)) uid)
;           (do (println "resync request unknown state")
;               (println "\tGameID:" gameid)
;               (println "\tGameID by ClientID:" (:gameid (lobby/game-for-client uid)))
;               (println "\tClientID:" uid)
;               (println "\tPlayers:" (map #(select-keys % [:uid :side]) players))
;               (println "\tSpectators" (map #(select-keys % [:uid]) (:spectators game))))))))

; (defmethod ws/-msg-handler :lobby/watch
;   ;; Handles a watch command when a game has started.
;   [{{db :system/db
;      {:keys [username] :as user} :user} :ring-req
;     uid :uid
;     {:keys [gameid password]} :?data
;     reply-fn :?reply-fn :as arg}]
;   (lobby/handle-lobby-watch arg)
;   (if-let [{game-password :password state :state started :started :as game}
;            (lobby/game-for-id gameid)]
;     (when (and user game (lobby/allowed-in-game db game user) state @state)
;       (if-not started
;         false ; don't handle this message, let lobby/handle-game-watch.
;         (if (and (not (lobby/already-in-game? user game))
;                  (or (empty? game-password)
;                      (bcrypt/check password game-password)))
;           ;; Add as a spectator, inform the client that this is the active game,
;           ;; Send existing state to spectator
;           ;; add a chat message, then send diff state to all players.
;           (do (lobby/spectate-game user uid gameid)
;               (main/handle-notification state (str username " joined the game as a spectator."))
;               (let [{:keys [spect-state]} (public-states state)]
;                 (ws/broadcast-to! [uid] :lobby/select {:gameid gameid
;                                                        :started started
;                                                        :state (json/generate-string spect-state)})
;                 (ws/broadcast-to! [uid] :games/diff {:diff {:update {gameid (lobby/game-lobby-view gameid game)}}})
;                 (update-and-send-diffs! (lobby/game-for-id gameid))
;                 (when reply-fn
;                   (reply-fn 200))))
;           (when reply-fn
;             (reply-fn 403)))))
;     (when reply-fn
;       (reply-fn 404))))

; (defmethod ws/-msg-handler :netrunner/say
;   [{{user :user} :ring-req
;     uid :uid
;     {:keys [gameid-str msg]} :?data}]
;   (when (active-game? gameid-str uid)
;     (let [gameid (java.util.UUID/fromString gameid-str)
;           {:keys [state mute-spectators] :as game} (lobby/game-for-id gameid)
;           {:keys [side]} (lobby/player? uid game)]
;       (if (and state side user)
;         (do (main/handle-say state (side-from-str side) user msg)
;             (update-and-send-diffs! game))
;         (when (and (lobby/spectator? uid game) (not mute-spectators))
;           (main/handle-say state :spectator user msg)
;           (lobby/refresh-lobby-assoc-in gameid [:last-update] (t/now))
;           (try
;             (update-and-send-diffs! game)
;             (catch Exception ex
;               (println (str "handle-game-say exception:" (.getMessage ex) "\n")))))))))

; (defmethod ws/-msg-handler :netrunner/typing
;   [{uid :uid
;     {:keys [gameid-str typing]} :?data}]
;   (when (active-game? gameid-str uid)
;     (let [gameid (java.util.UUID/fromString gameid-str)
;           {:keys [state] :as game} (lobby/game-for-id gameid)
;           {:keys [side user]} (lobby/player? uid game)]
;       (when (and state side user)
;         (main/handle-typing state (side-from-str side) user typing)
;         (try
;           (update-and-send-diffs! game)
;           (catch Exception ex
;             (println (str "handle-game-typing exception:" (.getMessage ex) "\n"))))))))

(defn handle-uidport-close [{:keys [users lobbies]} uid user]
  (let [lobbies (lobby/handle-leave-lobby lobbies uid user)
        users (dissoc users uid)]
    {:users users
     :lobbies lobbies}))

(defmethod ws/-msg-handler :chsk/uidport-close
  [{{db :system/db
     user :user} :ring-req
    uid :uid}]
  (let [lobby (app-state/uid->lobby uid)
        new-app-state (swap! app-state/app-state handle-uidport-close uid user)
        lobby? (get-in new-app-state [:lobbies (:gameid lobby)])]
    (lobby/broadcast-lobby-list)
    (cond
      ; The game will not exist if this is the last player to leave
      ; (:started lobby?)
      ; (do (main/handle-notification (:state lobby?) (str (:username user) " has disconnected."))
      ;     (update-and-send-diffs! lobby?))
      (not lobby?)
      (lobby/close-lobby! db lobby))))
