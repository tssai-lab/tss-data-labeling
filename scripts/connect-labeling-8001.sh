#!/usr/bin/env bash
# One-time bridge: the existing 8001 frontend serves the same /var/www release
# as port 80, while retaining its own training/login and manual-demo upstreams.
set -Eeuo pipefail
[[ $EUID == 0 ]]
work=/opt/tss-trunk-8001
cd "$work"
test -f compose.yml
test -f runtime.conf
test -s /var/www/index.html
test "$(docker network inspect tss-trunk-8001_default --format '{{(index .IPAM.Config 0).Subnet}}')" = '172.30.81.0/24'
backup="$work/backups/labeling-entry-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$backup"
cp -p compose.yml "$backup/compose.yml"
if [[ -f frontend-labeling.conf ]]; then cp -p frontend-labeling.conf "$backup/frontend-labeling.conf"; fi
changed=false
rollback() {
  status=$?
  trap - EXIT
  if [[ $status != 0 && $changed == true ]]; then
    cp -p "$backup/compose.yml" compose.yml
    if [[ -f "$backup/frontend-labeling.conf" ]]; then cp -p "$backup/frontend-labeling.conf" frontend-labeling.conf; fi
    docker compose --env-file runtime.conf up -d --no-deps frontend
    echo "8001 update failed; previous Compose restored from $backup" >&2
  fi
  exit "$status"
}
trap rollback EXIT
changed=true
cat > frontend-labeling.conf <<'NGINX'
server {
    absolute_redirect off;
    listen 80 default_server;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;
    charset utf-8;
    client_max_body_size 64m;
    location = /healthz { default_type text/plain; return 200 "ok\n"; }
    location ^~ /api/labeling/ {
        proxy_pass http://172.30.81.1:8083;
        proxy_http_version 1.1;
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_request_buffering off;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
    location /api/ {
        proxy_pass http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
    location /label-api/ {
        client_max_body_size 9m;
        proxy_pass http://manual-label-api:8013;
        proxy_http_version 1.1;
        proxy_request_buffering off;
        proxy_read_timeout 180s;
        proxy_send_timeout 180s;
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
    }
    location /mlflow-api/ { return 503; }
    location /v3/api-docs { proxy_pass http://backend:8080; }
    location = /index.html { add_header Cache-Control "no-store" always; }
    location ~* \.(js|css|woff2?|png|jpg|svg|ico)$ { try_files $uri =404; }
    location / { try_files $uri $uri/ /index.html; }
}
NGINX
python3 - <<'PY'
from pathlib import Path
path = Path('compose.yml')
text = path.read_text()
marker = '      - /var/www:/usr/share/nginx/html:ro'
if marker not in text:
    anchor = '    build: ./frontend-image\n'
    if text.count(anchor) != 1:
        raise SystemExit('Unexpected frontend Compose structure; no changes made')
    text = text.replace(anchor, anchor + '    volumes:\n' + marker + '\n      - ./frontend-labeling.conf:/etc/nginx/conf.d/default.conf:ro\n', 1)
    path.write_text(text)
elif '      - ./frontend-labeling.conf:/etc/nginx/conf.d/default.conf:ro' not in text:
    raise SystemExit('Incomplete existing bridge configuration; review manually')
PY
docker compose --env-file runtime.conf config --quiet
changed=true
docker compose --env-file runtime.conf up -d --no-deps frontend
docker exec tss-trunk-8001-frontend-1 nginx -t
curl --retry 10 --retry-delay 1 --retry-connrefused -fsS http://127.0.0.1:8001/healthz
expected=$(sha256sum /var/www/index.html | cut -d ' ' -f1)
actual=$(curl -fsS http://127.0.0.1:8001/index.html | sha256sum | cut -d ' ' -f1)
test "$actual" = "$expected"
code=$(curl -sS -o /var/tmp/tss-labeling-8001-probe.json -w '%{http_code}' http://127.0.0.1:8001/api/labeling/v1/me)
test "$code" = 401
python3 - <<'PY'
import json
with open('/var/tmp/tss-labeling-8001-probe.json') as stream:
    body = json.load(stream)
assert body['error']['code'] == 'UNAUTHENTICATED'
assert body.get('requestId')
PY
echo "8001 now serves the shared frontend release. Backup: $backup"
