"""Resources supplied by the platform, scoped to one offline export job."""
from pathlib import Path
import shutil

_resources = {}
_output = None


def configure(job_root, resources):
    global _resources, _output
    root = Path(job_root).resolve(strict=True)
    _output = root / 'output'
    _resources = {}
    for uri, relative_path in resources.items():
        path = (root / 'resources' / relative_path).resolve(strict=True)
        if root / 'resources' not in path.parents or not path.is_file():
            raise ValueError('INVALID_RESOURCE_PATH')
        _resources[uri] = path


def resolve_resource(uri, output_dir=None, filename=None):
    if uri not in _resources:
        raise ValueError('RESOURCE_NOT_PROVIDED')
    source = _resources[uri]
    if output_dir is None:
        return str(source)
    target_dir = Path(output_dir).resolve()
    if target_dir != _output and _output not in target_dir.parents:
        raise ValueError('INVALID_RESOURCE_OUTPUT')
    target_dir.mkdir(parents=True, exist_ok=True)
    target = (target_dir / (filename or source.name)).resolve()
    if target.parent != target_dir:
        raise ValueError('INVALID_RESOURCE_FILENAME')
    if target != source:
        shutil.copyfile(source, target)
    return str(target)
