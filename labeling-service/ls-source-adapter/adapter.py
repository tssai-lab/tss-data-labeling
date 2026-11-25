"""Offline, versioned file protocol around the editable Label Studio source.

No Django, SDK client, listening port, token, or network resource download.
The Java process supplies one private job directory; stdout contains only JSON.
"""
import json
import logging
import re
import socket
import sys
import math
import csv
import copy
from functools import lru_cache
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent / 'vendor'))

from lxml import etree
from label_studio_sdk._extensions.label_studio_tools.core.label_config import parse_config
import platform_resources

PROTOCOL_VERSION = 1
# Other upstream formats remain in source and capability metadata. Enable each
# platform format after its resources and output have been verified locally.
ENABLED_FORMATS = {'JSON', 'JSON_MIN', 'CSV', 'TSV', 'COCO', 'YOLO'}
SAFE_ID = re.compile(r'[A-Za-z0-9_-]{1,128}\Z')
EDITOR_TAGS = set(json.loads((Path(__file__).parent / 'editor-tags.json').read_text(encoding='utf-8'))['tags'])


def parse_xml(xml):
    if not isinstance(xml, str) or not xml.strip() or len(xml) > 262144:
        raise ValueError('INVALID_LABEL_CONFIG')
    # Upstream parses twice (core and converter). Reject declarations before
    # either call, and enforce a View root and unambiguous named bindings.
    if '<!DOCTYPE' in xml.upper() or '<!ENTITY' in xml.upper():
        raise ValueError('XML_DECLARATION_NOT_ALLOWED')
    root = etree.fromstring(xml.encode('utf-8'), parser=etree.XMLParser(
        resolve_entities=False, no_network=True, load_dtd=False))
    if root.tag != 'View':
        raise ValueError('LABEL_CONFIG_ROOT_MUST_BE_VIEW')
    for node in root.iter():
        if not isinstance(node.tag, str):
            continue
        if node.tag.lower() not in EDITOR_TAGS:
            raise ValueError('TAG_NOT_REGISTERED')
        if any(name.lower().startswith('on') or 'javascript:' in value.lower() for name, value in node.attrib.items()):
            raise ValueError('EXECUTABLE_ATTRIBUTE_NOT_ALLOWED')
    names = [node.get('name') for node in root.iter() if node.get('name')]
    if len(names) != len(set(names)):
        raise ValueError('DUPLICATE_TAG_NAME')
    for node in root.iter():
        if node.get('toName'):
            if not node.get('name') or any(name.strip() not in names for name in node.get('toName').split(',')):
                raise ValueError('INVALID_TAG_BINDING')
    # Pass XML bytes to retain Chinese labels and XML encoding declarations.
    parsed = parse_config(xml.encode('utf-8'))
    objects = {node.get('name'): node for node in root.iter() if node.get('name')}
    for control in parsed.values():
        for name, item in zip(control.get('to_name', []), control.get('inputs', [])):
            obj = objects.get(name)
            if obj is not None:
                if 'value' not in item and 'valueList' in item:
                    item['value'] = item['valueList']
                item['valueType'] = obj.get('valueType', obj.get('valuetype', 'url'))
                item['timeColumn'] = obj.get('timeColumn', obj.get('timecolumn', ''))
                item['timeFormat'] = obj.get('timeFormat', obj.get('timeformat', ''))
                item['channels'] = [channel.get('column') for channel in obj.iter('Channel') if channel.get('column')]
    for node in root.iter():
        if node.get('name') in parsed:
            control = parsed[node.get('name')]
            control['required'] = node.get('required', 'false').lower() == 'true'
            if node.tag == 'Choices':
                control['choice'] = node.get('choice', 'single')
    return parsed


def inspect_config(xml):
    from label_studio_sdk.converter.converter import Converter
    parsed = validate_config(xml)['parsedConfig']
    converter = Converter(parsed, project_dir=None, download_resources=False)
    return {
        'parsedConfig': parsed,
        'formats': [dict(name=fmt.name, title=info['title'],
                         supported=fmt.name in converter.supported_formats,
                         enabled=fmt.name in converter.supported_formats and fmt.name in ENABLED_FORMATS)
                    for fmt, info in converter.all_formats().items()],
    }


@lru_cache(maxsize=256)
def _validated_config(xml):
    from ls_backend_config import validate_label_config
    parsed = parse_xml(xml)
    validate_label_config(xml)
    return {'parsedConfig': parsed}


def validate_config(xml):
    return copy.deepcopy(_validated_config(xml))


def sample_config(xml):
    validate_config(xml)
    from platform_preview import preview_task
    return preview_task(xml)


