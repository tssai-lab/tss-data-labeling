#!/usr/bin/env bash
# Manual preparation only, after explicit authorization. No service restart.
set -Eeuo pipefail
umask 022
[[ $EUID == 0 && $# == 1 ]]
archive=$1
[[ $archive == /var/tmp/annotation-python-3.11.18-20261110.tar.gz && -f $archive && ! -L $archive ]]
expected=3288c54cea3db350052019b3f8b558dd8a3da8afa6fda573f5fd2bcb37635b4a
printf '%s  %s\n' "$expected" "$archive" | sha256sum -c -
base=/opt/tss-labeling-service/runtimes
target=$base/cpython-3.11.18-20261110
[[ -d /opt/tss-labeling-service && ! -L /opt/tss-labeling-service ]]
[[ ! -e $target && ! -L $target && ! -e $base/python3.11 && ! -L $base/python3.11 ]]
before=$(systemctl show tss-labeling.service --property=MainPID --value)
install -d -m 755 "$base"
stage=$(mktemp -d "$base/.python-3.11.18.XXXXXX")
chmod 755 "$stage"
python3 - "$archive" "$stage" <<'PY'
import posixpath,sys,tarfile
from pathlib import PurePosixPath
with tarfile.open(sys.argv[1],'r:gz') as source:
    for member in source.getmembers():
        path=PurePosixPath(member.name)
        if path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0]!='python':raise ValueError('Unsafe runtime archive member')
        if member.issym() or member.islnk():
            linked=posixpath.normpath(posixpath.join(str(path.parent),member.linkname) if member.issym() else member.linkname)
            if not linked.startswith('python/'):raise ValueError('Runtime link escapes installation')
        elif not (member.isdir() or member.isfile()):raise ValueError('Unsupported runtime member')
    source.extractall(sys.argv[2])
PY
"$stage/python/bin/python3.11" -I -c 'import ssl,sqlite3,venv,ensurepip; import sys; assert sys.version_info[:2]==(3,11)'
mv -T "$stage" "$target"
"$target/python/bin/python3.11" -m venv "$target/venv-check"
"$target/venv-check/bin/python" -I -c 'import ssl,venv; print("Private Python and venv verified")'
ln -s "$target/python" "$base/python3.11"
runuser -u tsslabeling -- "$base/python3.11/bin/python3.11" -I -c 'import ssl,venv,ensurepip; print("Service account can use private Python")'
after=$(systemctl show tss-labeling.service --property=MainPID --value)
[[ $before == "$after" ]]
systemctl is-active --quiet tss-labeling.service
printf 'Private Python installed at %s; existing service PID %s unchanged.\n' "$base/python3.11" "$after"
