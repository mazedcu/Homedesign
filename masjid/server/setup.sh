#!/usr/bin/env bash
# Masjid Finder setup — asks for the admin's name, phone and PIN. Safe next to other sites/apps.
set -e
cd "$(dirname "$0")"
[ "$(id -u)" = 0 ] || { echo "Please run as root (sudo ./setup.sh)"; exit 1; }
command -v docker >/dev/null || curl -fsSL https://get.docker.com | sh
IP=$(curl -s https://ifconfig.me || echo "YOUR-DROPLET-IP")
busy(){ ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"; }
echo; echo "=== Masjid Finder setup ==="
read -rp "Admin name [Admin]: " ANAME; ANAME=${ANAME:-Admin}
read -rp "Admin phone number (used to sign in): " APHONE
[[ "$APHONE" =~ ^[0-9+\ -]{7,18}$ ]] || { echo "Enter a valid phone number."; exit 1; }
read -rsp "Admin PIN (4-6 digits): " APIN; echo
[[ "$APIN" =~ ^[0-9]{4,6}$ ]] || { echo "PIN must be 4 to 6 numbers."; exit 1; }
read -rp "Country code to prefer in place search, e.g. bd [bd]: " CC; CC=${CC:-bd}
PORT=3200; while busy $PORT; do PORT=$((PORT+1)); done
{ echo "ADMIN_NAME=$ANAME"; echo "ADMIN_PHONE=$APHONE"; echo "ADMIN_PIN=$APIN"; echo "COUNTRY_CODES=$CC"; echo "APP_PORT=$PORT"; } > .env
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
echo "$URL" > address.txt
echo; echo "=================================================="
echo " DONE!  Masjid Finder is running at:"; echo; echo "   $URL"; echo
echo " Open it in a browser to test. In the Android app, enter this address once."
echo " Sign in as admin with your phone number and PIN (Account tab)."
echo " If the app can't connect, allow TCP port $PORT in DigitalOcean > Networking > Firewalls."
echo "=================================================="
