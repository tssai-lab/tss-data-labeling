import io
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

import prelabel
from worker import Worker, binding_for


IMAGE_CONFIG = """<View><Image name="image" value="$image"/>
<Choices name="classification" toName="image" required="true">
<Choice value="正常" alias="label_2b26b6c99549c8823763b5c7e0e7ee32"/>
<Choice value="异常" alias="label_31e67818e7804b2d5b7493f1196399a3"/>
</Choices></View>"""


class WorkerLabelsTest(unittest.TestCase):
    def test_image_uses_display_labels_in_prompt_and_serializes_selected_alias(self):
        worker = Worker("http://127.0.0.1:8083", "fixture", {
            "mode": "image", "options": {"weights": "stub", "projector": "stub"},
        })
        worker.call = Mock(return_value={
            "task": {"data": {"image": "000.JPG"}},
            "resources": [{"field": "image", "mediaType": "image/jpeg",
                           "resourceId": "c55a319e-03d0-4f48-9d41-23e4d7802cab"}],
        })
        worker.request = Mock(return_value=io.BytesIO(b"test image"))

        def respond(**kwargs):
            prompt = json.loads(kwargs["messages"][0]["content"][0]["text"])
            self.assertEqual(prompt["labels"], ["正常", "异常"])
            self.assertEqual(prompt["guidelines"], "发现表面裂痕就标异常。")
            model_output = json.dumps({"label": "异常"})
            return {"choices": [{"message": {"content": model_output}}]}

        model = Mock()
        model.create_chat_completion.side_effect = respond
        with patch.object(prelabel, "prompted_model", return_value=model):
            candidates = worker.infer("/sample", {"generation": 1}, {
                "editorConfig": {"labelConfig": IMAGE_CONFIG},
                "specInstructions": "发现表面裂痕就标异常。",
            })
        self.assertEqual(candidates[0]["value"]["choices"], ["label_31e67818e7804b2d5b7493f1196399a3"])

    def test_energy_daily_and_interval_controls_use_their_own_aliases(self):
        xml = Path(__file__).with_name("energy-prelabel.xml").read_text(encoding="utf-8")
        xml = xml.replace('<Choice value="低负荷"', '<Choice value="低负荷" alias="label_low"')
        xml = xml.replace('<Label value="尖峰"', '<Label value="尖峰" alias="label_spike"')
        binding, _, _ = binding_for({"labelConfig": xml}, "timeseries")
        regions = prelabel.prompted_intervals(binding, [7, 8], [{"label": "尖峰", "start": 8, "end": 8}])
        candidates = prelabel.series_result(binding, regions, "低负荷", "08 时读数升高。")
        self.assertEqual(candidates[0]["value"]["choices"], ["label_low"])
        self.assertEqual(candidates[1]["value"]["timeserieslabels"], ["label_spike"])

    def test_image_labels_without_alias_keep_their_values(self):
        xml = IMAGE_CONFIG.replace(' alias="label_2b26b6c99549c8823763b5c7e0e7ee32"', "")
        binding, _, _ = binding_for({"labelConfig": xml}, "image")
        self.assertEqual(prelabel.result(binding, "choices", {"choices": ["正常"]})["value"]["choices"],
                         ["正常"])
