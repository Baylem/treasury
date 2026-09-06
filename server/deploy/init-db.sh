#!/bin/sh
set -eu

# Create a dedicated application role without superuser, database, or role administration.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 \
    --set app_password="$TREASURY_APP_PASSWORD" <<'SQL'
CREATE ROLE treasury LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
REVOKE ALL ON DATABASE treasury FROM PUBLIC;
GRANT CONNECT ON DATABASE treasury TO treasury;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO treasury;
SQL
