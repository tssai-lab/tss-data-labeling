"""Platform resource boundary around the pinned LS sample generator."""
import csv
import io
import re
from urllib.parse import urlparse, parse_qs
from datetime import datetime, timezone
from lxml import etree
import ls_backend_samples as upstream

IMAGE_URL='/api/labeling/v1/label-configs/preview-image'
original_series=upstream.generate_time_series_json


def series(time_column, value_columns, time_format=None):
    if len(value_columns)>32 or not value_columns or len(set(value_columns))!=len(value_columns):
        raise ValueError('PREVIEW_CHANNEL_COUNT_INVALID')
    if time_column in value_columns:
        raise ValueError('PREVIEW_COLUMNS_INVALID')
    if time_format in ('%Q','%s'):
        result=original_series(time_column,value_columns)
        origin=int(datetime(2020,1,1,tzinfo=timezone.utc).timestamp())
        result[time_column]=[(origin+index)*(1000 if time_format=='%Q' else 1) for index in range(100)]
        return result
    result=original_series(time_column,value_columns,time_format.replace('%L','%f') if time_format else None)
    if time_format and '%L' in time_format:
        result[time_column]=[re.sub(r'(\.\d{3})\d{3}',r'\1',value) for value in result[time_column]]
    return result


upstream.generate_time_series_json=series


def _fields(data,field):
    # LS supports literal dotted keys, nested input and Repeater placeholders.
    if isinstance(data,dict) and field in data:return [(field,data[field])]
    tokens=re.sub(r'\[([^]]+)\]',r'.\1',field).split('.')
    def walk(node,rest,path):
        if not rest:return [(path,node)]
        key=rest[0]
        if key.startswith('{{') and key.endswith('}}'):
            if not isinstance(node,list):raise ValueError('PREVIEW_ARRAY_INPUT_REQUIRED')
            return [item for index,child in enumerate(node) for item in walk(child,rest[1:],path+'.'+str(index))]
        try:child=node[int(key)] if isinstance(node,list) else node[key]
        except (KeyError,IndexError,ValueError,TypeError):raise ValueError('PREVIEW_INPUT_FIELD_MISSING')
        return walk(child,rest[1:],path+'.'+key if path else key)
    return walk(data,tokens,'')


def preview_task(xml):
    root=etree.fromstring(xml.encode(),etree.XMLParser(resolve_entities=False,no_network=True,load_dtd=False))
    if sum(1 for _ in root.iter())>5000:
        raise ValueError('PREVIEW_CONFIG_TOO_LARGE')
    task,annotations,predictions=upstream.get_sample_task(xml)
    resources=[]
    for node in root.iter():
        field=node.get('valueList') or node.get('value')
        if not field or not field.startswith('$'):
            continue
        field=field[1:]
        if node.tag=='TimeSeries' and node.get('valueType',node.get('valuetype','url')).lower()!='json':
            current=_fields(task,field)[0][1]
            if isinstance(current,str) and current.startswith('/samples/time-series.csv?'):
                params=parse_qs(urlparse(current).query)
                column=params.get('time',['time'])[0]
                channels=params.get('values',[''])[0].split(',')
                content=series(column,channels,params.get('tf',[None])[0])
                separator=params.get('sep',[','])[0]
                if separator not in {',',';','\t'}: raise ValueError('PREVIEW_SEPARATOR_INVALID')
                output=io.StringIO(newline='');writer=csv.writer(output,delimiter=separator)
                writer.writerow(content);writer.writerows(zip(*content.values()))
                resources.append({'field':field,'content':output.getvalue(),'mediaType':'text/csv'})
            else:
                raise ValueError('PREVIEW_EXTERNAL_SERIES_REQUIRES_PROJECT_RESOURCE')
        elif node.tag=='Image':
            for bound,value in _fields(task,field):
                paths=[bound+'.'+str(i) for i in range(len(value))] if isinstance(value,list) else [bound]
                resources.extend({'field':path,'url':IMAGE_URL,'mediaType':'image/jpeg'} for path in paths)
        elif node.tag in {'Text','HyperText'} and node.get('valueType','text')=='url':
            examples=upstream.data_examples('editor_preview')
            resources.append({'field':field,'content':examples['HyperText' if node.tag=='HyperText' else 'TextRaw'],'mediaType':'text/html' if node.tag=='HyperText' else 'text/plain'})
    return {'sample_task':task,'resources':resources,'annotations':annotations or [],'predictions':predictions or [],'isExample':True}
