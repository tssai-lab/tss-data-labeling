"""LS 1.23.2 sample generation, Apache-2.0; local examples/resources adapter."""
import json, logging, csv, io
from pathlib import Path
from urllib.parse import urlencode
import numpy as np
import pandas as pd
from ls_backend_config import parse_config_to_xml
logger=logging.getLogger(__name__)
_DATA_EXAMPLES=None
class Settings:
    HOSTNAME=''
settings=Settings()
def find_file(name):
    return str(Path(__file__).parent/name)
def get_task_from_labeling_config(config):
    """Get task, annotations and predictions from labeling config comment,
    it must start from "<!-- {" and end as "} -->"
    """
    # try to get task data, annotations & predictions from config comment
    task_data, annotations, predictions = {}, None, None
    start = config.find('<!-- {')
    start = start if start >= 0 else config.find('<!--{')
    start += 4
    end = config[start:].find('-->') if start >= 0 else -1
    if 3 < start < start + end:
        try:
            logger.debug('Parse ' + config[start : start + end])
            body = json.loads(config[start : start + end])
        except Exception:
            logger.error("Can't parse task from labeling config", exc_info=True)
            pass
        else:
            logger.debug(json.dumps(body, indent=2))
            dont_use_root = 'predictions' in body or 'annotations' in body
            task_data = body['data'] if 'data' in body else (None if dont_use_root else body)
            predictions = body['predictions'] if 'predictions' in body else None
            annotations = body['annotations'] if 'annotations' in body else None
    return task_data, annotations, predictions


def data_examples(mode):
    """Data examples for editor preview and task upload examples"""
    global _DATA_EXAMPLES

    if _DATA_EXAMPLES is None:
        with open(find_file('data_examples.json'), encoding='utf-8') as f:
            _DATA_EXAMPLES = json.load(f)

        roots = ['editor_preview', 'upload']
        for root in roots:
            for key, value in _DATA_EXAMPLES[root].items():
                if isinstance(value, str):
                    _DATA_EXAMPLES[root][key] = value.replace('<HOSTNAME>', settings.HOSTNAME)

    return _DATA_EXAMPLES[mode]


def generate_sample_task_without_check(label_config, mode='upload', secure_mode=False):
    """Generate sample task only"""
    # load config
    xml = parse_config_to_xml(label_config)
    if xml is None:
        raise etree.ParseError('Project config is empty or incorrect')

    # make examples pretty
    examples = data_examples(mode=mode)

    # iterate over xml tree and find elements with 'value' or 'valueList' attributes
    task = {}
    # Include both 'value' and 'valueList' attributes in the search
    parent = xml.findall('.//*[@value]') + xml.findall('.//*[@valueList]')
    for p in parent:
        # Extract data placeholder key
        value = p.get('value') or p.get('valueList')
        if not value or not value.startswith('$'):
            continue
        value = value[1:]
        is_value_list = 'valueList' in p.attrib  # Check if the attribute is 'valueList'

        # detect secured mode - objects served as URLs
        value_type = p.get('valueType') or p.get('valuetype')

        only_urls = value_type == 'url'
        if secure_mode and p.tag in ['Paragraphs', 'HyperText', 'Text']:
            # In secure mode default valueType for Paragraphs and RichText is "url"
            only_urls = only_urls or value_type is None
        if p.tag == 'TimeSeries':
            # for TimeSeries default valueType is "url"
            only_urls = only_urls or value_type is None

        example_from_field_name = examples.get('$' + value)
        if example_from_field_name:
            # Get example by variable name
            task[value] = [example_from_field_name] if is_value_list else example_from_field_name

        elif value == 'video' and p.tag == 'HyperText':
            task[value] = examples.get('$videoHack')
        # List with a matching Ranker tag pair
        elif p.tag == 'List':
            task[value] = examples.get('List')
        elif p.tag == 'Paragraphs':
            # Paragraphs special case - replace nameKey/textKey if presented
            name_key = p.get('nameKey') or p.get('namekey') or 'author'
            text_key = p.get('textKey') or p.get('textkey') or 'text'
            if only_urls:
                params = {'nameKey': name_key, 'textKey': text_key}
                task[value] = examples['ParagraphsUrl'] + urlencode(params)
            else:
                task[value] = []
                for item in examples[p.tag]:
                    task[value].append({name_key: item['author'], text_key: item['text']})
        elif p.tag == 'TimeSeries':
            # TimeSeries special case - generate signals on-the-fly
            time_column = p.get('timeColumn')
            value_columns = []
            if hasattr(p, 'findall'):
                channels = p.findall('.//Channel[@column]')
                for ts_child in channels:
                    value_columns.append(ts_child.get('column'))
            sep = p.get('sep')
            time_format = p.get('timeFormat')

            if only_urls:
                # data is URL
                params = {'time': time_column, 'values': ','.join(value_columns)}
                if sep:
                    params['sep'] = sep
                if time_format:
                    params['tf'] = time_format
                task[value] = '/samples/time-series.csv?' + urlencode(params)
            else:
                # data is JSON
                task[value] = generate_time_series_json(time_column, value_columns, time_format)
        elif p.tag == 'HyperText':
            if only_urls:
                task[value] = examples['HyperTextUrl']
            else:
                task[value] = examples['HyperText']
        elif p.tag.lower().endswith('labels'):
            task[value] = examples['Labels']
        elif p.tag.lower() == 'choices':
            allow_nested = p.get('allowNested') or p.get('allownested') or 'false'
            if allow_nested == 'true':
                task[value] = examples['NestedChoices']
            else:
                task[value] = examples['Choices']
        else:
            # Patch for valueType="url"
            examples['Text'] = examples['TextUrl'] if only_urls else examples['TextRaw']
            # Not found by name, try to get example by type
            example_value = examples.get(p.tag, 'Something')
            task[value] = [example_value] if is_value_list else example_value

        # support for Repeater tag
        if '[' in value:
            base = value.split('[')[0]
            child = value.split(']')[1]

            # images[{{idx}}].url => { "images": [ {"url": "test.jpg"} ] }
            if child.startswith('.'):
                child_name = child[1:]
                task[base] = [{child_name: task[value]}, {child_name: task[value]}]
            # images[{{idx}}].url => { "images": [ "test.jpg", "test.jpg" ] }
            else:
                task[base] = [task[value], task[value]]

            # remove unused "images[{{idx}}].url"
            task.pop(value, None)

    return task


