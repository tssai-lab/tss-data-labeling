import csv
import io
import json
from pathlib import Path
import tempfile
import unittest
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import adapter
import platform_resources

TS = '''<View><TimeSeries name="curve" value="$measurements" valueType="json" timeColumn="t">
<Channel column="temperature"/><Channel column="pressure"/></TimeSeries>
<TimeSeriesLabels name="events" toName="curve"><Label value="异常"/></TimeSeriesLabels>
<Choices name="status" toName="curve"><Choice value="正常"/><Choice value="异常"/></Choices></View>'''
IMAGE = '''<View><Image name="photo" value="$picture"/><RectangleLabels name="boxes" toName="photo">
<Label value="设备"/></RectangleLabels></View>'''


def ts_task():
    return {'id': 'sample-42', 'data': {'measurements': [
        {'t': 0.25, 'temperature': -2, 'pressure': None},
        {'t': 1.7, 'temperature': 0, 'pressure': 3}], 'station': '站点甲'},
        'meta': {'resultVersion': 3}, 'annotations': [{'id': 7, 'completed_by': 2, 'result': [
            {'id': 'r1', 'from_name': 'events', 'to_name': 'curve', 'type': 'timeserieslabels',
             'value': {'start': 0.25, 'end': 1.7, 'timeserieslabels': ['异常']}},
            {'id': 'r2', 'from_name': 'status', 'to_name': 'curve', 'type': 'choices',
             'value': {'choices': ['异常']}}]}]}


