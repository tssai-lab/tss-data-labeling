import copy
import unittest
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import adapter
from platform_result import validate_result
from ls_backend_templates import platform_catalog

IMAGE='<View><Image name="image" value="$image"/><RectangleLabels name="boxes" toName="image"><Label value="猫"/><Label value="狗"/></RectangleLabels>{}</View>'
META={'image':{'width':100,'height':100}}
def box(id='r',label='猫'):
    return {'id':id,'from_name':'boxes','to_name':'image','type':'rectanglelabels','original_width':100,'original_height':100,'value':{'x':10,'y':10,'width':20,'height':20,'rectanglelabels':[label]}}
def answer(name,value,id='class',type='choices',target='image'):
    return {'id':id,'from_name':name,'to_name':target,'type':type,'value':value}

class PlatformResultTest(unittest.TestCase):
    def check(self,xml,results,stage='complete',data=None,meta=None):
        adapter.validate_config(xml)
        return validate_result(xml,{'result':results},stage,data,meta)

    def test_single_source_yaml_catalog_has_stable_ids_and_content_versions(self):
        catalog=platform_catalog()
        selected=[t for t in catalog['templates'] if t['group'] in {'Computer Vision','Time Series Analysis'} and t['type']=='community']
        self.assertEqual(17,len(selected))
        self.assertIn('ls:computer-vision/image-classification',[t['id'] for t in selected])
        self.assertTrue(all(t['version']=='1.23.2:'+t['sourceSha256'][:16] for t in selected))

    def test_dynamic_choices_validate_against_actual_task_values_and_aliases(self):
        xml='<View><Image name="image" value="$image"/><Choices name="c" toName="image" value="$options" required="true"/></View>'
        data={'options':[{'value':'甲','alias':'stable-a'},{'value':'乙'}]}
        self.check(xml,[answer('c',{'choices':['stable-a']})],data=data)
        for stage in ['draft','complete','submit']:
            with self.assertRaises(ValueError):self.check(xml,[answer('c',{'choices':['不存在']})],stage,data)

    def test_parent_conditional_required_uses_choice_values_and_aliases(self):
        xml='<View><Image name="image" value="$image"/><Choices name="kind" toName="image"><Choice value="正常"/><Choice value="异常" alias="bad"/></Choices><View visibleWhen="choice-selected" whenTagName="kind" whenChoiceValue="异常"><TextArea name="why" toName="image" required="true"/></View></View>'
        self.check(xml,[answer('kind',{'choices':['正常']})])
        with self.assertRaisesRegex(ValueError,'REQUIRED_CONTROL'):self.check(xml,[answer('kind',{'choices':['bad']})])
        self.check(xml,[answer('kind',{'choices':['bad']}),answer('why',{'text':['原因']},type='textarea')])

    def test_per_region_required_distinguishes_each_region_and_label_condition(self):
        xml=IMAGE.format('<TextArea name="why" toName="image" perRegion="true" required="true" whenLabelValue="猫"/>')
        values=[box('one'),box('two'),answer('why',{'text':['第一只']},id='one',type='textarea')]
        self.check(xml,values,'draft',meta=META)
        with self.assertRaisesRegex(ValueError,'REQUIRED_PER_REGION'):self.check(xml,values,meta=META)
        values.append(answer('why',{'text':['第二只']},id='two',type='textarea'))
        self.check(xml,values,meta=META)
        values[1]=box('two','狗');values.pop();self.check(xml,values,meta=META)

    def test_draft_can_be_incomplete_but_invalid_present_geometry_is_rejected(self):
        value=box();value['value'].pop('height')
        self.check(IMAGE.format(''),[value],'draft',meta=META)
        with self.assertRaises(ValueError):self.check(IMAGE.format(''),[value],meta=META)
        value['value']['x']=-1
        with self.assertRaises(ValueError):self.check(IMAGE.format(''),[value],'draft',meta=META)

    def test_completed_and_submitted_regions_use_real_image_size_and_bounds(self):
        original=box();self.check(IMAGE.format(''),[original],'submit',meta=META)
        for field,value in [('x',101),('width',0),('height',-1)]:
            broken=copy.deepcopy(original);broken['value'][field]=value
            with self.assertRaises(ValueError):self.check(IMAGE.format(''),[broken],meta=META)
        broken=copy.deepcopy(original);broken['original_width']=99
        with self.assertRaisesRegex(ValueError,'IMAGE_SIZE_MISMATCH'):self.check(IMAGE.format(''),[broken],meta=META)

    def test_relation_requires_existing_regions_and_declared_relation_label(self):
        xml=IMAGE.format('<Relations><Relation value="相邻"/></Relations>');values=[box('one'),box('two')]
        relation={'type':'relation','from_id':'one','to_id':'two','direction':'right','labels':['相邻']}
        self.check(xml,values+[relation],meta=META)
        relation['to_id']='missing'
        with self.assertRaises(ValueError):self.check(xml,values+[relation],meta=META)

    def test_taxonomy_rejects_invalid_cross_branch_path(self):
        xml='<View><Image name="image" value="$image"/><Taxonomy name="c" toName="image"><Choice value="甲"><Choice value="一"/></Choice><Choice value="乙"><Choice value="二"/></Choice></Taxonomy></View>'
        self.check(xml,[answer('c',{'taxonomy':[['甲','一']]},type='taxonomy')])
        with self.assertRaisesRegex(ValueError,'TAXONOMY_PATH_INVALID'):self.check(xml,[answer('c',{'taxonomy':[['甲','二']]},type='taxonomy')])

    def test_brush_reuses_ls_rle_format_and_rejects_forged_size_without_allocating_mask(self):
        import numpy as np
        from label_studio_sdk.converter.brush import encode_rle
        xml='<View><Image name="image" value="$image"/><BrushLabels name="mask" toName="image"><Label value="猫"/></BrushLabels></View>'
        region=answer('mask',{'format':'rle','rle':encode_rle(np.zeros(64,dtype=np.uint8)),'brushlabels':['猫']},type='brushlabels')
        region.update(original_width=4,original_height=4)
        self.check(xml,[region],meta={'image':{'width':4,'height':4}})
        region['value']['rle'][0]=255
        with self.assertRaisesRegex(ValueError,'MASK_SIZE_MISMATCH'):self.check(xml,[region],meta={'image':{'width':4,'height':4}})

    def test_upstream_config_embedded_sample_is_preserved_without_persisting_answers(self):
        xml='<View><Text name="t" value="$text"/><Choices name="c" toName="t"><Choice value="甲"/></Choices></View><!-- {"data":{"text":"配置内样例"},"annotations":[{"result":[{"from_name":"c","to_name":"t","type":"choices","value":{"choices":["甲"]}}]}]} -->'
        preview=adapter.sample_config(xml)
        self.assertEqual('配置内样例',preview['sample_task']['text'])
        self.assertEqual('甲',preview['annotations'][0]['result'][0]['value']['choices'][0])
        self.assertTrue(preview['isExample'])

    def test_upstream_array_example_resources_are_preserved(self):
        xml='<View><Image name="image" valueList="$images"/><Choices name="c" toName="image"><Choice value="甲"/></Choices></View>'
        preview=adapter.sample_config(xml)
        self.assertIsInstance(preview['sample_task']['images'],list)
        self.assertTrue(preview['resources'])
        self.assertEqual('images.0',preview['resources'][0]['field'])

    def test_pinned_ls_schema_and_bindings_reject_the_same_invalid_structure(self):
        from ls_backend_config import validate_label_config
        cases=['<View><Image name="i" value="$x"/><Choices name="c" toName="missing"><Choice value="甲"/></Choices></View>',
               '<View><Image name="i" value="$x"/><Image name="i" value="$y"/><Choices name="c" toName="i"><Choice value="甲"/></Choices></View>']
        for xml in cases:
            with self.subTest(xml=xml):
                with self.assertRaises(ValueError):validate_label_config(xml)
                with self.assertRaises(ValueError):adapter.validate_config(xml)

    def test_global_classification_of_image_array_does_not_require_item_index(self):
        xml='<View><Image name="image" valueList="$images"/><Choices name="c" toName="image" required="true"><Choice value="甲"/></Choices></View>'
        self.check(xml,[answer('c',{'choices':['甲']})],meta={'image':{'items':[{'width':100,'height':100}]}})

    def test_same_region_id_on_two_objects_cannot_satisfy_both_required_answers(self):
        xml='<View><Image name="image" value="$image"/><Image name="other" value="$other"/><RectangleLabels name="boxes" toName="image,other"><Label value="猫"/></RectangleLabels><TextArea name="why" toName="image,other" perRegion="true" required="true"/></View>'
        one=box();two=box();two['to_name']='other'
        values=[one,two,answer('why',{'text':['原因']},id='r',type='textarea')]
        with self.assertRaisesRegex(ValueError,'REQUIRED_PER_REGION'):self.check(xml,values,meta={'image':{'width':100,'height':100},'other':{'width':100,'height':100}})

    def test_incomplete_timeseries_draft_preserves_partial_region(self):
        xml='<View><TimeSeries name="curve" value="$series" valueType="json" timeColumn="t"><Channel column="x"/></TimeSeries><TimeSeriesLabels name="events" toName="curve"><Label value="异常"/></TimeSeriesLabels></View>'
        region={'id':'r','from_name':'events','to_name':'curve','type':'timeserieslabels','value':{'start':0,'timeserieslabels':['异常']}}
        data={'series':{'t':[0,1],'x':[1,2]}}
        self.check(xml,[region],'draft',data=data)
        adapter.validate_times(xml,[region],data,partial=True)
        self.assertNotIn('end',region['value'])
        with self.assertRaises(ValueError):self.check(xml,[region],data=data)

if __name__=='__main__':unittest.main()
