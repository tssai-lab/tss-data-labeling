"""Trusted codecs for table rows and document nodes. Never executes input code."""
import csv
import datetime
import decimal
import io
import json
import math
from pathlib import Path

MAX_RECORDS = 10000
MAX_NODES = 20000
MAX_BYTES = 64 * 1024 * 1024


def dumps(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':'))


def load_json(text):
    def unique(pairs):
        obj = {}
        for key, value in pairs:
            if key in obj:
                raise ValueError('重复字段: ' + key)
            obj[key] = value
        return obj
    return json.loads(text, object_pairs_hook=unique,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError('非有限数值')))


def normalize(value, depth=0, seen=None):
    if depth > 64:
        raise ValueError('数据嵌套超过 64 层')
    seen = seen if seen is not None else [0]
    seen[0] += 1
    if seen[0] > MAX_NODES:
        raise ValueError('单条记录节点过多')
    if isinstance(value, str) and len(value) > 1048576:
        raise ValueError('单个文本值过长')
    if value is None or isinstance(value, (str, bool, int)):
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError('非有限数值')
        return value
    if isinstance(value, (datetime.date, datetime.datetime, datetime.time, decimal.Decimal)):
        return str(value)
    if isinstance(value, dict):
        if any(not isinstance(key, str) for key in value):
            raise ValueError('字段名必须是字符串')
        return {key: normalize(item, depth + 1, seen) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [normalize(item, depth + 1, seen) for item in value]
    raise ValueError('不支持的数据值类型: ' + type(value).__name__)


def headers(values):
    if not values or any(not isinstance(v, str) or not v.strip() for v in values) or len(set(values)) != len(values):
        raise ValueError('表头不能为空或重复')
    if len(values) > 1000:
        raise ValueError('表格最多 1000 列')
    return values


def parse_file(path, name, structured):
    suffix = Path(name).suffix.lower()
    if structured and suffix == '.csv':
        with path.open(encoding='utf-8-sig', newline='') as source:
            try:
                dialect = csv.Sniffer().sniff(source.read(8192), delimiters=',;\t')
            except csv.Error:
                dialect = csv.excel
            source.seek(0)
            reader = csv.reader(source, dialect, strict=True)
            names = headers(next(reader, []))
            for index, values in enumerate(reader, 2):
                if len(values) != len(names):
                    yield {'error': '列数与表头不一致', 'row': index}
                else:
                    yield {'record': dict(zip(names, values)), 'row': index}
    elif structured and suffix == '.xlsx':
        from openpyxl import load_workbook
        # An XLSX is itself a ZIP; bound expansion before the workbook parser.
        import zipfile
        with zipfile.ZipFile(path) as archive:
            if len(archive.infolist()) > 10000 or sum(e.file_size for e in archive.infolist()) > MAX_BYTES:
                raise ValueError('XLSX 展开大小超过限制')
        book = load_workbook(io.BytesIO(path.read_bytes()), read_only=True, data_only=False, keep_links=False)
        try:
            for sheet in book:
                iterator = sheet.iter_rows()
                first = next(iterator, [])
                names = headers([cell.value for cell in first])
                for index, cells in enumerate(iterator, 2):
                    if any(cell.data_type == 'f' for cell in cells):
                        yield {'error': '含公式，请先转换为静态值', 'row': index, 'sheet': sheet.title}
                        continue
                    values = [cell.value for cell in cells]
                    if all(value is None for value in values):
                        continue
                    yield {'record': normalize(dict(zip(names, values))), 'row': index, 'sheet': sheet.title}
        finally:
            book.close()
    elif structured and suffix == '.parquet':
        import pyarrow.parquet as pq
        parquet = pq.ParquetFile(path)
        headers(parquet.schema_arrow.names)
        if sum(parquet.metadata.row_group(i).total_byte_size for i in range(parquet.metadata.num_row_groups)) > MAX_BYTES:
            raise ValueError('Parquet 展开大小超过限制')
        if parquet.metadata.num_rows > MAX_RECORDS:
            raise ValueError('单次最多 10000 条记录')
        index = 0
        for batch in parquet.iter_batches(batch_size=100):
            for row in batch.to_pylist():
                index += 1
                yield {'record': normalize(row), 'row': index}
    elif not structured and suffix in {'.json', '.jsonl'}:
        text = path.read_text(encoding='utf-8-sig')
        if suffix == '.jsonl':
            for index, line in enumerate(text.splitlines(), 1):
                if not line.strip():
                    continue
                try:
                    yield {'record': normalize(load_json(line)), 'row': index, 'raw': line}
                except (ValueError, RecursionError) as error:
                    yield {'error': str(error), 'row': index}
        else:
            value = load_json(text)
            for index, record in enumerate(value if isinstance(value, list) else [value], 1):
                yield {'record': normalize(record), 'row': index, 'raw': dumps(record) if isinstance(value, list) else text}
    elif not structured and suffix in {'.yaml', '.yml'}:
        import yaml
        class UniqueSafeLoader(yaml.SafeLoader):
            def construct_mapping(self, node, deep=False):
                keys = [self.construct_object(key, deep=deep) for key, _ in node.value]
                if any(not isinstance(key, str) for key in keys) or len(keys) != len(set(keys)):
                    raise ValueError('YAML 字段名必须是唯一字符串')
                return super().construct_mapping(node, deep=deep)
        text = path.read_text(encoding='utf-8-sig')
        # Disallow aliases to avoid cycles and expansion bombs.
        if any(isinstance(token, (yaml.tokens.AliasToken, yaml.tokens.AnchorToken)) for token in yaml.scan(text)):
            raise ValueError('YAML 不允许锚点或别名')
        docs = list(yaml.load_all(text, Loader=UniqueSafeLoader))
        for index, value in enumerate(docs, 1):
            yield {'record': normalize(value), 'row': index, 'raw': text if len(docs) == 1 else yaml.safe_dump(value, allow_unicode=True)}
    elif not structured and suffix == '.xml':
        from defusedxml import ElementTree as ET
        text = path.read_text(encoding='utf-8-sig')
        def node(element, depth=0):
            if depth > 64:
                raise ValueError('XML 嵌套过深')
            value = {'tag': element.tag, 'attributes': dict(element.attrib), 'text': element.text or '', 'tail': element.tail or ''}
            value['children'] = [node(child, depth + 1) for child in element]
            return value
        root = ET.fromstring(text, forbid_dtd=True, forbid_entities=True, forbid_external=True)
        yield {'record': normalize(node(root)), 'row': 1, 'raw': text}
    else:
        raise ValueError('当前插件不支持此文件格式')


def pointer(value, path):
    if not isinstance(path, str) or (path and not path.startswith('/')) or len(path) > 4096:
        raise ValueError('路径必须是 JSON Pointer（根节点为空）')
    if not path:
        return value
    for token in path[1:].split('/'):
        import re
        if re.search(r'~(?![01])', token):
            raise ValueError('路径转义无效')
        token = token.replace('~1', '/').replace('~0', '~')
        if isinstance(value, dict) and token in value:
            value = value[token]
        elif isinstance(value, list) and token.isdigit() and str(int(token)) == token and int(token) < len(value):
            value = value[int(token)]
        else:
            raise ValueError('路径不存在: ' + path)
    return value


def validate_config(config):
    if not isinstance(config, dict) or set(config) - {'schema', 'blockOnErrors', 'fieldMapping'}:
        raise ValueError('不支持的记录插件配置字段')
    schema = config.get('schema', {})
    from jsonschema import Draft202012Validator
    Draft202012Validator.check_schema(schema)
    def check(value, depth=0):
        if depth > 64:
            raise ValueError('Schema 嵌套过深')
        if isinstance(value, dict):
            for key, item in value.items():
                if key in {'$ref', '$dynamicRef'} and (not isinstance(item, str) or not item.startswith('#')):
                    raise ValueError('Schema 仅允许本地引用')
                check(item, depth + 1)
        elif isinstance(value, list):
            for item in value:
                check(item, depth + 1)
    check(schema)
    if 'blockOnErrors' in config and not isinstance(config['blockOnErrors'], bool):
        raise ValueError('blockOnErrors 必须为布尔值')
    mapping = config.get('fieldMapping', {})
    if not isinstance(mapping, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in mapping.items()):
        raise ValueError('字段映射须为显示名称到 JSON Pointer 的对象')
    return config


def issues(record, config):
    from jsonschema import Draft202012Validator, FormatChecker
    errors = []
    try:
        mapped = {key: pointer(record, path) for key, path in config.get('fieldMapping', {}).items()} or record
        from referencing import Registry
        from referencing.exceptions import NoSuchResource
        def deny_network(uri):
            raise NoSuchResource(ref=uri)
        validator = Draft202012Validator(config.get('schema', {}), format_checker=FormatChecker(), registry=Registry(retrieve=deny_network))
        for error in validator.iter_errors(mapped):
            errors.append({'path': '/' + '/'.join(str(p).replace('~', '~0').replace('/', '~1') for p in error.absolute_path), 'message': error.message})
            if len(errors) >= 100:
                break
    except (ValueError, KeyError, TypeError) as error:
        errors.append({'path': '', 'message': str(error)})
    return errors


def validate_result(record, result, labels, done, structured, config):
    if not isinstance(result, dict) or set(result) - {'labelId', 'nodes', 'entities', 'relations', 'notes'}:
        raise ValueError('结果字段无效')
    label = result.get('labelId', '')
    if (label and label not in labels) or (done and label not in labels):
        raise ValueError('请选择规范中的整条记录标签')
    notes = result.get('notes', '')
    if not isinstance(notes, str) or len(notes) > 10000:
        raise ValueError('备注无效')
    for name in ('nodes', 'entities', 'relations'):
        if not isinstance(result.get(name, []), list) or len(result.get(name, [])) > 1000:
            raise ValueError('标注列表无效')
    node_keys = set()
    for mark in result.get('nodes', []):
        if not isinstance(mark, dict) or set(mark) != {'path', 'labelId'} or mark['labelId'] not in labels:
            raise ValueError('字段标签无效')
        pointer(record, mark['path'])
        key = (mark['path'], mark['labelId'])
        if key in node_keys:
            raise ValueError('重复字段标注')
        node_keys.add(key)
    entities = {}
    for entity in result.get('entities', []):
        if structured or not isinstance(entity, dict) or set(entity) != {'id', 'path', 'start', 'end', 'labelId'}:
            raise ValueError('实体字段无效')
        text = pointer(record, entity['path'])
        start, end = entity['start'], entity['end']
        # Offsets are UTF-16 code units to match the browser selection API.
        if not isinstance(text, str) or type(start) is not int or type(end) is not int or not 0 <= start < end <= len(text.encode('utf-16-le')) // 2:
            raise ValueError('实体范围超出文本')
        boundaries = {0}
        offset = 0
        for char in text:
            offset += len(char.encode('utf-16-le')) // 2
            boundaries.add(offset)
        if start not in boundaries or end not in boundaries:
            raise ValueError('实体边界截断字符')
        if not isinstance(entity['id'], str) or not entity['id'] or entity['id'] in entities or entity['labelId'] not in labels:
            raise ValueError('实体标识或标签无效')
        entities[entity['id']] = entity
    for relation in result.get('relations', []):
        if structured or not isinstance(relation, dict) or set(relation) != {'from', 'to', 'labelId'} or relation['from'] not in entities or relation['to'] not in entities or relation['from'] == relation['to'] or relation['labelId'] not in labels:
            raise ValueError('关系端点或标签无效')
    errors = issues(record, config)
    if done and errors and (config.get('blockOnErrors', False) or not notes.strip()):
        raise ValueError('规则校验未通过：请复核原数据并填写说明；严格模式禁止完成')
    return {'issues': errors}


def run(job, request):
    action = request['action']
    if action == 'recordsParse':
        errors, count, size, error_count = [], 0, 0, 0
        with (job / 'records.jsonl').open('w', encoding='utf-8') as output:
            for item in parse_file(job / 'source.input', request['sourceName'], request['structured']):
                if 'error' in item:
                    error_count += 1
                    if len(errors) < 1000:
                        errors.append(item)
                    continue
                count += 1
                if count > MAX_RECORDS:
                    raise ValueError('单次最多 10000 条记录')
                line = dumps(item) + '\n'
                size += len(line.encode('utf-8'))
                if size > MAX_BYTES:
                    raise ValueError('解析后数据超过 64 MiB')
                output.write(line)
        return {'count': count, 'errors': errors, 'errorCount': error_count}
    config = validate_config(request.get('config', {}))
    if action == 'recordsConfig':
        return {'valid': True}
    if action == 'recordsValidate':
        return validate_result(request['record'], request['result'], request['labels'], request['done'], request['structured'], config)
    if action == 'recordsIssues':
        return {'issues': issues(request['record'], config)}
    if action == 'recordsParquet':
        import pyarrow as pa
        import pyarrow.parquet as pq
        rows = load_json((job / 'rows.json').read_text(encoding='utf-8'))
        # Preserve ordinary column types; only heterogeneous columns use documented JSON encoding.
        arrays, fields = [], []
        for key in dict.fromkeys(key for row in rows for key in row):
            values = [row.get(key) for row in rows]
            metadata = None
            try:
                column = pa.array(values)
            except (pa.ArrowInvalid, pa.ArrowTypeError):
                column = pa.array([dumps(value) for value in values])
                metadata = {b'tss.encoding': b'json'}
            arrays.append(column)
            fields.append(pa.field(key, column.type, metadata=metadata))
        table = pa.Table.from_arrays(arrays, schema=pa.schema(fields))
        pq.write_table(table, job / 'records.parquet')
        return {'count': len(rows)}
    raise ValueError('未知记录操作')
