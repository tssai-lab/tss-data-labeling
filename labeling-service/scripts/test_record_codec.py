import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'ls-source-adapter/record_codec.py'
spec = importlib.util.spec_from_file_location('record_codec', SOURCE)
codec = importlib.util.module_from_spec(spec)
spec.loader.exec_module(codec)


class RecordCodecTest(unittest.TestCase):
    def test_published_contract_schemas_are_valid_and_separate_table_from_documents(self):
        from jsonschema import Draft202012Validator
        folder = SOURCE.parents[1] / 'src/main/resources/schemas/record-data'
        for path in folder.glob('*.schema.json'):
            schema = json.loads(path.read_text(encoding='utf-8'))
            Draft202012Validator.check_schema(schema)
            if path.name.endswith('result-v1.schema.json'):
                validator = Draft202012Validator(schema)
                validator.validate({'labelId': 'ok', 'nodes': [{'path': '/id', 'labelId': 'ok'}]})
                self.assertFalse(validator.is_valid({'unexpected': True}))
        self.assertEqual(4, len(list(folder.glob('*.schema.json'))))

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.root = Path(self.folder.name)
        self.addCleanup(self.folder.cleanup)

    def parse(self, name, text, structured=False):
        path = self.root / 'source.input'
        path.write_bytes(text if isinstance(text, bytes) else text.encode('utf-8'))
        return list(codec.parse_file(path, name, structured))

    def test_csv_preserves_keys_values_and_reports_bad_row(self):
        rows = self.parse('data.csv', 'id,label,a/b\n001,原标签,"两,列"\n2,短行\n', True)
        self.assertEqual({'id': '001', 'label': '原标签', 'a/b': '两,列'}, rows[0]['record'])
        self.assertEqual(3, rows[1]['row'])
        self.assertIn('error', rows[1])
        with self.assertRaises(ValueError):
            self.parse('duplicate.csv', 'id,id\na,b\n', True)

    def test_xlsx_all_sheets_static_values_only(self):
        from openpyxl import Workbook
        book = Workbook()
        book.active.append(['id', 'amount'])
        book.active.append(['001', 0])
        book.active.append(['002', '=1+1'])
        sheet = book.create_sheet('第二张表')
        sheet.append(['id', 'amount'])
        sheet.append(['003', -2])
        path = self.root / 'data.xlsx'
        book.save(path)
        rows = self.parse('data.xlsx', path.read_bytes(), True)
        self.assertEqual(0, rows[0]['record']['amount'])
        self.assertIn('error', rows[1])
        self.assertEqual('第二张表', rows[2]['sheet'])

    def test_parquet_roundtrip_preserves_null_zero_and_key(self):
        import pyarrow as pa
        import pyarrow.parquet as pq
        path = self.root / 'data.parquet'
        pq.write_table(pa.Table.from_pylist([{'id': '001', 'amount': 0}, {'id': '002', 'amount': None}]), path)
        rows = self.parse('data.parquet', path.read_bytes(), True)
        self.assertEqual([{'id': '001', 'amount': 0}, {'id': '002', 'amount': None}], [r['record'] for r in rows])
        (self.root / 'rows.json').write_text(codec.dumps([r['record'] for r in rows]), encoding='utf-8')
        codec.run(self.root, {'action': 'recordsParquet'})
        self.assertEqual([r['record'] for r in rows], pq.read_table(self.root / 'records.parquet').to_pylist())

    def test_json_jsonl_preserve_raw_and_report_line(self):
        text = '{ "a/b": "中文😀", "~": null }'
        row = self.parse('data.json', text)[0]
        self.assertEqual(text, row['raw'])
        self.assertEqual('中文😀', codec.pointer(row['record'], '/a~1b'))
        rows = self.parse('data.jsonl', text + '\nnot-json\n{"id":2}\n')
        self.assertEqual(2, rows[1]['row'])
        self.assertIn('error', rows[1])
        self.assertEqual(3, rows[2]['row'])
        with self.assertRaises(ValueError):
            self.parse('data.json', '{"id":1,"id":2}')

    def test_xml_yaml_preserve_document_and_reject_executable_inputs(self):
        xml = '<record id="1">甲<name>乙</name>丙</record>'
        row = self.parse('data.xml', xml)[0]
        self.assertEqual(xml, row['raw'])
        self.assertEqual('丙', row['record']['children'][0]['tail'])
        for name, text in [('data.xml', '<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><x>&e;</x>'), ('data.yaml', 'a: &a [*a]'), ('data.yaml', '!!python/object/apply:os.system [echo nope]'), ('data.yaml', 'id: 1\nid: 2')]:
            with self.subTest(name=name, text=text), self.assertRaises(Exception):
                self.parse(name, text)
        self.assertEqual('中文', self.parse('data.yaml', 'id: "001"\nname: 中文\n')[0]['record']['name'])

    def test_schema_and_mapping_are_checked_without_network(self):
        config = {'schema': {'type': 'object', 'required': ['name'], 'properties': {'name': {'type': 'string', 'minLength': 2}}}, 'fieldMapping': {'name': '/客户/名称'}}
        codec.validate_config(config)
        self.assertEqual([], codec.issues({'客户': {'名称': '张三'}}, config))
        self.assertTrue(codec.issues({'客户': {'名称': ''}}, config))
        for schema in [{'type': 'unknown'}, {'$ref': 'https://example.invalid/schema.json'}]:
            with self.assertRaises(Exception):
                codec.validate_config({'schema': schema})

    def test_result_rejects_foreign_labels_paths_entities_and_relations(self):
        record = {'text': '甲😀乙'}
        valid = {'labelId': 'ok', 'nodes': [{'path': '/text', 'labelId': 'ok'}], 'entities': [{'id': 'a', 'path': '/text', 'start': 1, 'end': 3, 'labelId': 'ok'}, {'id': 'b', 'path': '/text', 'start': 3, 'end': 4, 'labelId': 'ok'}], 'relations': [{'from': 'a', 'to': 'b', 'labelId': 'ok'}]}
        self.assertEqual([], codec.validate_result(record, valid, ['ok'], True, False, {})['issues'])
        for invalid in [{'labelId': 'foreign'}, {'labelId': 'ok', 'nodes': [{'path': '/absent', 'labelId': 'ok'}]}, {'labelId': 'ok', 'entities': [{'id': 'a', 'path': '/text', 'start': 1, 'end': 2, 'labelId': 'ok'}]}, {'labelId': 'ok', 'relations': [{'from': 'a', 'to': 'absent', 'labelId': 'ok'}]}]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                codec.validate_result(record, invalid, ['ok'], True, False, {})
        with self.assertRaises(ValueError):
            codec.validate_result(record, valid, ['ok'], True, True, {})

    def test_completion_and_rule_disposition(self):
        codec.validate_result({}, {}, ['ok'], False, True, {})
        with self.assertRaises(ValueError):
            codec.validate_result({}, {}, ['ok'], True, True, {})
        config = {'schema': {'required': ['missing']}}
        with self.assertRaises(ValueError):
            codec.validate_result({}, {'labelId': 'ok'}, ['ok'], True, True, config)
        codec.validate_result({}, {'labelId': 'ok', 'notes': '源文件缺字段，已复核'}, ['ok'], True, True, config)
        with self.assertRaises(ValueError):
            codec.validate_result({}, {'labelId': 'ok', 'notes': '已说明'}, ['ok'], True, True, {**config, 'blockOnErrors': True})


if __name__ == '__main__':
    unittest.main()
