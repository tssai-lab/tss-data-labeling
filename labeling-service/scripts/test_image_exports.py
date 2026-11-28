import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image

ADAPTER_ROOT = Path(__file__).resolve().parents[1] / 'ls-source-adapter'
sys.path.insert(0, str(ADAPTER_ROOT))
spec = importlib.util.spec_from_file_location('image_export_adapter', ADAPTER_ROOT / 'adapter.py')
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)


class ImageExportTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.root = Path(self.folder.name)
        self.addCleanup(self.folder.cleanup)
        (self.root / 'resources').mkdir()
        Image.new('RGB', (5, 4), (20, 40, 60)).save(self.root / 'resources/input.png')

    def export(self, fmt, xml, results, prepared=False):
        task = {'id': 'sample-1', 'data': {'nested': {'image': 'resources/input.png'}},
                'meta': {'sourcePath': 'scan.tiff#page-2', 'pageIndex': 1},
                'annotations': [{'id': 'reviewed-1', 'completed_by': 'owner', 'result': results}]}
        if prepared:
            (self.root / 'tasks').mkdir()
            (self.root / 'tasks/0000.json').write_text(json.dumps(task), encoding='utf-8')
        else:
            (self.root / 'tasks.json').write_text(json.dumps([task]), encoding='utf-8')
        return adapter.export(self.root, {'labelConfig': xml, 'format': fmt, 'tasksPrepared': prepared,
                                         'resources': {'resources/input.png': 'input.png'}})

    @staticmethod
    def xml(kind='RectangleLabels', label='设备', alias='machine'):
        return f'<View><Image name="image" value="$nested.image"/><{kind} name="labels" toName="image"><Label value="{label}" alias="{alias}"/></{kind}></View>'

    @staticmethod
    def box(rotation=0):
        return {'id': 'box', 'from_name': 'labels', 'to_name': 'image', 'type': 'rectanglelabels',
                'original_width': 5, 'original_height': 4,
                'value': {'x': 40, 'y': 25, 'width': 40, 'height': 50, 'rotation': rotation, 'rectanglelabels': ['machine']}}

    def test_voc_contains_real_image_consistent_coordinates_alias_and_page_provenance(self):
        response = self.export('VOC', self.xml(), [self.box()], prepared=True)
        self.assertEqual(1, response['taskCount'])
        xml = ET.parse(next((self.root / 'output/Annotations').glob('*.xml'))).getroot()
        self.assertEqual('machine', xml.findtext('object/name'))
        self.assertEqual(['2', '1', '4', '3'], [xml.findtext('object/bndbox/' + key) for key in ['xmin', 'ymin', 'xmax', 'ymax']])
        with Image.open(self.root / 'output/images' / xml.findtext('filename')) as image:
            self.assertEqual((5, 4), image.size)
        manifest = json.loads((self.root / 'output/annotation-index.json').read_text(encoding='utf-8'))
        self.assertEqual([{'id': 0, 'name': 'machine'}], manifest['labels'])
        self.assertEqual('scan.tiff#page-2', manifest['items'][0]['source']['sourcePath'])

    def test_voc_rotation_uses_enclosing_box_and_escapes_label_text(self):
        xml_config = self.xml(label='A&amp;B', alias='safe')
        box = self.box(90); box['value']['rectanglelabels'] = ['safe']
        self.export('VOC', xml_config, [box])
        xml = ET.parse(next((self.root / 'output/Annotations').glob('*.xml'))).getroot()
        self.assertEqual(['0', '1', '2', '3'], [xml.findtext('object/bndbox/' + key) for key in ['xmin', 'ymin', 'xmax', 'ymax']])

    def test_mask_png_pixels_labels_and_dimensions_are_trainable(self):
        from label_studio_sdk.converter.brush import mask2rle
        mask = np.array([[0, 0, 255, 0, 0], [0, 255, 255, 0, 0], [0, 0, 0, 0, 0], [255, 0, 0, 0, 255]], dtype=np.uint8)
        region = {'id': '../region', 'from_name': 'labels', 'to_name': 'image', 'type': 'brushlabels',
                  'original_width': 5, 'original_height': 4, 'value': {'format': 'rle', 'rle': mask2rle(mask), 'brushlabels': ['machine']}}
        self.export('BRUSH_TO_PNG', self.xml('BrushLabels'), [region])
        manifest = json.loads((self.root / 'output/annotation-index.json').read_text(encoding='utf-8'))
        self.assertEqual({'background': 0, 'foreground': 255}, manifest['maskValues'])
        self.assertEqual(['machine'], manifest['items'][0]['labels'])
        with Image.open(self.root / 'output' / manifest['items'][0]['mask']) as png:
            self.assertEqual((5, 4), png.size)
            np.testing.assert_array_equal(mask, np.asarray(png))

    def test_mask_invalid_header_is_rejected_before_large_allocation(self):
        region = {'id': 'bad', 'from_name': 'labels', 'to_name': 'image', 'type': 'brushlabels',
                  'original_width': 5, 'original_height': 4,
                  'value': {'format': 'rle', 'rle': [255] * 20, 'brushlabels': ['machine']}}
        with self.assertRaisesRegex(ValueError, 'MASK_SIZE_MISMATCH'):
            self.export('BRUSH_TO_PNG', self.xml('BrushLabels'), [region])

    def test_resources_are_explicit_and_dimension_mismatch_is_rejected(self):
        box = self.box(); box['original_width'] = 9
        with self.assertRaisesRegex(ValueError, 'IMAGE_SIZE_MISMATCH'):
            self.export('VOC', self.xml(), [box])

    def test_export_capabilities_follow_control_type(self):
        rect = {item['name']: item['enabled'] for item in adapter.inspect_config(self.xml())['formats']}
        brush = {item['name']: item['enabled'] for item in adapter.inspect_config(self.xml('BrushLabels'))['formats']}
        self.assertTrue(rect['VOC']); self.assertFalse(rect['BRUSH_TO_PNG'])
        self.assertTrue(brush['BRUSH_TO_PNG']); self.assertFalse(brush['VOC'])


if __name__ == '__main__':
    unittest.main()
