"""SDK region validation plus platform completion policy, following LS Required/Visibility mixins.
Preserves native region payloads; no task-specific/energy fields are assumed.
"""
import copy
import math
import re
from lxml import etree
from label_studio_sdk.label_interface import LabelInterface

GEOMETRY={'rectangle','rectanglelabels','polygon','polygonlabels','ellipse','ellipselabels','keypoint','keypointlabels','brush','brushlabels','timeserieslabels','labels'}


def at(data,path):
    if isinstance(data,dict) and path in data:return data[path]
    node=data
    for key in re.sub(r'\[(\d+)\]',r'.\1',path).split('.'):
        if not key:continue
        try:node=node[int(key)] if isinstance(node,list) else node[key]
        except (KeyError,IndexError,TypeError,ValueError):return None
    return node


def finite(value):
    if isinstance(value,float) and not math.isfinite(value):raise ValueError('RESULT_NUMBER_NOT_FINITE')
    if isinstance(value,dict):
        for child in value.values():finite(child)
    if isinstance(value,list):
        for child in value:finite(child)


def labels_from(value):
    labels=[]
    if isinstance(value,list):
        for item in value:
            if isinstance(item,str):labels.append(item)
            elif isinstance(item,dict):
                if isinstance(item.get('value'),str):labels.append(item.get('alias') or item['value'])
                labels.extend(labels_from(item.get('children',[])))
    return labels


def has_value(region):
    value=region.get('value',{});kind=region.get('type','')
    for key in ['choices','text','taxonomy',kind if kind.endswith('labels') else '']:
        if key and key in value:return bool(value[key]) and any(str(item).strip() for item in value[key])
    return bool(value)


def visible(node,results,areas,area=None):
    """Port LS VisibilityMixin predicates; submitted regions stand in for transient selection."""
    for current in [*reversed(list(node.iterancestors())),node]:
        attrs={key.lower():value for key,value in current.attrib.items()}
        mode=attrs.get('visiblewhen');tag=attrs.get('whentagname');choice=attrs.get('whenchoicevalue');label=attrs.get('whenlabelvalue')
        choices=[r for r in results if r.get('type')=='choices' and (not tag or r.get('from_name')==tag)
                 and (area is None or r.get('id')==area.get('id') or not r.get('_per_region'))]
        def selected_values(region):
            selected=set(region.get('value',{}).get('choices',[]))
            source=next((n for n in node.getroottree().iter('Choices') if n.get('name')==region.get('from_name')),None)
            if source is not None:
                for option in source.iter('Choice'):
                    if option.get('alias') in selected:selected.add(option.get('value'))
            return selected
        selected=any(selected_values(r) & set(choice.split(',')) if choice else selected_values(r) for r in choices)
        if mode=='choice-selected' and not selected:return False
        if mode=='choice-unselected' and selected:return False
        if mode=='region-selected':
            candidates=[area] if area is not None else areas
            matched=False
            for candidate in candidates:
                if tag and candidate.get('from_name')!=tag:continue
                found=[x for r in results if r.get('id')==candidate.get('id') for key,value in r.get('value',{}).items() if key.endswith('labels') and isinstance(value,list) for x in value]
                if not label or set(label.split(',')) & set(found):matched=True
            if not matched:return False
        if mode=='no-region-selected' and (area is not None or areas):return False
        if not mode and choice and not selected:return False
    return True