def _is_strftime_string(s):
    # simple way to detect strftime format
    return '%' in s


def _get_smallest_time_freq(time_format):
    """Determine the smallest time component in strftime format and return pandas frequency"""
    if not time_format:
        return 'D'  # default to daily

    # Order from smallest to largest (we want the smallest one)
    time_components = [
        ('%S', 's'),  # second
        ('%M', 'min'),  # minute
        ('%H', 'h'),  # hour
        ('%d', 'D'),  # day
        ('%m', 'MS'),  # month start
        ('%Y', 'YS'),  # year start
    ]

    # Find the smallest time component present in the format
    for strftime_code, pandas_freq in time_components:
        if strftime_code in time_format:
            return pandas_freq

    return 'D'  # default to daily if no time components found


def generate_time_series_json(time_column, value_columns, time_format=None):
    """Generate sample for time series"""
    n = 100
    if time_format is not None and not _is_strftime_string(time_format):
        time_fmt_map = {'yyyy-MM-dd': '%Y-%m-%d'}
        time_format = time_fmt_map.get(time_format)

    if time_format is None:
        times = np.arange(n).tolist()
    else:
        # Automatically determine the appropriate frequency based on time format
        freq = _get_smallest_time_freq(time_format)
        times = pd.date_range('2020-01-01', periods=n, freq=freq)
        new_times = []
        prev_time_str = None
        for time in times:
            time_str = time.strftime(time_format)

            # Check if formatted string is monotonic (to handle cycling due to format truncation)
            if prev_time_str is not None and time_str <= prev_time_str:
                break

            new_times.append(time_str)
            prev_time_str = time_str  # Update prev_time_str for next iteration

        times = new_times

    ts = {time_column: times}
    for value_col in value_columns:
        ts[value_col] = np.random.randn(len(times)).tolist()
    return ts


def get_sample_task(label_config, secure_mode=False):
    """Get sample task from labeling config and combine it with generated sample task"""
    predefined_task, annotations, predictions = get_task_from_labeling_config(label_config)
    generated_task = generate_sample_task_without_check(label_config, mode='editor_preview', secure_mode=secure_mode)
    if predefined_task is not None:
        generated_task.update(predefined_task)
    return generated_task, annotations, predictions

