#!/bin/sh
# Read Docker secrets and export as environment variables
if [ -f /run/secrets/postgres_password ]; then
    export POSTGRES_PASSWORD=$(cat /run/secrets/postgres_password)
fi
exec java -jar app.jar
