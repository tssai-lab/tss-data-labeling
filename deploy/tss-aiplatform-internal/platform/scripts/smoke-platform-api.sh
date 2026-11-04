#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-platform.sh
source "${script_dir}/lib-platform.sh"

node_config="${1:-}"
platform_config="${2:-}"
load_platform_config "$node_config" "$platform_config"
[[ $(id -u) -eq 0 ]] || die "API smoke must run as root"
require_control_plane_identity
command -v curl >/dev/null || die "curl is required"
command -v openssl >/dev/null || die "openssl is required"
command -v python3 >/dev/null || die "python3 is required"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
chmod 0700 "$tmp_dir"
stamp="$(date +%s)"
username="c5smoke${stamp}"
password="C5_$(openssl rand -hex 6)"
object_name="c5-smoke/${stamp}.txt"
backend_url="http://${TSS_PLATFORM_BIND_IP}:${TSS_BACKEND_PORT}"

python3 - "$tmp_dir/register.json" "$username" "$password" <<'PY'
import json, sys
path, username, password = sys.argv[1:]
with open(path, "w", encoding="utf-8") as out:
    json.dump({"username": username, "password": password, "confirmPassword": password}, out)
PY
chmod 0600 "$tmp_dir/register.json"
register_status="$(curl --silent --show-error --output "$tmp_dir/register.response" \
  --write-out '%{http_code}' --max-time 10 -X POST \
  -H 'Content-Type: application/json' --data-binary "@$tmp_dir/register.json" \
  "$backend_url/api/user/register/username")"
[[ $register_status == 200 ]] || die "normal-user registration HTTP request failed"
python3 - "$tmp_dir/register.response" <<'PY'
import json, sys
payload = json.load(open(sys.argv[1], encoding="utf-8"))
if int(payload.get("code", -1)) != 200:
    raise SystemExit("normal-user registration business result failed")
PY

python3 - "$tmp_dir/login.json" "$username" "$password" <<'PY'
import json, sys
path, username, password = sys.argv[1:]
with open(path, "w", encoding="utf-8") as out:
    json.dump({"type": "account", "username": username, "password": password}, out)
PY
chmod 0600 "$tmp_dir/login.json"
login_status="$(curl --silent --show-error --output "$tmp_dir/login.response" \
  --write-out '%{http_code}' --max-time 10 -X POST \
  -H 'Content-Type: application/json' --data-binary "@$tmp_dir/login.json" \
  "$backend_url/api/user/login")"
[[ $login_status == 200 ]] || die "normal-user login HTTP request failed"
token="$(python3 - "$tmp_dir/login.response" <<'PY'
import json, sys
payload = json.load(open(sys.argv[1], encoding="utf-8"))
if int(payload.get("code", -1)) != 200:
    raise SystemExit("normal-user login business result failed")
data = payload.get("data") or {}
if int(data.get("roleId", -1)) != 3 or not data.get("token"):
    raise SystemExit("registered smoke user did not receive the normal-user role/token")
print(data["token"])
PY
)"
{
  printf 'header = "Authorization: Bearer %s"\n' "$token"
  printf 'silent\nshow-error\nmax-time = 10\n'
} >"$tmp_dir/auth.curl"
chmod 0600 "$tmp_dir/auth.curl"

permission_status="$(curl --config "$tmp_dir/auth.curl" --output "$tmp_dir/permission.response" \
  --write-out '%{http_code}' "$backend_url/api/system/config/resource-policy/get")"
python3 - "$permission_status" "$tmp_dir/permission.response" <<'PY'
import json, sys
http_status, path = sys.argv[1:]
payload = json.load(open(path, encoding="utf-8"))
if http_status != "403" and int(payload.get("code", -1)) != 403:
    raise SystemExit("normal user was not denied the super-administrator resource policy")
PY

printf 'tss C5 object-storage smoke %s\n' "$stamp" >"$tmp_dir/payload.txt"
upload_status="$(curl --config "$tmp_dir/auth.curl" --output "$tmp_dir/upload.response" \
  --write-out '%{http_code}' -X POST \
  --form "file=@$tmp_dir/payload.txt;type=text/plain" \
  --form "objectName=$object_name" "$backend_url/api/files/upload")"
[[ $upload_status == 200 ]] || die "authenticated object upload HTTP request failed"
stored_name="$(python3 - "$tmp_dir/upload.response" <<'PY'
import json, sys
payload = json.load(open(sys.argv[1], encoding="utf-8"))
if payload.get("success") is not True:
    raise SystemExit("authenticated object upload business result failed")
print((payload.get("data") or {}).get("objectName") or "")
PY
)"
[[ $stored_name == users/*/files/$object_name ]] \
  || die "uploaded smoke object did not receive the normal-user storage prefix"
curl --config "$tmp_dir/auth.curl" --fail --output "$tmp_dir/downloaded.txt" \
  --get --data-urlencode "objectName=$stored_name" "$backend_url/api/files/download"
cmp "$tmp_dir/payload.txt" "$tmp_dir/downloaded.txt" \
  || die "downloaded smoke object differs from the uploaded bytes"
delete_status="$(curl --config "$tmp_dir/auth.curl" --output "$tmp_dir/delete.response" \
  --write-out '%{http_code}' -X DELETE --get \
  --data-urlencode "objectName=$stored_name" "$backend_url/api/files/delete")"
[[ $delete_status == 200 ]] || die "smoke object cleanup request failed"
python3 - "$tmp_dir/delete.response" <<'PY'
import json, sys
payload = json.load(open(sys.argv[1], encoding="utf-8"))
if payload.get("success") is not True or not (payload.get("data") or {}).get("minioDeleteQueued"):
    raise SystemExit("smoke object cleanup was not queued")
PY
curl --config "$tmp_dir/auth.curl" --silent --show-error --output /dev/null \
  -X POST "$backend_url/api/user/logout"
unset token password
echo "PASS: normal-user register/login, permission denial and MinIO upload/download/cleanup flow succeeded"
