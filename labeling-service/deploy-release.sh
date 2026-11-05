#!/usr/bin/env bash
# 由 GitHub Actions 通过 SSH 在服务器上执行；只替换新标注服务的 JAR。
set -Eeuo pipefail
[[ $EUID == 0 ]]
[[ $# == 3 ]]
artifact=$1
digest=$2
revision=$3
[[ $revision =~ ^[0-9a-f]{40}$ && $digest =~ ^[0-9a-f]{64}$ ]]
[[ $artifact =~ ^/var/tmp/tss-labeling-[0-9a-f]{40}-[0-9]+-[0-9]+\.jar$ ]]
[[ -f $artifact && ! -L $artifact ]]
printf '%s  %s\n' "$digest" "$artifact" | sha256sum -c -
exec 9>/run/lock/tss-labeling-release.lock
flock -w 120 9
target=/opt/tss-labeling-service/tss-labeling-service.jar
previous=${target}.previous
[[ -f $target && ! -L $target ]]
systemctl is-active --quiet tss-labeling.service
curl -fsS --max-time 10 http://127.0.0.1:8083/api/labeling/v1/health >/dev/null
cp -p "$target" "$previous"
activated=false
finish() {
    local status=$?
    trap - EXIT
    if [[ $status != 0 && $activated == true ]]; then
        echo '部署未通过检查，恢复上一版 JAR。' >&2
        install -o root -g root -m 644 "$previous" "${target}.next"
        mv -f "${target}.next" "$target"
        if ! systemctl restart tss-labeling.service; then
            echo '旧版服务恢复失败，需要检查服务日志和数据库兼容性。' >&2
        fi
    fi
    rm -f -- "$artifact"
    exit "$status"
}
trap finish EXIT
install -o root -g root -m 644 "$artifact" "${target}.next"
activated=true
mv -f "${target}.next" "$target"
systemctl restart tss-labeling.service
for attempt in {1..30}; do
    if systemctl is-active --quiet tss-labeling.service && \
       curl -fsS --max-time 3 http://127.0.0.1:8083/api/labeling/v1/health >/dev/null; then
        printf '%s\n' "$revision" > /opt/tss-labeling-service/deployed-revision
        echo "标注服务已部署：$revision"
        exit 0
    fi
    sleep 2
done
echo '标注服务未在规定时间内恢复 HTTP 响应。' >&2
exit 1
