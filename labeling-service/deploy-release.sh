#!/usr/bin/env bash
# Executed only by the authorized deployment workflow, never by local tests.
set -Eeuo pipefail
umask 022
[[ $EUID == 0 && $# == 4 ]]
artifact=$1 digest=$2 revision=$3 bundle_tool=$4
[[ $revision =~ ^[0-9a-f]{40}$ && $digest =~ ^[0-9a-f]{64}$ ]]
[[ $artifact =~ ^/var/tmp/tss-labeling-[0-9a-f]{40}-[0-9]+-[0-9]+\.tar\.gz$ ]]
[[ $bundle_tool == "${artifact%.tar.gz}-tool.py" ]]
[[ -f $artifact && ! -L $artifact && -f $bundle_tool && ! -L $bundle_tool ]]
printf '%s  %s\n' "$digest" "$artifact" | sha256sum -c -
exec 9>/run/lock/tss-labeling-release.lock
flock -w 120 9
base=/opt/tss-labeling-service
target=$base/tss-labeling-service.jar
dropin=/etc/systemd/system/tss-labeling.service.d/30-ls-source.conf
[[ -d $base && ! -L $base && -f $target ]]
if [[ -L $target ]]; then [[ $(readlink "$target") == "$base/current/service.jar" ]]; fi
if [[ -x $base/runtimes/python3.11/bin/python3.11 ]]; then
    python_bin=$base/runtimes/python3.11/bin/python3.11
elif ! python_bin=$(command -v python3.11); then echo 'python3.11 with venv must be available before deployment.' >&2; exit 1; fi
"$python_bin" -c 'import sys; assert sys.version_info[:2] == (3, 11)'
command -v psql >/dev/null
systemctl is-active --quiet tss-labeling.service
curl -fsS --max-time 10 http://127.0.0.1:8083/api/labeling/v1/health >/dev/null
install -d -m 755 "$base/releases"
install -d -m 700 "$base/backups"
release=$(mktemp -d "$base/releases/${revision}.XXXXXX")
chmod 755 "$release"
"$python_bin" "$bundle_tool" verify "$artifact" --revision "$revision" --destination "$release" >/dev/null
"$python_bin" -m venv "$release/venv"
"$release/venv/bin/python" -m pip install --no-index --require-hashes --find-links "$release/wheels" -r "$release/requirements.lock"
"$release/venv/bin/python" "$release/release/runtime_check.py" --adapter-root "$release/ls-source-adapter"
backup=$(mktemp -d "$base/backups/${revision}.XXXXXX")
cp -pL "$target" "$backup/service.jar"
old_current=''
if [[ -e $base/current || -L $base/current ]]; then
    [[ -L $base/current ]]
    old_current=$(readlink "$base/current")
    [[ $old_current == "$base/releases/"* && -d $old_current ]]
fi
if [[ -e $dropin ]]; then [[ -f $dropin && ! -L $dropin ]]; cp -p "$dropin" "$backup/ls-source.conf"; fi
activated=false
finish() {
    local status=$?
    trap - EXIT
    set +e
    if [[ $status != 0 ]]; then
        if [[ $activated == true ]]; then
            echo 'Activation failed; restoring the previous JAR and runtime configuration.' >&2
            install -o root -g root -m 644 "$backup/service.jar" "${target}.next"
            mv -Tf "${target}.next" "$target"
            if [[ -n $old_current ]]; then
                ln -s "$old_current" "$base/.restore-current-$$"
                mv -Tf "$base/.restore-current-$$" "$base/current"
            elif [[ -L $base/current && $(readlink "$base/current") == "$release" ]]; then
                rm -- "$base/current"
            fi
            if [[ -f $backup/ls-source.conf ]]; then install -m 644 "$backup/ls-source.conf" "$dropin"; else rm -f -- "$dropin"; fi
            systemctl daemon-reload
        fi
        if [[ $activated == true ]] || ! systemctl is-active --quiet tss-labeling.service; then
            systemctl restart tss-labeling.service || echo 'Previous service did not restart; inspect migrations before restoring the database.' >&2
        fi
        echo "Database backup and failed release retained: $backup; $release" >&2
    fi
    rm -f -- "$artifact" "$bundle_tool"
    exit "$status"
}
trap finish EXIT
service_pid=$(systemctl show tss-labeling.service --property MainPID --value)
"$python_bin" "$release/release/backup_database.py" --pid "$service_pid" --output "$backup/labeling.dump"
activated=true
install -d -m 755 "$(dirname "$dropin")"
cat > "$release/runtime.env" <<EOF
LABELING_LS_SOURCE_PYTHON=$base/current/venv/bin/python
LABELING_LS_SOURCE_ADAPTER_ROOT=$base/current/ls-source-adapter
EOF
cat > "$release/ls-source.conf" <<EOF
[Service]
EnvironmentFile=$base/current/runtime.env
EOF
install -m 644 "$release/ls-source.conf" "$dropin"
ln -s "$release" "$base/.current-$$"
mv -Tf "$base/.current-$$" "$base/current"
ln -s "$base/current/service.jar" "$base/.jar-$$"
mv -Tf "$base/.jar-$$" "$target"
systemctl daemon-reload
systemctl restart tss-labeling.service
for attempt in {1..15}; do
    if systemctl is-active --quiet tss-labeling.service && \
       curl -fsS --max-time 15 http://127.0.0.1:8083/api/labeling/v1/health/ready >/dev/null; then
        printf '%s\n' "$revision" > "$base/deployed-revision"
        printf '%s\n' "$backup" > "$base/previous-backup"
        echo "Labeling release activated: $revision"
        exit 0
    fi
    sleep 2
done
echo 'Readiness failed; restoring the previous application. Database migrations are not reversed automatically.' >&2
exit 1
