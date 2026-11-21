"""LS 1.23.2 core/label_config.py: configuration functions, Apache-2.0.
Changes: framework exception -> ValueError; pinned local schema; no Django settings.
"""
import json, re
from collections import OrderedDict
from pathlib import Path
from typing import Union, Tuple
import defusedxml.ElementTree as etree
import jsonschema
import xmljson
from label_studio_sdk.label_interface import LabelInterface
from label_studio_sdk._legacy.exceptions import LabelStudioValidationErrorSentryIgnored
_LABEL_CONFIG_SCHEMA_DATA=json.loads((Path(__file__).parent/'label_config_schema.json').read_text(encoding='utf-8'))
def _fix_choices(config):
    """
    workaround for single choice
    https://github.com/HumanSignal/label-studio/issues/1259
    """
    if 'Choices' in config:
        # for single Choices tag in View
        if 'Choice' in config['Choices'] and not isinstance(config['Choices']['Choice'], list):
            config['Choices']['Choice'] = [config['Choices']['Choice']]
        # for several Choices tags in View
        elif isinstance(config['Choices'], list) and all('Choice' in tag_choices for tag_choices in config['Choices']):
            for n in range(len(config['Choices'])):
                # check that Choices tag has only 1 choice
                if not isinstance(config['Choices'][n]['Choice'], list):
                    config['Choices'][n]['Choice'] = [config['Choices'][n]['Choice']]
    if 'View' in config:
        if isinstance(config['View'], OrderedDict):
            config['View'] = _fix_choices(config['View'])
        else:
            config['View'] = [_fix_choices(view) for view in config['View']]
    return config


def parse_config_to_xml(config_string: Union[str, None], raise_on_empty: bool = False) -> Union[OrderedDict, None]:
    if config_string is None:
        if raise_on_empty:
            raise TypeError('config_string is None')
        return None

    xml = etree.fromstring(config_string, forbid_dtd=True)

    # Remove comments
    for comment in xml.findall('.//comment'):
        comment.getparent().remove(comment)

    return xml


def parse_config_to_json(config_string: Union[str, None]) -> Tuple[Union[OrderedDict, None], Union[str, None]]:
    try:
        xml = parse_config_to_xml(config_string, raise_on_empty=True)
    except TypeError:
        raise etree.ParseError('can only parse strings')
    if xml is None:
        raise etree.ParseError('xml is empty or incorrect')
    config = xmljson.badgerfish.data(xml)
    config = _fix_choices(config)
    return config, etree.tostring(xml, encoding='unicode')


def validate_label_config(config_string: Union[str, None]) -> None:
    # xml and schema
    try:
        config, cleaned_config_string = parse_config_to_json(config_string)
        jsonschema.validate(config, _LABEL_CONFIG_SCHEMA_DATA)
    except (etree.ParseError, ValueError) as exc:
        raise ValueError(str(exc))
    except jsonschema.exceptions.ValidationError as exc:
        # jsonschema4 validation error now includes all errors from "anyOf" subschemas
        # check https://python-jsonschema.readthedocs.io/en/latest/errors/#jsonschema.exceptions.ValidationError.context
        # we pick the first failed schema and show only its error message
        error_message = exc.context[0].message if len(exc.context) else exc.message
        error_message = 'Validation failed on {}: {}'.format(
            '/'.join(map(str, exc.path)), error_message.replace('@', '')
        )
        raise ValueError(error_message)

    # unique names in config # FIXME: 'name =' (with spaces) won't work
    all_names = re.findall(r'name="([^"]*)"', cleaned_config_string)
    if len(set(all_names)) != len(all_names):
        raise ValueError('Label config contains non-unique names')

    # toName points to existent name
    names = set(all_names)
    toNames = re.findall(r'toName="([^"]*)"', cleaned_config_string)
    for toName_ in toNames:
        for toName in toName_.split(','):
            if toName not in names:
                raise ValueError(f'toName="{toName}" not found in names: {sorted(names)}')

    # Tag attribute validation (e.g. Video playback speed) via SDK
    try:
        li = LabelInterface(config_string)
        if hasattr(li, '_tag_attribute_validation'):
            li._tag_attribute_validation()
    except LabelStudioValidationErrorSentryIgnored as exc:
        raise ValueError(str(exc))

