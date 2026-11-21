"""Deployment-only backup using the running service's actual environment.

Never prints credentials. Stops that one service after checking all inputs, then
takes a consistent labeling-schema dump. Local tests only exercise the parser.
"""
import argparse
import os
import json
import re
from pathlib import Path
import shutil
import subprocess
from urllib.parse import parse_qs, unquote, urlparse


def postgres_environment(service):
    url = service.get('SPRING_DATASOURCE_URL') or service.get('LABELING_DATABASE_URL', '')
    if service.get('SPRING_FLYWAY_URL', url) != url:
        raise ValueError('Separate Flyway and application databases require an explicit backup plan')
    if not url.startswith('jdbc:postgresql://'):
        raise ValueError('The service must declare its PostgreSQL JDBC URL for backup')
    parsed = urlparse(url[5:])
    if not parsed.hostname or not parsed.path.strip('/') or parsed.username or parsed.password:
        raise ValueError('Invalid database URL')
    parameters = parse_qs(parsed.query)
    if parameters.get('currentSchema', ['labeling']) != ['labeling']:
        raise ValueError('Backup is restricted to the labeling schema')
    result = {k: v for k, v in os.environ.items() if not k.startswith('PG')}
    result.update(PGHOST=parsed.hostname, PGPORT=str(parsed.port or 5432), PGDATABASE=unquote(parsed.path.lstrip('/')),
                  PGUSER=service.get('SPRING_FLYWAY_USER') or service.get('LABELING_MIGRATION_USERNAME', 'tss_labeling_owner'),
                  PGPASSWORD=service.get('SPRING_FLYWAY_PASSWORD', service.get('LABELING_MIGRATION_PASSWORD', '')),
                  PGCONNECT_TIMEOUT='10')
    if 'sslmode' in parameters:
        result['PGSSLMODE'] = parameters['sslmode'][0]
    return result


def dump_plan(environment, container='tss-labeling-postgres'):
    """Choose a compatible client before stopping the application."""
    psql=shutil.which('psql')
    if psql is None:raise ValueError('psql is required to check the actual database version')
    version=subprocess.run([psql,'--no-password','-Atc','SHOW server_version_num'],env=environment,capture_output=True,text=True,timeout=15)
    if version.returncode or not version.stdout.strip().isdigit():raise ValueError('Database version check failed; no service stopped')
    major=int(version.stdout.strip())//10000
    candidates=[shutil.which('pg_dump'),f'/usr/lib/postgresql/{major}/bin/pg_dump']
    for executable in candidates:
        if executable and (executable==candidates[0] or Path(executable).is_file()):
            probe=subprocess.run([executable,'--version'],capture_output=True,text=True,timeout=10)
            found=re.search(r'PostgreSQL\) (\d+)',probe.stdout)
            if probe.returncode==0 and found and int(found.group(1))>=major:return [executable],environment
    docker=shutil.which('docker')
    if not docker or environment['PGHOST'] not in ('127.0.0.1','localhost','::1') or not re.fullmatch(r'[A-Za-z0-9_.-]+',container):
        raise ValueError('No compatible pg_dump client; no service stopped')
    probe=subprocess.run([docker,'inspect','--format','{{json .NetworkSettings.Ports}}',container],capture_output=True,text=True,timeout=10)
    bindings=json.loads(probe.stdout) if probe.returncode==0 else {}
    if not any(item.get('HostPort')==environment['PGPORT'] for item in bindings.get('5432/tcp',[]) or []):
        raise ValueError('Backup container does not match the configured database port')
    probe=subprocess.run([docker,'exec',container,'pg_dump','--version'],capture_output=True,text=True,timeout=10)
    found=re.search(r'PostgreSQL\) (\d+)',probe.stdout)
    if probe.returncode or not found or int(found.group(1))<major:raise ValueError('Container pg_dump is incompatible')
    forwarded={**environment,'PGHOST':'127.0.0.1','PGPORT':'5432'}
    command=[docker,'exec']
    for key in ('PGHOST','PGPORT','PGDATABASE','PGUSER','PGPASSWORD','PGCONNECT_TIMEOUT','PGSSLMODE'):
        if key in forwarded:command.extend(['-e',key])
    return command+[container,'pg_dump'],forwarded


def backup(pid, output):
    if pid <= 1 or not output.parent.is_dir() or output.exists():
        raise ValueError('Invalid backup PID or output')
    service = {}
    for entry in Path(f'/proc/{pid}/environ').read_bytes().split(b'\0'):
        if b'=' in entry:
            name, value = entry.split(b'=', 1)
            service[name.decode()] = value.decode()
    environment = postgres_environment(service)
    command,environment=dump_plan(environment,service.get('LABELING_PG_DUMP_CONTAINER','tss-labeling-postgres'))
    subprocess.run(['systemctl', 'stop', 'tss-labeling.service'], check=True, timeout=60)
    os.umask(0o077)
    with output.open('xb') as dump:
        subprocess.run(command+['--no-password','--format=custom','--schema=labeling'],stdout=dump,env=environment,check=True,timeout=600)
    if output.stat().st_size == 0:
        raise RuntimeError('Database backup was empty')
    print('Labeling schema backed up; service stopped for activation')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--pid', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    options = parser.parse_args()
    backup(options.pid, options.output)
