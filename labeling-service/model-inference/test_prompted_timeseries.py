import json
from io import BytesIO
from pathlib import Path
import unittest
from urllib.error import HTTPError
from unittest.mock import Mock, patch

import online_model
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

    def test_online_worker_applies_frozen_guidelines_and_writes_native_results(self):
        xml = self.xml.replace('value="尖峰"', 'value="环比跳升"')
        endpoint = "https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions"
        connection = {"protocol": "openai-chat", "endpoint": endpoint,
                      "apiKeyEnv": "DASHSCOPE_API_KEY", "model": "qwen3.6-plus", "enableThinking": False}
        with patch.dict("os.environ", {"LABELING_ONLINE_MODEL_HOSTS": "maas.qianwenaiapi.com", "DASHSCOPE_API_KEY": "fake"}), \
                patch.object(online_model, "build_opener") as opener:
            response = {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(self.output)}}]}
            opener.return_value.open.return_value = BytesIO(json.dumps(response).encode())
            results = self.worker.infer("/sample", {"generation": 1}, {
                "editorConfig": {"labelConfig": xml, "timeseriesPrelabelMode": "instructions"},
                "specInstructions": self.guidelines, "onlineModel": connection,
            })
            request = opener.return_value.open.call_args.args[0]
        body = json.loads(request.data)
        prompt = json.loads(body["messages"][0]["content"])
        self.assertEqual(prompt["guidelines"], self.guidelines)
        self.assertEqual(prompt["sample"]["context"]["dailyMedian"], 100)
        self.assertEqual(request.full_url, endpoint)
        self.assertEqual(body["model"], "qwen3.6-plus")
        self.assertIs(body["enable_thinking"], False)
        self.assertEqual(request.get_header("Authorization"), "Bearer fake")
        self.assertEqual(results[0]["value"]["choices"], ["低负荷"])
        self.assertEqual(results[1]["value"]["start"], 8)

    def test_online_rate_limit_is_reported_without_provider_response(self):
        url = "https://models.example.test/predict"
        with patch.dict("os.environ", {"LABELING_ONLINE_MODEL_HOSTS": "models.example.test"}), \
                patch.object(online_model, "build_opener") as opener:
            opener.return_value.open.side_effect = HTTPError(url, 429, "fake provider body", {}, None)
            with self.assertRaisesRegex(online_model.OnlineModelError, "^MODEL_RATE_LIMITED$"):
                online_model.predict({"protocol": "prediction-v1", "endpoint": url}, {
                    "messages": [{"content": '{"labels":["正常"]}'}],
                    "response_format": {"schema": {"required": ["label"]}},
                })

    def test_custom_model_receives_image_and_prediction_contract(self):
        image = "data:image/png;base64,fixture"
        schema = {"required": ["label"], "properties": {"label": {"enum": ["正常"]}}}
        with patch.dict("os.environ", {"LABELING_ONLINE_MODEL_HOSTS": "models.example.test"}), \
                patch.object(online_model, "build_opener") as opener:
            opener.return_value.open.return_value = BytesIO(json.dumps({"label": "正常"}).encode())
            result = online_model.predict({"protocol": "prediction-v1", "model": "defect-v1",
                                           "endpoint": "https://models.example.test/predict"}, {
                "messages": [{"content": [{"type": "text", "text": '{"labels":["正常"]}'},
                                          {"type": "image_url", "image_url": {"url": image}}]}],
                "response_format": {"schema": schema},
            })
            sent = json.loads(opener.return_value.open.call_args.args[0].data)
        self.assertEqual(sent, {"model": "defect-v1", "input": {"labels": ["正常"]}, "image": image, "outputSchema": schema})
        self.assertEqual(result, {"label": "正常"})


if __name__ == "__main__":
    unittest.main()
