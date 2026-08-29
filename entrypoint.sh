#!/bin/sh
set -e

groupadd -o -g "$PGID" appgroup 2>/dev/null || true
useradd -o -u "$PUID" -g "$PGID" -d /mjdbcmcp_config -s /bin/sh appuser 2>/dev/null || true
chown -R "$PUID:$PGID" /mjdbcmcp_config

exec gosu "$PUID:$PGID" java $JAVA_OPTS -jar /app/app.jar
