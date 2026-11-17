"""LS 1.23.2 projects/api.py template reader; framework paths/settings injected."""
import pathlib, logging, yaml, hashlib
logger=logging.getLogger(__name__)
ROOT=pathlib.Path(__file__).parent
class Settings:
    VERSION_EDITION='Community'
    HOSTNAME=''
settings=Settings()
class os:
    class path:
        @staticmethod
        def join(*parts): return '/'.join(parts)
def find_dir(name): return ROOT/name
def find_file(name): return ROOT/name
def read_yaml(path):
    with open(path,encoding='utf-8') as stream: return yaml.safe_load(stream)
def read_templates_and_groups():
    annotation_templates_dir = find_dir('annotation_templates')
    configs = []
    for config_file in pathlib.Path(annotation_templates_dir).glob('**/*.yml'):
        config = read_yaml(config_file)

        if settings.VERSION_EDITION != 'Community':
            if config.get('group', '').lower() == 'community contributions':
                continue

        if config.get('image', '').startswith('/static') and settings.HOSTNAME:
            # if hostname set manually, create full image urls
            config['image'] = settings.HOSTNAME + config['image']
        configs.append(config)
    template_groups_file = find_file(os.path.join('annotation_templates', 'groups.txt'))
    with open(template_groups_file, encoding='utf-8') as f:
        groups = f.read().splitlines()

    if settings.VERSION_EDITION != 'Community':
        groups = [group for group in groups if group.lower() != 'community contributions']

    logger.debug(f'{len(configs)} templates found.')
    return {'templates': configs, 'groups': groups}


def platform_catalog():
    catalog=read_templates_and_groups()
    sources={}
    for file in sorted((ROOT/'annotation_templates').rglob('*.yml')):
        raw=read_yaml(file);sources.setdefault((raw.get('title'),raw.get('group')),[]).append(file)
    for template in catalog['templates']:
        matches=sources.get((template.get('title'),template.get('group')),[])
        if len(matches)!=1:
            raise ValueError('AMBIGUOUS_TEMPLATE_SOURCE')
        file=matches[0]
        template['id']='ls:'+(file.parent.relative_to(ROOT/'annotation_templates') if file.stem=='config' else file.relative_to(ROOT/'annotation_templates').with_suffix('')).as_posix()
        template['sourceSha256']=hashlib.sha256(file.read_bytes()).hexdigest()
        template['version']='1.23.2:'+template['sourceSha256'][:16]
        template['image']=template.get('image','').replace('/static/templates/','/ls-template-assets/')
    catalog['sourceVersion']='1.23.2'
    catalog['sourceCommit']='df96d79183156e556d91825d15fa7721f1145e1a'
    return catalog
