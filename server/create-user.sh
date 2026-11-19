#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

if command -v docker-compose >/dev/null 2>&1; then
    compose() { docker-compose "$@"; }
elif command -v docker >/dev/null 2>&1; then
    compose() { docker compose "$@"; }
else
    printf '%s\n' 'Docker Compose was not found. Install Docker Compose and try again.' >&2
    exit 127
fi

printf '%s\n' 'Create a Unison account. The password will be entered without being shown.'
compose run --rm --no-deps -it unison-api create-user

printf '%s\n' 'Refreshing the API so it loads the new account...'
compose restart unison-api
printf '%s\n' 'Account created and Unison API restarted.'
