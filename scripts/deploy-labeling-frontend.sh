#!/usr/bin/env bash
# 静态资源已上传；这里原子替换统一前端入口，并检查 Nginx 实际返回内容。
set -Eeuo pipefail
[[ $EUID == 0 ]]
[[ $# == 3 ]]
artifact=$1
digest=$2
revision=$3
[[ $revision =~ ^[0-9a-f]{40}$ && $digest =~ ^[0-9a-f]{64}$ ]]
[[ $artifact =~ ^/var/tmp/tss-frontend-[0-9a-f]{40}-[0-9]+-[0-9]+\.html$ ]]
[[ -f $artifact && ! -L $artifact ]]
printf '%s  %s\n' "$digest" "$artifact" | sha256sum -c -
exec 9>/run/lock/tss-labeling-frontend-release.lock
flock -w 120 9
target=/var/www/index.html
previous=/var/lib/tss-labeling-frontend.previous.html
[[ -f $target && ! -L $target ]]
cp -p "$target" "$previous"
activated=false
finish() {
    local status=$?
    trap - EXIT
    if [[ $status != 0 && $activated == true ]]; then
        echo '入口检查失败，恢复上一版 index.html。' >&2
        install -o www-data -g www-data -m 644 "$previous" "${target}.next"
        mv -f "${target}.next" "$target"
    fi
    rm -f -- "$artifact"
    exit "$status"
}
trap finish EXIT
install -o www-data -g www-data -m 644 "$artifact" "${target}.next"
activated=true
mv -f "${target}.next" "$target"
served_digest=$(curl -fsS --max-time 15 -H 'Host: 47.111.225.144' \
    'http://127.0.0.1/index.html' | sha256sum | cut -d ' ' -f1)
[[ $served_digest == "$digest" ]]
# The public 8001 entry must see this release too, not an older image layer.
entry_digest=$(curl -fsS --max-time 15 \
    'http://127.0.0.1:8001/index.html' | sha256sum | cut -d ' ' -f1)
[[ $entry_digest == "$digest" ]]
# Distinguish the labeling service from the legacy training API at this path.
probe=$(curl -sS --max-time 15 -w '|%{http_code}' \
    'http://127.0.0.1:8001/api/labeling/v1/me')
[[ $probe == *'|401' ]]
python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["error"]["code"] == "UNAUTHENTICATED" and r.get("requestId")' \
    <<< "${probe%|401}"
printf '%s\n' "$revision" > /var/lib/tss-labeling-frontend.revision
echo "统一前端已部署：$revision"
