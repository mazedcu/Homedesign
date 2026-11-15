#!/usr/bin/env bash
# Cousin Heaven one-command setup for a fresh Ubuntu/Debian DigitalOcean droplet.
set -e
cd "$(dirname "$0")"
[ "$(id -u)" = 0 ] || { echo "Please run as root (sudo ./setup.sh)"; exit 1; }
if ! command -v docker >/dev/null; then curl -fsSL https://get.docker.com | sh; fi
IP=$(curl -s https://ifconfig.me || echo "YOUR-DROPLET-IP")
echo; echo "=== Cousin Heaven setup ==="
read -rp "Domain name pointing to this droplet (press Enter to use plain http on the IP): " DOMAIN
read -rp "Family code (secret word the cousins type to join): " CODE
read -rp "Parent/admin name: " ANAME
read -rsp "Parent/admin 4-digit PIN: " APIN; echo
[ ${#CODE} -ge 4 ] && [[ "$APIN" =~ ^[0-9]{4}$ ]] && [ -n "$ANAME" ] || { echo "Need: code of 4+ chars, a name, and a 4-digit PIN."; exit 1; }
cat > .env <<EOT
FAMILY_CODE=$CODE
ADMIN_NAME=$ANAME
ADMIN_PIN=$APIN
EOT
chmod 600 .env
if [ -n "$DOMAIN" ]; then printf '%s {\n  reverse_proxy app:3000\n}\n' "$DOMAIN" > Caddyfile; URL="https://$DOMAIN"
else printf ':80 {\n  reverse_proxy app:3000\n}\n' > Caddyfile; URL="http://$IP"; fi
command -v ufw >/dev/null && { ufw allow 80/tcp; ufw allow 443/tcp; } >/dev/null 2>&1 || true
docker compose up -d --build
echo; echo "Done! Open the app and enter this server address:  $URL"
