[![Build status](https://circleci.com/gh/mtgred/netrunner/tree/master.svg?style=shield)](https://circleci.com/gh/mtgred/netrunner)

Play Android: Netrunner in the browser.


## Live server

http://www.jinteki.net

[Gameplay videos](https://www.youtube.com/results?search_query=jinteki.net)

![screenshot](http://i.imgur.com/xkxOMHc.jpg)


## Card implementation status

[Card rules implementation status](https://docs.google.com/spreadsheets/d/1ICv19cNjSaW9C-DoEEGH3iFt09PBTob4CAutGex0gnE/pubhtml)


## Development
### Quickstart

Install [Leiningen](https://leiningen.org/), [NodeJS](https://nodejs.org/en/download/package-manager/#macos) and
[MongoDB](https://docs.mongodb.com/manual/installation/).

This project runs on Java 8. If you're on OSX or Linux, we recommend using
[jenv](https://github.com/jenv/jenv/blob/master/README.md) to manage your java environment.

You can check your setup by running

    $ lein version # Your exact version numbers below may vary, but we expect Java 1.8.X
    Leiningen 2.9.1 on Java 1.8.0_222 OpenJDK 64-Bit Server VM

Populate the database and create indexes using:

    $ lein fetch [--no-card-images]
    $ lein create-indexes

You can optionally pass `--no-card-images` if you don't want to download images from
[NetrunnerDB](https://netrunnerdb.com/), as this takes a while. See `lein fetch help` for further options.

To install frontend dependencies, run:

    $ npm ci

To compile CSS:

    $ npx stylus src/css/netrunner.styl -o resources/public/css/

Optionally you can pass `-w` to `stylus` to watch for changes and automatically recompile.

Compile ClojureScript frontend:

    $ npx shadow-cljs compile app

Finally, launch the webserver and the Clojure REPL:

    $ lein repl

and open http://localhost:1042/


### Tests

To run all tests:

    $ lein test

To run a single test file:

    $ lein test game.cards.agendas-test

Or a single test:

    $ lein test :only game.cards.agendas-test/fifteen-minutes

For more information refer to the [development guide](https://github.com/mtgred/netrunner/wiki/Getting-Started-with-Development).


### Further reading

 - [Development Tips and Tricks](https://github.com/mtgred/netrunner/wiki/Development-Tips-and-Tricks)
 - [Writing Tests](https://github.com/mtgred/netrunner/wiki/Tests)
 - "Profiling Database Queries" in `DEVELOPMENT.md`

## License

Jinteki.net is released under the [MIT License](http://www.opensource.org/licenses/MIT).