class AdapterTest(unittest.TestCase):
    def test_backend_schema_validation_preserves_validation_error_type_and_message(self):
        from ls_backend_config import validate_label_config
        with self.assertRaisesRegex(ValueError, 'Validation failed'):
            validate_label_config('<View><Image/></View>')

    def test_backend_preview_generates_native_json_and_csv_for_configured_columns(self):
        preview = adapter.sample_config(TS)
        self.assertTrue(preview['isExample'])
        self.assertEqual({'t', 'temperature', 'pressure'}, set(preview['sample_task']['measurements']))
        self.assertEqual(100, len(preview['sample_task']['measurements']['t']))
        csv_preview = adapter.sample_config(TS.replace('valueType="json"', 'valueType="url"'))
        rows = list(csv.DictReader(io.StringIO(csv_preview['resources'][0]['content'])))
        self.assertEqual(100, len(rows))
        self.assertEqual({'t', 'temperature', 'pressure'}, set(rows[0]))

    def test_backend_preview_uses_local_images_and_epoch_or_formatted_times(self):
        image = adapter.sample_config(IMAGE)
        self.assertEqual('/api/labeling/v1/label-configs/preview-image', image['resources'][0]['url'])
        for fmt in ['%Q', '%Y-%m-%d %H:%M:%S.%L']:
            preview = adapter.sample_config(TS.replace('timeColumn="t"', f'timeColumn="t" timeFormat="{fmt}"'))
            times = preview['sample_task']['measurements']['t']
            self.assertEqual(100, len(times))
            self.assertTrue(all(a < b for a, b in zip(times, times[1:])))
            if fmt == '%Q': self.assertIsInstance(times[0], int)
            else: self.assertEqual('2020-01-01 00:00:00.000', times[0])

    def export(self, xml, fmt, tasks):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'tasks.json').write_text(json.dumps(tasks, ensure_ascii=False), encoding='utf-8')
            result = adapter.export(root, {'labelConfig': xml, 'format': fmt})
            return result, {f['path']: (root / 'output' / f['path']).read_bytes() for f in result['files']}

    def test_native_time_series_bindings_and_formats(self):
        data = adapter.inspect_config(TS)
        self.assertEqual(['curve'], data['parsedConfig']['events']['to_name'])
        self.assertEqual('measurements', data['parsedConfig']['events']['inputs'][0]['value'])
        enabled = {f['name'] for f in data['formats'] if f['enabled']}
        self.assertEqual({'JSON', 'JSON_MIN', 'CSV', 'TSV'}, enabled)

    def test_json_preserves_task_data_and_raw_results(self):
        task = ts_task()
        _, files = self.export(TS, 'JSON', [task])
        self.assertEqual([task], json.loads(files['result.json']))

    def test_min_json_preserves_intervals_and_choices(self):
        _, files = self.export(TS, 'JSON_MIN', [ts_task()])
        record = json.loads(files['result.json'])[0]
        self.assertEqual('异常', record['status'])  # Native JSON_MIN flattens a single choice.
        self.assertEqual(0.25, record['events'][0]['start'])
        self.assertEqual(-2, record['measurements'][0]['temperature'])

    def test_csv_and_tsv_include_real_control_results(self):
        for fmt, separator in [('CSV', ','), ('TSV', '\t')]:
            with self.subTest(fmt=fmt):
                _, files = self.export(TS, fmt, [ts_task()])
                rows = list(csv.DictReader(io.StringIO(next(iter(files.values())).decode('utf-8-sig')), delimiter=separator))
                self.assertEqual('站点甲', rows[0]['station'])
                self.assertIn('异常', rows[0]['status'])
                self.assertIn('0.25', rows[0]['events'])

    def test_csv_resource_config_is_not_an_energy_schema(self):
        xml = TS.replace('valueType="json"', 'valueType="url"')
        task = ts_task()
        task['data']['measurements'] = '/api/labeling/v1/samples/sample-42/file'
        _, files = self.export(xml, 'JSON', [task])
        self.assertEqual(task['data'], json.loads(files['result.json'])[0]['data'])

    def test_image_detection_coco_and_yolo(self):
        task = {'id': 42, 'data': {'picture': 'platform://sample-42.png'}, 'annotations': [
            {'id': 1, 'completed_by': 2, 'result': [{'id': 'box1', 'from_name': 'boxes',
             'to_name': 'photo', 'type': 'rectanglelabels', 'original_width': 200, 'original_height': 100,
             'value': {'x': 10, 'y': 20, 'width': 30, 'height': 40, 'rotation': 0, 'rectanglelabels': ['设备']}}]}]}
        _, files = self.export(IMAGE, 'COCO', [task])
        coco = json.loads(files['result.json'])
        self.assertEqual([20, 20, 60, 40], coco['annotations'][0]['bbox'])
        self.assertEqual('设备', coco['categories'][0]['name'])
        self.assertEqual('platform://sample-42.png', coco['images'][0]['file_name'])
        _, files = self.export(IMAGE, 'YOLO', [task])
        label = next(content for name, content in files.items() if name.startswith('labels/'))
        self.assertEqual('0 0.25 0.4 0.3 0.4', label.decode().strip())
        other = json.loads(json.dumps(task))
        other['id'] = 43
        other['data']['picture'] = 'platform://other-directory/sample-42.png'
        _, files = self.export(IMAGE, 'YOLO', [task, other])
        self.assertTrue({'labels/42.txt', 'labels/43.txt'}.issubset(files))

    def test_coco_uses_only_manifest_resource_and_keeps_public_reference(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'resources').mkdir()
            Image.new('RGB', (17, 13)).save(root / 'resources' / 'image.png')
            uri = '/api/labeling/v1/samples/42/file'
            (root / 'tasks.json').write_text(json.dumps([
                {'id': 42, 'data': {'picture': uri}, 'annotations': []}]), encoding='utf-8')
            adapter.export(root, {'labelConfig': IMAGE, 'format': 'COCO', 'resources': {uri: 'image.png'}})
            exported = json.loads((root / 'output' / 'result.json').read_text(encoding='utf-8'))
            self.assertEqual({'width': 17, 'height': 13, 'id': 0, 'file_name': uri}, exported['images'][0])

    def test_unsupported_format_fails(self):
        with self.assertRaisesRegex(ValueError, 'EXPORT_FORMAT_NOT_SUPPORTED'):
            self.export(TS, 'COCO', [ts_task()])

    def test_native_formatted_and_epoch_time_results_keep_their_format(self):
        results=ts_task()['annotations'][0]['result']
        for fmt,start,end in [('%Q','1793491200000','1793494800000'),
                              ('%Y-%m-%d %H:%M:%S','2026-11-01 00:00:00','2026-11-01 01:00:00')]:
            xml=TS.replace('timeColumn="t"',f'timeColumn="t" timeFormat="{fmt}"')
            results[0]['value']['start']=start;results[0]['value']['end']=end
            self.assertTrue(adapter.validate_times(xml,results)['valid'])
            results[0]['value']['start']=end;results[0]['value']['end']=start
            with self.assertRaises(ValueError):adapter.validate_times(xml,results)

    def test_unknown_xml_tag_is_rejected_by_vendored_backend_schema(self):
        with self.assertRaises(ValueError):adapter.inspect_config('<View><Unknown name="x" value="$series"/></View>')

    def test_unsafe_or_ambiguous_xml_rejected(self):
        for xml in ['<!DOCTYPE View [<!ENTITY x SYSTEM "file:///secret">]><View>&x;</View>',
                    '<Other/>', '<View><Image name="x"/><Image name="x"/></View>',
                    '<View><Choices name="x" toName="missing"/></View>']:
            with self.subTest(xml=xml), self.assertRaises(ValueError):
                adapter.inspect_config(xml)

    def test_resource_manifest_cannot_escape_job(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'resources').mkdir()
            (root / 'secret').write_text('private')
            with self.assertRaisesRegex(ValueError, 'INVALID_RESOURCE_PATH'):
                platform_resources.configure(root, {'platform://test': '../secret'})
            platform_resources.configure(root, {})
            with self.assertRaisesRegex(ValueError, 'RESOURCE_NOT_PROVIDED'):
                platform_resources.resolve_resource('http://127.0.0.1/secret')

    def test_reject_path_like_task_id(self):
        task = ts_task()
        task['id'] = '../escape'
        with self.assertRaisesRegex(ValueError, 'INVALID_TASK_ID'):
            self.export(TS, 'JSON', [task])


if __name__ == '__main__':
    unittest.main()