def export(job_root, request):
    from label_studio_sdk.converter.converter import Converter
    metadata = inspect_config(request['labelConfig'])
    fmt = request.get('format')
    if not any(item['name'] == fmt and item['enabled'] for item in metadata['formats']):
        raise ValueError('EXPORT_FORMAT_NOT_SUPPORTED')
    # tasks.json is supplied by the Java caller from a frozen result selection.
    task_dir = job_root / 'tasks'
    prepared = request.get('tasksPrepared') is True
    if prepared:
        tasks = (json.loads(path.read_text(encoding='utf-8')) for path in sorted(task_dir.glob('*.json')))
    else:
        tasks = json.loads((job_root / 'tasks.json').read_text(encoding='utf-8'))
        if not isinstance(tasks, list):
            raise ValueError('TASKS_MUST_BE_ARRAY')
        task_dir.mkdir()
    resources = dict(request.get('resources', {}))
    image_keys = {item['value'] for control in metadata['parsedConfig'].values()
                  for item in control.get('inputs', []) if item['type'] == 'Image'}
    prepared_paths = sorted(task_dir.glob('*.json')) if prepared else []
    count = 0
    for index, task in enumerate(tasks):
        count += 1
        if not isinstance(task, dict) or not isinstance(task.get('data'), dict):
            raise ValueError('INVALID_TASK_DATA')
        if not SAFE_ID.fullmatch(str(task.get('id', ''))):
            raise ValueError('INVALID_TASK_ID')
        annotations = task.get('annotations', [])
        if not isinstance(annotations, list) or any(
                not isinstance(annotation, dict) or not isinstance(annotation.get('result'), list)
                for annotation in annotations):
            raise ValueError('INVALID_ANNOTATION_RESULT')
        if fmt in {'COCO', 'YOLO'}:
            # Upstream accepts disk paths in task.data. Platform inputs are
            # resource references; never let them bypass the explicit resolver.
            for key in image_keys:
                from platform_result import at
                value = at(task['data'], key)
                # SDK converters expect a literal data key; project a private
                # conversion copy while native JSON keeps its original structure.
                task['data'][key] = value
                if not isinstance(value, str):
                    raise ValueError('IMAGE_RESOURCE_REFERENCE_REQUIRED')
                if Path(value).is_file() or re.match(r'^[A-Za-z]:', value) or value.startswith('\\'):
                    raise ValueError('IMAGE_RESOURCE_REFERENCE_REQUIRED')
                if value not in resources and value.startswith(('/', '.')):
                    raise ValueError('IMAGE_RESOURCE_REFERENCE_REQUIRED')
        if prepared and fmt in {'COCO', 'YOLO'}:
            prepared_paths[index].write_text(
                json.dumps(task, ensure_ascii=False, allow_nan=False), encoding='utf-8')
        if not prepared:
            (task_dir / f'{index:012d}.json').write_text(
                json.dumps(task, ensure_ascii=False, allow_nan=False), encoding='utf-8')
    platform_resources.configure(job_root, resources)
    output = job_root / 'output'
    output.mkdir()
    converter = Converter(metadata['parsedConfig'], project_dir=None, download_resources=False)
    # Native upstream directory iteration streams one task at a time, avoiding
    # an ijson dependency and preserving the original LS task/result structure.
    converter.convert(str(task_dir), str(output), fmt, is_dir=True)
    if fmt == 'TSV' and (output / 'result.csv').exists():
        (output / 'result.csv').rename(output / 'result.tsv')
    files = []
    for path in sorted(output.rglob('*')):
        if path.is_file():
            files.append({'path': path.relative_to(output).as_posix(), 'size': path.stat().st_size})
    return {'format': fmt, 'files': files, 'taskCount': count}


def run(job_root):
    request = json.loads((job_root / 'request.json').read_text(encoding='utf-8'))
    if request.get('protocolVersion') != PROTOCOL_VERSION:
        raise ValueError('UNSUPPORTED_PROTOCOL_VERSION')
    if request.get('action', '').startswith('records'):
        from record_codec import run as run_records
        return run_records(job_root, request)
    if request.get('action') == 'templates':
        from ls_backend_templates import platform_catalog
        return platform_catalog()
    if request.get('action') == 'inspect':
        return inspect_config(request['labelConfig'])
    if request.get('action') == 'validate':
        return validate_config(request['labelConfig'])
    if request.get('action') == 'sample':
        return sample_config(request['labelConfig'])
    if request.get('action') == 'export':
        return export(job_root, request)
    if request.get('action') == 'validateTimes':
        platform_resources.configure(job_root, request.get('resources', {}))
        return validate_times(request['labelConfig'], request['result'], request.get('taskData'))
    if request.get('action') == 'validateResult':
        platform_resources.configure(job_root,request.get('resources',{}))
        from platform_result import validate_result
        result=validate_result(request['labelConfig'],request['envelope'],request['stage'],request.get('taskData'),request.get('inputMetadata'),request.get('completion'))
        if any(region.get('type')=='timeserieslabels' for region in request['envelope']['result']):
            validate_times(request['labelConfig'],request['envelope']['result'],request.get('taskData'),partial=request.get('stage')=='draft')
        return result
    raise ValueError('UNSUPPORTED_ACTION')


