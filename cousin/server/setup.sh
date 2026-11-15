#!/usr/bin/env bash
# Cousin Heaven setup. Safe for servers that already run other sites/apps:
# it never touches existing containers, nginx/apache or firewall rules (except opening ONE port if needed).
set -e
cd "$(dirname "$0")"
[ "$(id -u)" = 0 ] || { echo "Please run as root (sudo ./setup.sh)"; exit 1; }
command -v docker >/dev/null || curl -fsSL https://get.docker.com | sh
IP=$(curl -s https://ifconfig.me || echo "YOUR-DROPLET-IP")
busy(){ ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"; }
echo; echo "=== Cousin Heaven setup ==="
read -rp "Family code (secret word the cousins type to join): " CODE
read -rp "Parent/admin name: " ANAME
read -rsp "Parent/admin 4-digit PIN: " APIN; echo
[ ${#CODE} -ge 4 ] && [[ "$APIN" =~ ^[0-9]{4}$ ]] && [ -n "$ANAME" ] || { echo "Need: code of 4+ chars, a name, and a 4-digit PIN."; exit 1; }
PORT=3100; while busy $PORT; do PORT=$((PORT+1)); done
{ echo "FAMILY_CODE=$CODE"; echo "ADMIN_NAME=$ANAME"; echo "ADMIN_PIN=$APIN"; } > .env
if busy 80 || busy 443; then
  echo; echo "Ports 80/443 are already used by another website on this server."
  echo "Cousin Heaven will NOT touch them. Choose how to run it:"
  echo "  1) On its own port $PORT  (simple: http://$IP:$PORT)"
  echo "  2) Behind your existing nginx/apache with a subdomain (you add a small proxy config)"
  read -rp "Choose 1 or 2 [1]: " MODE; MODE=${MODE:-1}
  if [ "$MODE" = 2 ]; then
    { echo "APP_BIND=127.0.0.1"; echo "APP_PORT=$PORT"; } >> .env
    docker compose up -d --build
    echo; echo "Running locally on 127.0.0.1:$PORT. Add this to your existing nginx for a subdomain (with WebSocket support):"
    cat <<NG

  location / {
      proxy_pass http://127.0.0.1:$PORT;
      proxy_http_version 1.1;
      proxy_set_header Upgrade \$http_upgrade;
      proxy_set_header Connection "upgrade";
      proxy_set_header Host \$host;
      proxy_set_header X-Forwarded-For \$remote_addr;
      client_max_body_size 8m;
  }
NG
    echo "Then use https://your-subdomain in the app."; exit 0
  fi
  { echo "APP_BIND=0.0.0.0"; echo "APP_PORT=$PORT"; } >> .env
  command -v ufw >/dev/null && ufw status | grep -q active && ufw allow $PORT/tcp >/dev/null
  docker compose up -d --build
  echo; echo "Done! Server address for the app:  http://$IP:$PORT"
  echo "If it does not open, allow TCP port $PORT in the DigitalOcean Networking > Firewalls page."
  exit 0
fi
read -rp "Domain name pointing to this droplet (Enter = plain http on the IP): " DOMAIN
if [ -n "$DOMAIN" ]; then printf '%s {\n  reverse_proxy app:3000\n}\n' "$DOMAIN" > Caddyfile; URL="https://$DOMAIN"
else printf ':80 {\n  reverse_proxy app:3000\n}\n' > Caddyfile; URL="http://$IP"; fi
{ echo "APP_BIND=127.0.0.1"; echo "APP_PORT=$PORT"; echo "COMPOSE_PROFILES=caddy"; } >> .env
command -v ufw >/dev/null && ufw status | grep -q active && { ufw allow 80/tcp; ufw allow 443/tcp; } >/dev/null
docker compose up -d --build
echo; echo "Done! Server address for the app:  $URL"
