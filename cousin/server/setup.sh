#!/usr/bin/env bash
# Cousin Heaven setup — only asks for the parent's name and PIN. Safe next to other sites/apps.
set -e
cd "$(dirname "$0")"
[ "$(id -u)" = 0 ] || { echo "Please run as root (sudo ./setup.sh)"; exit 1; }
command -v docker >/dev/null || curl -fsSL https://get.docker.com | sh
IP=$(curl -s https://ifconfig.me || echo "YOUR-DROPLET-IP")
busy(){ ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"; }
echo; echo "=== Cousin Heaven setup ==="
read -rp "Parent/admin name [Papa]: " ANAME; ANAME=${ANAME:-Papa}
read -rsp "Parent/admin 4-digit PIN: " APIN; echo
[[ "$APIN" =~ ^[0-9]{4}$ ]] || { echo "PIN must be 4 numbers."; exit 1; }
CODE=$( (head -c 64 /dev/urandom | tr -dc 'a-z0-9' | head -c 8) || true )
PORT=3100; while busy $PORT; do PORT=$((PORT+1)); done
{ echo "FAMILY_CODE=$CODE"; echo "ADMIN_NAME=$ANAME"; echo "ADMIN_PIN=$APIN"; echo "APP_PORT=$PORT"; } > .env
if [ -n "$DOMAIN" ]; then
  printf '%s {\n  reverse_proxy app:3000\n}\n' "$DOMAIN" > Caddyfile; URL="https://$DOMAIN"
  { echo "APP_BIND=127.0.0.1"; echo "COMPOSE_PROFILES=caddy"; } >> .env
  command -v ufw >/dev/null && ufw status | grep -q active && { ufw allow 80/tcp; ufw allow 443/tcp; } >/dev/null || true
else
  # Own port: never touches ports 80/443 or your other sites.
  URL="http://$IP:$PORT"; echo "APP_BIND=0.0.0.0" >> .env
  command -v ufw >/dev/null && ufw status | grep -q active && ufw allow $PORT/tcp >/dev/null || true
fi
docker compose up -d --build
INVITE=$(printf '%s|%s' "$URL" "$CODE" | base64 -w0 | tr '+/' '-_' | tr -d '=')
echo "$INVITE" > invite.txt
echo; echo "=================================================="
echo " DONE!  Send this INVITE CODE to the cousins:"; echo; echo "   $INVITE"; echo
echo " They install the app, paste the code once, then pick a name + PIN."
echo " (Saved in invite.txt. Server address: $URL)"
echo " If the app can't connect, allow TCP port $PORT in DigitalOcean > Networking > Firewalls."
echo "=================================================="
