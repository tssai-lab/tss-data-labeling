"""Release-boundary tests; no systemctl, shell deployment or live database calls."""
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
from subprocess import CompletedProcess
from backup_database import postgres_environment, dump_plan
from release_bundle import safe_name, verify


class ReleaseBoundaryTests(unittest.TestCase):
    def archive(self, root, content=b'jar', declared=None, extra=None):
        artifact=root/'release.tar.gz'
        manifest={'schemaVersion':1,'revision':'a'*40,'python':'3.11','platform':'linux-x86_64',
                  'files':{'service.jar':declared or hashlib.sha256(content).hexdigest()}}
        entries={'release.json':json.dumps(manifest).encode(),'service.jar':content}
        if extra: entries.update(extra)
        with tarfile.open(artifact,'w:gz') as output:
            for name,data in entries.items():
                info=tarfile.TarInfo(name);info.size=len(data);output.addfile(info,io.BytesIO(data))
        return artifact

    def test_manifest_revision_checksums_and_safe_extraction(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);artifact=self.archive(root)
            verify(artifact,'a'*40,root/'unpacked')
            self.assertEqual(b'jar',(root/'unpacked/service.jar').read_bytes())
            with self.assertRaises(ValueError):verify(artifact,'b'*40)
            with self.assertRaises(ValueError):verify(artifact,destination=root/'unpacked')

    def test_tampering_and_unlisted_entries_fail_before_extraction(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for options in ({'declared':'0'*64},{'extra':{'unlisted':b'x'}}):
                artifact=self.archive(root,**options)
                with self.assertRaises(ValueError):verify(artifact,destination=root/'unpacked')
            self.assertFalse((root/'unpacked').exists())

    def test_path_traversal_and_links_are_rejected(self):
        for name in ('../outside','/absolute','a/../../outside','a\\outside','a/./file','C:/outside','.'):
            with self.assertRaises(ValueError):safe_name(name)
        with tempfile.TemporaryDirectory() as directory:
            artifact=Path(directory)/'link.tar.gz'
            with tarfile.open(artifact,'w:gz') as output:
                info=tarfile.TarInfo('link');info.type=tarfile.SYMTYPE;info.linkname='/outside';output.addfile(info)
            with self.assertRaises(ValueError):verify(artifact)

    def test_backup_uses_service_database_and_migration_credentials(self):
        env=postgres_environment({'LABELING_DATABASE_URL':'jdbc:postgresql://127.0.0.1:55432/labeling_only?currentSchema=labeling&sslmode=require',
                                  'LABELING_MIGRATION_USERNAME':'migration','LABELING_MIGRATION_PASSWORD':'not-logged'})
        self.assertEqual('55432',env['PGPORT']);self.assertEqual('labeling_only',env['PGDATABASE'])
        self.assertEqual('migration',env['PGUSER']);self.assertEqual('require',env['PGSSLMODE'])
        for url in ('jdbc:mysql://localhost/db','jdbc:postgresql://localhost/db?currentSchema=public','jdbc:postgresql://user:secret@localhost/db'):
            with self.assertRaises(ValueError):postgres_environment({'LABELING_DATABASE_URL':url})

    def test_old_host_client_uses_only_matching_database_container(self):
        env={'PGHOST':'127.0.0.1','PGPORT':'5435','PGDATABASE':'labeling','PGUSER':'owner','PGPASSWORD':'not-logged','PGCONNECT_TIMEOUT':'10'}
        def probe(command,**options):
            if 'SHOW server_version_num' in command:return CompletedProcess(command,0,'160013\n','')
            if command[0]=='pg_dump':return CompletedProcess(command,0,'pg_dump (PostgreSQL) 12.22','')
            if 'inspect' in command:return CompletedProcess(command,0,'{"5432/tcp":[{"HostPort":"5435"}]}','')
            return CompletedProcess(command,0,'pg_dump (PostgreSQL) 16.13','')
        with patch('backup_database.shutil.which',side_effect=lambda command:command),patch('backup_database.Path.is_file',return_value=False),patch('backup_database.subprocess.run',side_effect=probe):
            command,forwarded=dump_plan(env)
            self.assertIn('tss-labeling-postgres',command);self.assertIn('PGPASSWORD',command)
            self.assertNotIn('not-logged',command);self.assertEqual('5432',forwarded['PGPORT']);self.assertEqual('5435',env['PGPORT'])
            with self.assertRaises(ValueError):dump_plan({**env,'PGPORT':'5436'})
            with self.assertRaises(ValueError):dump_plan({**env,'PGHOST':'another-host'})

    def test_compatible_host_client_needs_no_container(self):
        def probe(command,**options):return CompletedProcess(command,0,'160013' if command[0]=='psql' else 'pg_dump (PostgreSQL) 16.13','')
        with patch('backup_database.shutil.which',side_effect=lambda command:command),patch('backup_database.subprocess.run',side_effect=probe):
            command,_=dump_plan({'PGHOST':'127.0.0.1','PGPORT':'5435'})
            self.assertEqual(['pg_dump'],command)


if __name__=='__main__':unittest.main()
