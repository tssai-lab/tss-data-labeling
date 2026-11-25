"""Exercise the shipped LS source without identity, database or network access."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile

XML = '<View><TimeSeries name="curve" value="$readings" valueType="json" timeColumn="t"><Channel column="v"/></TimeSeries><Choices name="state" toName="curve"><Choice value="正常"/></Choices></View>'


def check(source):
    source = source.resolve(strict=True)
    # Verify the newly shipped codecs and all optional binary format dependencies too.
    import importlib.util
    spec = importlib.util.spec_from_file_location('release_record_codec', source / 'record_codec.py')
    codec = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(codec)
    from openpyxl import Workbook
    import pyarrow as pa
    import pyarrow.parquet as pq
    with tempfile.TemporaryDirectory(prefix='record-release-check-') as folder:
        root = Path(folder)
        workbook = Workbook()
        workbook.active.append(['id', 'value'])
        workbook.active.append(['001', 0])
        workbook.save(root / 'check.xlsx')
        assert list(codec.parse_file(root / 'check.xlsx', 'check.xlsx', True))[0]['record'] == {'id': '001', 'value': 0}
        pq.write_table(pa.Table.from_pylist([{'id': '001', 'value': 0}]), root / 'check.parquet')
        assert list(codec.parse_file(root / 'check.parquet', 'check.parquet', True))[0]['record']['id'] == '001'
        codec.validate_config({'schema': {'type': 'object'}})
        codec.validate_result({'id': '001'}, {'labelId': 'ok', 'nodes': [{'path': '/id', 'labelId': 'ok'}]}, ['ok'], True, True, {})
    with tempfile.TemporaryDirectory(prefix='labeling-release-check-') as folder:
        root = Path(folder)
        def invoke(action, **fields):
            job = root / action
            job.mkdir()
            request = {'protocolVersion': 1, 'action': action, 'labelConfig': XML, **fields}
            (job / 'request.json').write_text(json.dumps(request), encoding='utf-8')
            if action == 'export':
                tasks = [{'id': 'release-check', 'data': {'readings': [{'t': 0, 'v': 1}]}, 'annotations': [{'id': 1, 'result': [{'from_name': 'state', 'to_name': 'curve', 'type': 'choices', 'value': {'choices': ['正常']}}]}]}]
                (job / 'tasks.json').write_text(json.dumps(tasks), encoding='utf-8')
            result = subprocess.run([sys.executable, '-E', '-s', '-X', 'utf8', str(source / 'adapter.py'), str(job)], cwd=source, capture_output=True, timeout=45, encoding='utf-8')
            if result.returncode:
                raise RuntimeError(f'Shipped LS source failed: {action}: {result.stderr[-1500:]}')
            response = json.loads(result.stdout)
            if response.get('protocolVersion') != 1 or response.get('ok') is not True:
                raise RuntimeError('Invalid LS source response: ' + action)
            return response['data']
        invoke('inspect')
        invoke('templates')
        invoke('validate')
        invoke('sample')
        invoke('validateResult', stage='complete', envelope={'result': [{'from_name': 'state', 'to_name': 'curve', 'type': 'choices', 'value': {'choices': ['正常']}}]}, taskData={'readings': [{'t': 0, 'v': 1}]})
        exported = invoke('export', format='JSON')
        if not exported.get('files'):
            raise RuntimeError('Shipped exporter produced no file')
        result = json.loads((root / 'export/output/result.json').read_text(encoding='utf-8'))
        if result[0]['annotations'][0]['result'][0]['value']['choices'] != ['正常']:
            raise RuntimeError('Exported label changed')
    print('LS release runtime: inspect, templates, config/result validation, sample and JSON export passed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--adapter-root', type=Path, required=True)
    check(parser.parse_args().adapter_root)
