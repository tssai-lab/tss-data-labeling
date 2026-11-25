import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

import prelabel
from worker import Worker


class PromptedTimeseriesTest(unittest.TestCase):
    def setUp(self):
        self.xml = Path(__file__).with_name("energy-prelabel.xml").read_text(encoding="utf-8")
        self.worker = Worker("http://127.0.0.1:8083", "fixture", {
            "mode": "timeseries", "options": {"weights": "stub"},
        })
        values = [2] * 24
        values[8] = 5
        self.data = {
            "station": "fixture", "daily_kwh": 55,
            "series": {"hour_local": list(range(24)), "value_kwh": values},
            "history_summary": {"days": 30, "hourlyP5": 1, "hourlyP99": 90,
                                "dailyDays": 30, "dailyMedian": 100},
        }
        self.worker.call = Mock(return_value={"task": {"data": self.data}})
        self.guidelines = "比上一小时翻倍时标记环比跳升，日用量不足历史中位数的60%时标记低负荷。"
        self.output = {
            "dailyLabel": "低负荷", "intervals": [{"label": "环比跳升", "start": 8, "end": 8}],
            "rationale": "08时从2升至5 kWh。当天55 kWh，低于日用量中位数100 kWh的60%。",
        }

    def infer(self, xml, respond, **config):
        model = Mock()

        def completion(**kwargs):
            prompt = json.loads(kwargs["messages"][0]["content"])
            schema = kwargs["response_format"]["schema"]
            return {"choices": [{"message": {"content": json.dumps(respond(prompt, schema))}}]}

        model.create_chat_completion.side_effect = completion
        with patch.object(prelabel, "prompted_model", return_value=model):
            return self.worker.infer("/sample", {"generation": 1}, {
                "editorConfig": {"labelConfig": xml, **config},
                "specInstructions": self.guidelines, "instructions": "先核查异常候选。",
            })

    def test_instructions_can_produce_an_event_outside_the_old_thresholds(self):
        xml = self.xml.replace('value="尖峰"', 'value="环比跳升"')

        def respond(prompt, schema):
            self.assertEqual(prompt["guidelines"], self.guidelines)
            self.assertEqual(prompt["batchNotes"], "先核查异常候选。")
            self.assertEqual(prompt["sample"]["values"][8], 5)
            self.assertEqual(prompt["sample"]["context"]["hourlyP99"], 90)
            self.assertEqual(prompt["sample"]["context"]["dailyKwh"], 55)
            self.assertNotIn("eligibleCandidates", prompt["sample"])
            self.assertIn("环比跳升", schema["properties"]["intervals"]["items"]["properties"]["label"]["enum"])
            self.assertIn("one short sentence", prompt["task"])
            self.assertEqual(schema["properties"]["rationale"]["maxLength"], prelabel.MAX_ENERGY_RATIONALE_LENGTH)
            return self.output

        results = self.infer(xml, respond, timeseriesPrelabelMode="instructions")
        self.assertEqual(results[0]["value"]["choices"], ["低负荷"])
        self.assertEqual(results[1]["value"], {
            "start": 8, "end": 8, "instant": True, "timeserieslabels": ["环比跳升"],
        })
        self.assertEqual(results[2]["value"]["text"], [self.output["rationale"]])

    def test_rejects_invalid_model_output(self):
        xml = self.xml.replace('value="尖峰"', 'value="环比跳升"')
        variants = [
            ({"dailyLabel": "未知日级标签"}, "outside the project configuration"),
            ({"intervals": [{"label": "未知区间标签", "start": 8, "end": 8}]}, "outside the project configuration"),
            ({"intervals": [{"label": "环比跳升", "start": 8, "end": 25}]}, "ordered timestamps"),
            ({"intervals": [{"label": "环比跳升", "start": 8, "end": 7}]}, "ordered timestamps"),
            ({"rationale": " "}, "judgment rationale"),
            ({"rationale": "依据" * prelabel.MAX_ENERGY_RATIONALE_LENGTH}, "exceeded the length limit"),
        ]
        for changes, error in variants:
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, error):
                self.infer(xml, lambda *_: {**self.output, **changes}, timeseriesPrelabelMode="instructions")

    def test_frozen_legacy_version_still_uses_its_threshold(self):
        def respond(prompt, schema):
            self.assertEqual(prompt["sample"]["eligibleCandidates"], {})
            self.assertIn("低负荷", schema["properties"]["dailyLabel"]["enum"])
            return {"selectedIds": [], "dailyLabel": "低负荷"}

        results = self.infer(self.xml, respond, energyRules={"lowDailyPercent": 60})
        self.assertEqual(results[0]["value"]["choices"], ["低负荷"])
        self.assertIn("60%", results[-1]["value"]["text"][0])


if __name__ == "__main__":
    unittest.main()
