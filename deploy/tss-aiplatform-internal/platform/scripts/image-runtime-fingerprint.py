#!/usr/bin/env python3
"""Create a stable fingerprint of execution-relevant container image fields."""

import hashlib
import json
import sys


CONFIG_KEYS = (
    "User",
    "ExposedPorts",
    "Env",
    "Entrypoint",
    "Cmd",
    "Healthcheck",
    "ArgsEscaped",
    "Volumes",
    "WorkingDir",
    "NetworkDisabled",
    "MacAddress",
    "OnBuild",
    "Labels",
    "StopSignal",
    "Shell",
)


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


try:
    image = json.load(sys.stdin)
except (json.JSONDecodeError, OSError) as exc:
    fail(f"cannot read image inspection JSON: {exc}")

if isinstance(image, list):
    if len(image) != 1 or not isinstance(image[0], dict):
        fail("docker image inspect JSON must contain exactly one image")
    image = image[0]
if not isinstance(image, dict):
    fail("image inspection JSON must be an object")

docker_shape = "Architecture" in image or "RootFS" in image
config = image.get("Config" if docker_shape else "config") or {}
rootfs = image.get("RootFS" if docker_shape else "rootfs") or {}
if not isinstance(config, dict) or not isinstance(rootfs, dict):
    fail("image config and root filesystem must be objects")

layers = rootfs.get("Layers" if docker_shape else "diff_ids")
if not isinstance(layers, list) or not layers:
    fail("image root filesystem has no layers")

normalized = {
    "architecture": image.get("Architecture" if docker_shape else "architecture"),
    "os": image.get("Os" if docker_shape else "os"),
    # Keep the key even when the image has no architecture variant. This makes
    # Docker inspect output and an OCI image config normalize identically.
    "variant": image.get("Variant" if docker_shape else "variant"),
    "config": {
        key: config[key]
        for key in CONFIG_KEYS
        if key in config and config[key] is not None
    },
    "rootfs": layers,
}
if not normalized["architecture"] or not normalized["os"]:
    fail("image architecture or operating system is missing")

canonical = json.dumps(
    normalized, ensure_ascii=False, sort_keys=True, separators=(",", ":")
).encode("utf-8")
print(hashlib.sha256(canonical).hexdigest())
