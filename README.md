## Description

This app shows a github user's authored repositories. This allows you to
interesting stats about how responsive the author is to issues, how
many issues they've resolved, how many issues they have, etc.

View the app [on heroku](#TODO).

## Running the App

1. Start the application: `GITHUB_AUTH=user:pass lein run`
2. Go to [localhost:8080](http://localhost:8080/) and look up a user's contributions.

## Configuration

This app takes the following environment variables:
* `$GITHUB_AUTH (required)` - This can either be your Github Basic auth
  `user:pass` or an oauth token.

## Limitations

* Only works with browsers that support [Server Side Events](http://caniuse.com/#feat=eventsource) and [HTML5 History](http://caniuse.com/#feat=history).
* In Chrome, if you look up a couple of different users and then enter
  a direct user url e.g. /defunkt, going backwords and forwards in
  your browser will be wonky.