def validate_result(xml,envelope,stage,task_data=None,input_meta=None,completion=None):
    if stage not in {'draft','complete','submit'}:raise ValueError('RESULT_STAGE_INVALID')
    if not isinstance(envelope,dict) or not isinstance(envelope.get('result'),list):raise ValueError('RESULT_ARRAY_REQUIRED')
    results=copy.deepcopy(envelope['result']);complete=stage!='draft'
    if len(results)>20000:raise ValueError('RESULT_LIMIT_EXCEEDED')
    finite(results)
    interface=LabelInterface(xml)
    root=etree.fromstring(xml.encode(),etree.XMLParser(resolve_entities=False,no_network=True,load_dtd=False))
    nodes={node.get('name'):node for node in root.iter() if node.get('name')}
    objects=input_meta or {};seen=set();global_answers=set();controls={c.name:c for c in interface.controls}
    areas=[r for r in results if isinstance(r,dict) and r.get('type') in GEOMETRY and
           any(key in r.get('value',{}) for key in ['x','points','rle','start'])]
    for name,control in controls.items():
        source=nodes[name].get('value','')
        if source.startswith('$') and (control.tag.lower() in {'choices','taxonomy','labels'} or control.tag.lower().endswith('labels')):
            data=at(task_data or {},source[1:])
            if not isinstance(data,list):raise ValueError('DYNAMIC_LABEL_SOURCE_MISSING:'+name)
            control.labels=labels_from(data)
    for index,region in enumerate(results):
        if not isinstance(region,dict):raise ValueError('REGION_OBJECT_REQUIRED')
        kind=region.get('type','')
        if kind=='relation':continue
        name=region.get('from_name');control=controls.get(name);node=nodes.get(name)
        if control is None or region.get('to_name') not in (control.to_name or []):raise ValueError('RESULT_BINDING_INVALID')
        if not isinstance(region.get('value'),dict):raise ValueError('RESULT_VALUE_OBJECT_REQUIRED')
        per_region=str(node.get('perRegion',node.get('perregion','false'))).lower()=='true'
        region['_per_region']=per_region
        if not per_region and kind not in GEOMETRY:
            scope=(name,region.get('to_name'),region.get('item_index'))
            if scope in global_answers:raise ValueError('DUPLICATE_CONTROL_ANSWER')
            global_answers.add(scope)
        if region.get('id') is not None:
            key=(region['id'],name,region.get('to_name'),region.get('item_index'))
            if key in seen:raise ValueError('DUPLICATE_REGION_RESULT')
            seen.add(key)
        if per_region and not any(a.get('id')==region.get('id') and a.get('to_name')==region.get('to_name') for a in areas):raise ValueError('REGION_REFERENCE_INVALID')
        value=region['value']
        label_key=getattr(control,'_label_attr_name',None)
        if label_key and label_key!='text' and label_key in value:
            chosen=value[label_key]
            if not isinstance(chosen,list):raise ValueError('LABEL_ARRAY_REQUIRED')
            flattened=[leaf for path in chosen for leaf in path] if kind=='taxonomy' else chosen
            if any(not isinstance(label,str) or label not in (control.labels or []) for label in flattened):raise ValueError('LABEL_NOT_IN_CONFIG:'+name)
            if len(flattened)!=len(set(flattened)) and kind!='taxonomy':raise ValueError('DUPLICATE_LABEL')
            if complete and not chosen:raise ValueError('REGION_LABEL_REQUIRED')
        if kind=='choices' and node.get('choice','single') in {'single','single-radio'} and len(value.get('choices',[]))>1:raise ValueError('SINGLE_CHOICE_REQUIRED')
        if kind=='taxonomy' and 'taxonomy' in value:
            allowed=[]
            def paths(items,prefix):
                for item in items:
                    if not isinstance(item,dict) or 'value' not in item:continue
                    path=prefix+[item.get('alias') or item['value']];allowed.append(path);paths(item.get('children',[]),path)
            dynamic=node.get('value','')
            if dynamic.startswith('$'):paths(at(task_data or {},dynamic[1:]) or [],[])
            else:
                for choice_node in node.iter('Choice'):
                    chain=[*reversed(list(choice_node.iterancestors('Choice'))),choice_node]
                    allowed.append([part.get('alias') or part.get('value') for part in chain])
            if any(path not in allowed for path in value['taxonomy']):raise ValueError('TAXONOMY_PATH_INVALID')
        if kind in {'number','rating'} and kind in value:
            n=value[kind]
            if isinstance(n,bool) or not isinstance(n,(int,float)):raise ValueError('NUMERIC_VALUE_REQUIRED')
            attrs={key.lower():value for key,value in node.attrib.items()}
            low=attrs.get('min',0 if kind=='rating' else None);high=attrs.get('max',attrs.get('maxrating',5) if kind=='rating' else None)
            if (low is not None and n<float(low)) or (high is not None and n>float(high)):raise ValueError('VALUE_OUT_OF_RANGE')
        if kind=='textarea' and 'text' in value:
            if any(not isinstance(text,str) for text in value['text']):raise ValueError('TEXT_ARRAY_REQUIRED')
            maximum=node.get('maxSubmissions',node.get('maxsubmissions'))
            if maximum and int(maximum)>0 and len(value['text'])>int(maximum):raise ValueError('TEXT_SUBMISSION_LIMIT')
        if kind=='timeserieslabels':value.setdefault('instant',False)
        if complete:
            errors=interface.validate_region(region,return_errors=True,region_index=index,context={'result':results,'region':region})
            if errors:raise ValueError('; '.join(errors))
        else:
            expected=control.tag.lower()
            if kind!=expected and interface._merged_labels_geometry_control(control,kind) is None:raise ValueError('RESULT_TYPE_MISMATCH')
            # Missing shape attributes are allowed only in a draft, never invalid present values.
            for key in ['choices','text','taxonomy']:
                if key in value and not isinstance(value[key],list):raise ValueError('RESULT_ARRAY_REQUIRED:'+key)
        geometry(kind,value,complete)
        meta=objects.get(region.get('to_name'),{})
        if meta.get('items') is not None and (kind in GEOMETRY or node.get('perItem',node.get('peritem','false')).lower()=='true'):
            item=region.get('item_index')
            if not isinstance(item,int) or isinstance(item,bool) or not 0<=item<len(meta['items']):raise ValueError('IMAGE_ITEM_INDEX_INVALID')
            meta=meta['items'][item]
        for key,actual in [('original_width',meta.get('width')),('original_height',meta.get('height'))]:
            if key in region:
                if not isinstance(region[key],(int,float)) or isinstance(region[key],bool) or region[key]<=0:raise ValueError('IMAGE_SIZE_INVALID')
                if actual is not None and region[key]!=actual:raise ValueError('IMAGE_SIZE_MISMATCH')
            elif complete and meta.get('width') and kind in GEOMETRY and kind!='timeserieslabels':raise ValueError('IMAGE_SIZE_REQUIRED')
        if complete and kind in {'brush','brushlabels'}:validate_mask(value.get('rle'),meta)
    for index,region in enumerate(results):
        if region.get('type')=='relation':
            errors=interface.validate_relation(region,[r for r in results if r.get('type')!='relation'],return_errors=True,relation_index=index)
            if errors:raise ValueError(str(errors))
            allowed={node.get('alias') or node.get('value') for node in root.iter('Relation')}
            if any(label not in allowed for label in region.get('labels',[])):raise ValueError('RELATION_LABEL_INVALID')
    if not complete:return {'valid':True,'stage':stage}
    meaningful=any(r.get('type')!='relation' and has_value(r) for r in results)
    completion=completion or {}
    if not meaningful:
        if not completion.get('allowEmpty') or not str(envelope.get('emptyReason','')).strip():raise ValueError('ANNOTATION_OR_EMPTY_REASON_REQUIRED')
        return {'valid':True,'stage':stage}
    for name,control in controls.items():
        node=nodes[name]
        if node.get('required','false').lower()!='true' and name not in completion.get('requiredControls',[]):continue
        per_region=node.get('perRegion',node.get('perregion','false')).lower()=='true'
        per_item=node.get('perItem',node.get('peritem','false')).lower()=='true'
        if per_region:
            for area in areas:
                if area.get('to_name') not in (control.to_name or []):continue
                if node.get('whenLabelValue'):
                    labels=[label for r in results if r.get('id')==area.get('id') and r.get('to_name')==area.get('to_name') for key,values in r.get('value',{}).items() if key.endswith('labels') and isinstance(values,list) for label in values]
                    for source in root.iter():
                        if source.tag in {'Label','Choice'} and source.get('alias') in labels:labels.append(source.get('value'))
                    if not set(node.get('whenLabelValue').split(',')) & set(labels):continue
                if visible(node,results,areas,area) and not any(r.get('from_name')==name and r.get('id')==area.get('id') and r.get('to_name')==area.get('to_name') and has_value(r) for r in results):raise ValueError('REQUIRED_PER_REGION:'+name)
        elif per_item:
            for target in control.to_name or []:
                for item in range(len(objects.get(target,{}).get('items',[]))):
                    if visible(node,results,areas) and not any(r.get('from_name')==name and r.get('to_name')==target and r.get('item_index')==item and has_value(r) for r in results):raise ValueError('REQUIRED_PER_ITEM:'+name)
        elif visible(node,results,areas) and not any(r.get('from_name')==name and has_value(r) for r in results):raise ValueError('REQUIRED_CONTROL:'+name)
    return {'valid':True,'stage':stage}


