"""HTTP transport for online models used by the annotation Worker."""

import json
import os
from socket import timeout
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class OnlineModelError(RuntimeError):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def predict(connection, completion):
    endpoint = connection["endpoint"]
    parsed = urlsplit(endpoint)
    hosts = {host.strip().lower() for host in os.getenv("LABELING_ONLINE_MODEL_HOSTS", "").split(",")}
    if (parsed.scheme != "https" or not parsed.hostname or parsed.hostname not in hosts
            or parsed.username or parsed.password or parsed.query or parsed.fragment):
        raise OnlineModelError("MODEL_ENDPOINT_NOT_ALLOWED")

    secret_env = connection.get("apiKeyEnv")
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if secret_env:
        if secret_env == "DASHSCOPE_API_KEY" and not (
                parsed.hostname == "dashscope.aliyuncs.com" or parsed.hostname.endswith(".maas.aliyuncs.com")
                or parsed.hostname in {"dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com",
                                       "maas.qianwenaiapi.com"}):
            raise OnlineModelError("MODEL_ENDPOINT_NOT_ALLOWED")
        secret = os.getenv(secret_env)
        if not secret:
            raise OnlineModelError("MODEL_CREDENTIAL_MISSING")
        headers["Authorization"] = f"Bearer {secret}"

    schema = completion["response_format"]["schema"]
    protocol = connection["protocol"]
    if protocol == "openai-chat":
        model = connection.get("model")
        if not model:
            raise OnlineModelError("MODEL_CONFIGURATION_INVALID")
        body = {**completion, "model": model, "stream": False,
                "response_format": {"type": "json_object"}}
        if connection.get("enableThinking") is not None:
            body["enable_thinking"] = connection["enableThinking"]
    elif protocol == "prediction-v1":
        content = completion["messages"][0]["content"]
        text = content if isinstance(content, str) else content[0]["text"]
        body = {"model": connection.get("model"), "input": json.loads(text), "outputSchema": schema}
        if not isinstance(content, str):
            body["image"] = content[1]["image_url"]["url"]
    else:
        raise OnlineModelError("MODEL_PROTOCOL_UNSUPPORTED")

    request = Request(endpoint, json.dumps(body, ensure_ascii=False, allow_nan=False).encode(), headers)
    try:
        with build_opener(NoRedirect()).open(request, timeout=90) as response:
            encoded = response.read(1024 * 1024 + 1)
            if len(encoded) > 1024 * 1024:
                raise OnlineModelError("MODEL_OUTPUT_TOO_LARGE")
            payload = json.loads(encoded)
    except HTTPError as error:
        if error.code == 429:
            raise OnlineModelError("MODEL_RATE_LIMITED") from None
        if error.code in (401, 403):
            raise OnlineModelError("MODEL_AUTH_FAILED") from None
        if error.code >= 500:
            raise OnlineModelError("MODEL_UNAVAILABLE") from None
        raise OnlineModelError("MODEL_REQUEST_REJECTED") from None
    except (timeout, URLError, OSError) as error:
        reason = getattr(error, "reason", error)
        code = "MODEL_TIMEOUT" if isinstance(reason, timeout) else "MODEL_UNAVAILABLE"
        raise OnlineModelError(code) from None
    except (ValueError, UnicodeError):
        raise OnlineModelError("MODEL_OUTPUT_INVALID") from None

    try:
        if protocol == "openai-chat":
            choice = payload["choices"][0]
            if choice.get("finish_reason") == "length":
                raise OnlineModelError("MODEL_OUTPUT_TRUNCATED")
            payload = json.loads(choice["message"]["content"])
        if not isinstance(payload, dict) or set(payload) != set(schema["required"]):
            raise OnlineModelError("MODEL_OUTPUT_INVALID")
        return payload
    except (AttributeError, KeyError, ValueError, TypeError, IndexError):
        raise OnlineModelError("MODEL_OUTPUT_INVALID") from None