def validate_times(xml, results, task_data=None, partial=False):
    parse_xml(xml)
    root = etree.fromstring(xml.encode('utf-8'), etree.XMLParser(resolve_entities=False, no_network=True))
    objects = {node.get('name'): node for node in root.iter() if node.get('name')}

    def number(value, fmt):
        if isinstance(value, bool) or not isinstance(value, (str, int, float)):
            raise ValueError('INVALID_TIME_VALUE')
        if not fmt or fmt in ('%Q', '%s'):
            result = float(value)
        else:
            if not isinstance(value, str):
                raise ValueError('FORMATTED_TIME_MUST_BE_STRING')
            # D3's millisecond, space-padded day and numeric zone directives.
            python_format = fmt.replace('%L', '%f').replace('%e', '%d').replace('%Z', '%z')
            date = datetime.strptime(value, python_format)
            if date.tzinfo is None:
                date = date.replace(tzinfo=timezone.utc)  # Matches upstream d3.utcParse.
            result = date.timestamp()
        if not math.isfinite(result):
            raise ValueError('NON_FINITE_TIME')
        return result

    for region in results:
        if region.get('type') != 'timeserieslabels':
            continue
        obj = objects.get(region.get('to_name'))
        if obj is None or obj.tag != 'TimeSeries':
            raise ValueError('INVALID_TIME_SERIES_BINDING')
        fmt = obj.get('timeFormat', obj.get('timeformat', ''))
        value = region['value']
        if partial:
            if 'start' not in value and 'end' not in value:continue
            value=dict(value);value.setdefault('start',value.get('end'));value.setdefault('end',value.get('start'))
        start, end = number(value['start'], fmt), number(value['end'], fmt)
        if start > end or (value.get('instant') and start != end):
            raise ValueError('INVALID_TIME_INTERVAL')
        if task_data is not None:
            from platform_result import at
            data = at(task_data, obj.get('value', '').lstrip('$'))
            if data is None:raise ValueError('INPUT_FIELD_MISSING')
            column = obj.get('timeColumn', obj.get('timecolumn', ''))

            def times(data):
                if isinstance(data, dict):
                    key = next((key for key in data if key.lower() == column.lower()), None)
                    if key is not None:
                        return iter(data[key])
                    if not column:
                        return iter(range(len(next(iter(data.values()), []))))
                elif isinstance(data, list):
                    return ((item[int(column or 0)] if isinstance(item,list) else item.get(column)) for item in data) if column or (data and isinstance(data[0],list)) else iter(range(len(data)))
                raise ValueError('INPUT_TIME_COLUMN_MISSING')

            if isinstance(data, str):
                path = platform_resources.resolve_resource(data)
                with open(path, encoding='utf-8-sig') as source_file:
                    first = source_file.read(1)
                    source_file.seek(0)
                    if first in '[{':
                        values = [number(item, fmt) for item in times(json.load(source_file)) if item is not None and item != '']
                    else:
                        rows = csv.DictReader(source_file,delimiter=obj.get('sep',','))
                        key = next((name for name in (rows.fieldnames or []) if name.lower() == column.lower()), None)
                        if key is None and column.isdigit() and int(column)<len(rows.fieldnames or []):key=rows.fieldnames[int(column)]
                        if column and key is None:
                            raise ValueError('INPUT_TIME_COLUMN_MISSING')
                        values = [number(row[key], fmt) if key else index for index, row in enumerate(rows) if not key or row[key] != '']
            else:
                values = [number(item, fmt) for item in times(data) if item is not None and item != '']
            if not values or start < min(values) or end > max(values):
                raise ValueError('INTERVAL_OUTSIDE_SAMPLE')
    return {'valid': True}


def _deny_network(*args, **kwargs):
    raise ValueError('NETWORK_DISABLED')


def execute(job):
    try:
        platform_resources.configure(job,{})
        result=run(job)
        return {'protocolVersion':PROTOCOL_VERSION,'ok':True,'data':result}
    except Exception as exc:
        logging.exception('LS source adapter failed')
        message=str(exc)[:300] if isinstance(exc,ValueError) else '配置、结果或资源处理失败'
        return {'protocolVersion':PROTOCOL_VERSION,'ok':False,'error':{'code':type(exc).__name__,'message':message}}


if __name__ == '__main__':
    logging.basicConfig(stream=sys.stderr,level=logging.WARNING)
    socket.socket.connect=_deny_network
    socket.socket.connect_ex=_deny_network
    if len(sys.argv)>1 and sys.argv[1]=='--worker':
        for line in sys.stdin:
            try:
                job=Path(json.loads(line)['job']).resolve(strict=True)
                response=execute(job)
            except Exception:
                response={'protocolVersion':PROTOCOL_VERSION,'ok':False,'error':{'code':'INVALID_JOB','message':'内部任务无效'}}
            print(json.dumps(response,ensure_ascii=False,allow_nan=False),flush=True)
    else:
        response=execute(Path(sys.argv[1]).resolve(strict=True))
        print(json.dumps(response,ensure_ascii=False,allow_nan=False))
        sys.exit(0 if response['ok'] else 1)