def geometry(kind,value,complete):
    def number(key,lo=0,hi=100,positive=False):
        if key not in value:
            if complete:raise ValueError('GEOMETRY_FIELD_MISSING:'+key)
            return
        n=value[key]
        if isinstance(n,bool) or not isinstance(n,(int,float)) or not math.isfinite(n) or n<lo or n>hi or (positive and n==0):raise ValueError('GEOMETRY_OUT_OF_BOUNDS:'+key)
    if kind in {'rectangle','rectanglelabels','ellipse','ellipselabels','keypoint','keypointlabels'}:
        for key in ['x','y']:number(key)
        for key in (['width','height'] if kind.startswith('rectangle') else ['radiusX','radiusY'] if kind.startswith('ellipse') else []):number(key,positive=True)
        if kind.startswith('rectangle') and not value.get('rotation') and all(key in value for key in ['x','y','width','height']):
            if value['x']+value['width']>100.000001 or value['y']+value['height']>100.000001:raise ValueError('RECTANGLE_OUT_OF_IMAGE')
    if kind in {'polygon','polygonlabels'}:
        points=value.get('points',[])
        if not isinstance(points,list) or (complete and len(points)<3):raise ValueError('POLYGON_POINTS_INVALID')
        for point in points:
            if not isinstance(point,list) or len(point)!=2 or any(isinstance(v,bool) or not isinstance(v,(float,int)) or not 0<=v<=100 for v in point):raise ValueError('POLYGON_OUT_OF_IMAGE')


def validate_mask(rle,meta):
    from label_studio_sdk.converter.brush import InputStream,bytes2bit
    if not isinstance(rle,list) or len(rle)<7 or any(isinstance(v,bool) or not isinstance(v,int) or not 0<=v<=255 for v in rle):raise ValueError('MASK_RLE_INVALID')
    stream=InputStream(bytes2bit(rle))
    def read(size):
        if stream.i+size>len(stream.data):raise ValueError('MASK_RLE_TRUNCATED')
        return stream.read(size)
    count=read(32);word_size=read(5)+1;sizes=[read(4)+1 for _ in range(4)]
    expected=meta.get('width',0)*meta.get('height',0)*4
    if count<=0 or count>160000000 or word_size!=8 or (expected and count!=expected):raise ValueError('MASK_SIZE_MISMATCH')
    offset=0
    while offset<count:
        repeated=read(1);end=offset+1+read(sizes[read(2)])
        if end>count:raise ValueError('MASK_RUN_OUT_OF_BOUNDS')
        bits=word_size if repeated else (end-offset)*word_size
        if stream.i+bits>len(stream.data):raise ValueError('MASK_RLE_TRUNCATED')
        stream.i+=bits;offset=end
