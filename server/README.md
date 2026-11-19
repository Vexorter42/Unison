# Unison API

Small self-hosted service for private Unison accounts and cross-device library sync. It has no public sign-up and does not depend on Nextcloud.

## Deploy on the home server

Copy this directory to ~/docker/unison-api, then run:

    mkdir -p ~/docker/unison-api/data
    chmod 700 ~/docker/unison-api/data
    docker-compose build
    docker-compose up -d

Create a friend's account through SSH. The command hides and confirms the password:

    docker-compose run --rm --no-deps -it unison-api create-user

Restart the service after adding an account so it reloads the account file:

    docker-compose restart

To erase an account and its server-side backup, run:

    docker-compose run --rm --no-deps -it unison-api delete-user

Then restart the service. Also remove that person's local Unison session from their devices.

Copy unison-api.subdomain.conf into ~/docker/swag/proxy/, validate and reload SWAG:

    docker exec swag nginx -t
    docker exec swag nginx -s reload

The server's existing wildcard certificate and DNS should make https://unison-api.vexorter.duckdns.org available. Confirm /healthz returns {"status":"ok"} before using the clients.

## API

- POST /v1/auth/login — username/password; returns a revocable 30-day bearer token.
- POST /v1/auth/logout — revokes the current token.
- GET /v1/sync — returns the user's Unison backup and an ETag; returns 404 if empty.
- PUT /v1/sync — saves a Unison backup using If-Match or If-None-Match: *.
- GET /healthz — health check.

Passwords are salted Argon2id hashes. Session tokens are random and only their hashes are stored. Backups are private to the account and limited to 16 MiB. The data directory contains account hashes, active session hashes, and library backups; protect it and include it in server backups. Account and backup deletion does not erase copies already made in your server's external backup system.
