"""VOC and binary PNG masks from frozen platform tasks and explicit local resources."""
import hashlib
import json
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from PIL import Image
import numpy as np
import platform_resources
from platform_result import at, validate_mask

SAFE_ID = re.compile(r'[A-Za-z0-9_-]{1,128}\Z')


def _bindings(config):
    images = {}
    for control in config.values():
        for name, source in zip(control.get('to_name', []), control.get('inputs', [])):
            if source['type'] == 'Image':
                images[name] = source['value']
    return images


def _labels(region, results, control):
    value = region.get('value', {})
    labels = value.get('rectanglelabels', value.get('brushlabels', value.get('labels', [])))
    if not labels:
        for related in results:
            if related.get('id') == region.get('id'):
                labels = related.get('value', {}).get('labels', [])
                if labels:
                    break
    aliases = {attrs.get('value', name): name for name, attrs in control.get('labels_attrs', {}).items()}
    return [aliases.get(label, label) for label in labels]


def _box(value, width, height):
    numbers = [value.get(key, 0) for key in ('x', 'y', 'width', 'height', 'rotation')]
    if any(isinstance(n, bool) or not isinstance(n, (int, float)) or not math.isfinite(n) for n in numbers):
        raise ValueError('INVALID_VOC_COORDINATES')
    x, y, w, h, rotation = numbers
    if w <= 0 or h <= 0:
        raise ValueError('INVALID_VOC_COORDINATES')
    x, y, w, h = x * width / 100, y * height / 100, w * width / 100, h * height / 100
    angle = math.radians(rotation)
    points = [(x + dx * math.cos(angle) - dy * math.sin(angle),
               y + dx * math.sin(angle) + dy * math.cos(angle)) for dx, dy in ((0, 0), (w, 0), (w, h), (0, h))]
    # Zero-based pixel-edge coordinates, clipped to the image. VOC loses rotation.
    left = max(0, math.floor(min(p[0] for p in points) + 1e-8))
    top = max(0, math.floor(min(p[1] for p in points) + 1e-8))
    right = min(width, math.ceil(max(p[0] for p in points) - 1e-8))
    bottom = min(height, math.ceil(max(p[1] for p in points) - 1e-8))
    if right <= left or bottom <= top:
        raise ValueError('VOC_BOX_OUTSIDE_IMAGE')
    return left, top, right, bottom


def _text(parent, name, value):
    ET.SubElement(parent, name).text = str(value)


def export(task_dir, output, config, fmt):
    bindings = _bindings(config)
    index = []
    images_index = []
    declared = []
    aliases = {}
    for control in config.values():
        aliases.update({attrs.get('value', name): name for name, attrs in control.get('labels_attrs', {}).items()})
        if control.get('type', '').lower() in {'rectanglelabels', 'brushlabels', 'labels'}:
            for label in control.get('labels', []):
                if label not in declared:
                    declared.append(label)
    for path in sorted(Path(task_dir).glob('*.json')):
        task = json.loads(path.read_text(encoding='utf-8'))
        task_id = str(task['id'])
        if not SAFE_ID.fullmatch(task_id):
            raise ValueError('INVALID_TASK_ID')
        for image_name, field in bindings.items():
            uri = at(task['data'], field)
            if not isinstance(uri, str):
                raise ValueError('IMAGE_RESOURCE_REFERENCE_REQUIRED')
            source = platform_resources.resolve_resource(uri)
            stem = task_id + '-' + hashlib.sha256(image_name.encode()).hexdigest()[:8]
            image_path = 'images/' + stem + '.png'
            (output / 'images').mkdir(exist_ok=True)
            with Image.open(source) as image:
                width, height = image.size
                if width <= 0 or height <= 0 or width * height > 40000000:
                    raise ValueError('EXPORT_IMAGE_TOO_LARGE')
                image.convert('RGBA').save(output / image_path)
            images_index.append({'sampleId': task_id, 'image': image_path, 'imageField': field,
                                 'width': width, 'height': height, 'source': task.get('meta', {})})
            if fmt == 'VOC':
                root = ET.Element('annotation')
                _text(root, 'folder', 'images'); _text(root, 'filename', Path(image_path).name)
                size = ET.SubElement(root, 'size')
                for key, value in [('width', width), ('height', height), ('depth', 4)]:
                    _text(size, key, value)
                _text(root, 'segmented', 0)
            for annotation_index, annotation in enumerate(task.get('annotations', [])):
                results = annotation.get('result', [])
                for region_index, region in enumerate(results):
                    if region.get('to_name') != image_name:
                        continue
                    kind = region.get('type', '').lower()
                    if kind not in ({'rectangle', 'rectanglelabels'} if fmt == 'VOC' else {'brush', 'brushlabels'}):
                        continue
                    if region.get('original_width') != width or region.get('original_height') != height:
                        raise ValueError('IMAGE_SIZE_MISMATCH')
                    control = config.get(region.get('from_name'), {})
                    labels = [label if label in declared else aliases.get(label, label)
                              for label in _labels(region, results, control)]
                    if not labels or any(label not in declared for label in labels):
                        raise ValueError('EXPORT_LABEL_NOT_DECLARED')
                    item = {'sampleId': task_id, 'image': image_path, 'imageField': field,
                            'annotationId': annotation.get('id'), 'regionId': region.get('id'),
                            'labels': labels, 'width': width, 'height': height, 'source': task.get('meta', {})}
                    if fmt == 'VOC':
                        box = _box(region['value'], width, height)
                        for label in labels:
                            obj = ET.SubElement(root, 'object'); _text(obj, 'name', label)
                            _text(obj, 'pose', 'Unspecified'); _text(obj, 'truncated', 0); _text(obj, 'difficult', 0)
                            bounds = ET.SubElement(obj, 'bndbox')
                            for key, value in zip(('xmin', 'ymin', 'xmax', 'ymax'), box):
                                _text(bounds, key, value)
                        item['annotation'] = 'Annotations/' + stem + '.xml'
                    else:
                        rle = region.get('value', {}).get('rle')
                        validate_mask(rle, {'width': width, 'height': height})
                        from label_studio_sdk.converter.brush import decode_rle
                        rgba = decode_rle(rle).reshape((height, width, 4))
                        mask = np.where(rgba[:, :, 3] > 0, 255, 0).astype(np.uint8)
                        mask_path = f'masks/{stem}-{annotation_index}-{region_index}.png'
                        (output / 'masks').mkdir(exist_ok=True)
                        Image.fromarray(mask).save(output / mask_path)
                        item['mask'] = mask_path
                    index.append(item)
            if fmt == 'VOC':
                (output / 'Annotations').mkdir(exist_ok=True)
                ET.ElementTree(root).write(output / 'Annotations' / (stem + '.xml'), encoding='utf-8', xml_declaration=True)
    manifest = {'format': fmt, 'labels': [{'id': i, 'name': name} for i, name in enumerate(declared)],
                'coordinateSystem': 'zero-based pixel edges; rotated rectangles use the enclosing axis-aligned box',
                'maskValues': {'background': 0, 'foreground': 255}, 'images': images_index, 'items': index}
    (output / 'annotation-index.json').write_text(json.dumps(manifest, ensure_ascii=False, allow_nan=False), encoding='utf-8')
