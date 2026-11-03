#!/usr/bin/env python3
"""Download one trusted GitHub Actions artifact with parallel range requests."""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import tempfile
import time
from typing import Any
import urllib.error
import urllib.request
import zipfile


PROFILES = {
    "airgap": {
        "artifact_prefix": "tss-aiplatform-airgap",
        "expected_files": {
            "airgap-common.sha256",
            "airgap-gpu.sha256",
            "calico-amd64.tar",
            "k8s-core-amd64.tar",
            "metrics-server-amd64.tar",
            "nvidia-amd64.tar",
            "sources.lock",
        },
        "checksum_files": {
            "airgap-common.sha256": [
                "k8s-core-amd64.tar",
                "calico-amd64.tar",
                "metrics-server-amd64.tar",
                "sources.lock",
            ],
            "airgap-gpu.sha256": ["nvidia-amd64.tar"],
        },
        "max_bytes": 4 * 1024**3,
    },
    "cpu-runtime": {
        "artifact_prefix": "tss-aiplatform-cpu-runtime",
        "expected_files": {
            "cpu-runtime-amd64.tar",
            "cpu-runtime.sha256",
            "sources.lock",
        },
        "checksum_files": {
            "cpu-runtime.sha256": ["cpu-runtime-amd64.tar", "sources.lock"],
        },
        # The locked CV image contains CPU ML libraries whose Docker archive is
        # larger than the compressed registry size. GitHub's artifact service
        # already enforces a 10 GB per-artifact limit; keep the local ceiling
        # equally narrow while accepting the measured 9.67 GB bundle.
        "max_bytes": 10 * 1024**3,
    },
    "gpu-runtime": {
        "artifact_prefix": "tss-aiplatform-gpu-runtime",
        "expected_files": {
            "gpu-runtime-amd64.tar",
            "gpu-runtime.sha256",
            "sources.lock",
        },
        "checksum_files": {
            "gpu-runtime.sha256": ["gpu-runtime-amd64.tar", "sources.lock"],
        },
        # CV training and GPU inference share the CUDA/PyTorch layers. Export
        # all GPU workers in one archive so shared layers are stored once, while
        # retaining GitHub's 10 GiB artifact ceiling as the hard limit.
        "max_bytes": 10 * 1024**3,
    },
    "platform": {
        "artifact_prefix": "tss-aiplatform-platform-images",
        "expected_files": {
            "platform-images-amd64.tar",
            "platform-images.sha256",
            "sources.lock",
        },
        "checksum_files": {
            "platform-images.sha256": ["platform-images-amd64.tar", "sources.lock"],
        },
        # The five lock entries have a combined conservative budget below
        # 2.1 GiB. Keep only a narrow 2.25 GiB ceiling for the staged bundle.
        "max_bytes": 2304 * 1024**2,
    },
    "backend-image": {
        "artifact_prefix": "tss-aiplatform-backend-image",
        "expected_files": {
            "backend-image-amd64.tar",
            "backend-image.sha256",
            "sources.lock",
        },
        "checksum_files": {
            "backend-image.sha256": ["backend-image-amd64.tar", "sources.lock"],
        },
        "max_bytes": 768 * 1024**2,
    },
    "frontend-image": {
        "artifact_prefix": "tss-aiplatform-frontend-image",
        "expected_files": {
            "frontend-image-amd64.tar",
            "frontend-image.sha256",
            "sources.lock",
        },
        "checksum_files": {
            "frontend-image.sha256": ["frontend-image-amd64.tar", "sources.lock"],
        },
        "max_bytes": 256 * 1024**2,
    },
}
USER_AGENT = "tss-aiplatform-artifact/1.1"


def fail(message: str) -> "NoReturn":
    raise RuntimeError(message)


def api_request(url: str, token: str) -> urllib.request.Request:
    return urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "User-Agent": USER_AGENT,
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )


def api_json(url: str, token: str) -> dict[str, Any]:
    with urllib.request.urlopen(api_request(url, token), timeout=30) as response:
        return json.load(response)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        return None


def signed_archive_url(repository: str, artifact_id: int, token: str) -> str:
    url = f"https://api.github.com/repos/{repository}/actions/artifacts/{artifact_id}/zip"
    opener = urllib.request.build_opener(NoRedirect)
    try:
        opener.open(api_request(url, token), timeout=30)
    except urllib.error.HTTPError as error:
        if error.code not in {302, 307}:
            raise
        location = error.headers.get("Location", "")
        if not location.startswith("https://"):
            fail("artifact API returned an invalid redirect")
        return location
    fail("artifact API did not return a download redirect")


def trusted_artifact(
    repository: str, run_id: int, head_sha: str, token: str, profile: str
) -> tuple[int, int, str]:
    payload = api_json(
        f"https://api.github.com/repos/{repository}/actions/runs/{run_id}/artifacts?per_page=100",
        token,
    )
    profile_config = PROFILES[profile]
    expected_name = f"{profile_config['artifact_prefix']}-{head_sha}"
    matches = [
        artifact
        for artifact in payload.get("artifacts", [])
        if artifact.get("name") == expected_name
    ]
    if len(matches) != 1:
        fail(f"the export run must contain exactly one expected {profile} artifact")
    artifact = matches[0]
    if artifact.get("expired") is not False:
        fail("the expected air-gap artifact is expired")
    artifact_id = artifact.get("id")
    size = artifact.get("size_in_bytes")
    digest = artifact.get("digest", "")
    if not isinstance(artifact_id, int) or artifact_id <= 0:
        fail("artifact ID is invalid")
    if not isinstance(size, int) or size <= 0 or size > profile_config["max_bytes"]:
        fail(f"artifact size is invalid or exceeds the {profile} safety limit")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        fail("artifact has no valid GitHub SHA256 digest")
    return artifact_id, size, digest.removeprefix("sha256:")


def probe_archive_size(url: str) -> int:
    request = urllib.request.Request(
        url,
        headers={"Range": "bytes=0-0", "User-Agent": USER_AGENT},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        content_range = response.headers.get("Content-Range", "")
        body = response.read()
        status_code = response.getcode()
    match = re.fullmatch(r"bytes 0-0/([1-9][0-9]*)", content_range)
    if status_code != 206 or body == b"" or not match:
        fail("artifact storage does not support strict byte-range downloads")
    return int(match.group(1))


def download_part(
    *,
    part_number: int,
    start: int,
    end: int,
    archive_size: int,
    initial_url: str,
    part_path: Path,
    repository: str,
    artifact_id: int,
    token: str,
) -> tuple[int, int]:
    expected_size = end - start + 1
    temporary_path = part_path.with_suffix(part_path.suffix + ".download")
    if part_path.exists():
        if part_path.is_symlink() or not part_path.is_file():
            fail(f"part {part_number} is not a regular file")
        actual_size = part_path.stat().st_size
        if actual_size != expected_size:
            fail(f"completed part {part_number} has an invalid size")
        return part_number, actual_size
    if temporary_path.exists() and (
        temporary_path.is_symlink() or not temporary_path.is_file()
    ):
        fail(f"partial part {part_number} is not a regular file")
    for attempt in range(1, 6):
        resumed_size = temporary_path.stat().st_size if temporary_path.exists() else 0
        if resumed_size > expected_size:
            fail(f"partial part {part_number} exceeds its expected size")
        if resumed_size == expected_size:
            temporary_path.replace(part_path)
            return part_number, expected_size
        request_start = start + resumed_size
        url = initial_url if attempt == 1 else signed_archive_url(repository, artifact_id, token)
        request = urllib.request.Request(
            url,
            headers={
                "Range": f"bytes={request_start}-{end}",
                "User-Agent": USER_AGENT,
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                content_range = response.headers.get("Content-Range", "")
                if response.getcode() != 206:
                    fail(f"part {part_number} was not returned as HTTP 206")
                if content_range != f"bytes {request_start}-{end}/{archive_size}":
                    fail(f"part {part_number} returned an unexpected content range")
                written = resumed_size
                with temporary_path.open("ab") as output_file:
                    while True:
                        block = response.read(1024 * 1024)
                        if not block:
                            break
                        output_file.write(block)
                        written += len(block)
            if written != expected_size:
                fail(
                    f"part {part_number} size mismatch: expected={expected_size} actual={written}"
                )
            temporary_path.replace(part_path)
            return part_number, written
        except Exception:
            if attempt == 5:
                raise
            time.sleep(min(2**attempt, 16))
    fail(f"part {part_number} exhausted its retries")


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as input_file:
        while block := input_file.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def validate_checksum_file(extract_dir: Path, checksum_name: str, expected_names: list[str]) -> None:
    checksum_path = extract_dir / checksum_name
    lines = checksum_path.read_text(encoding="utf-8").splitlines()
    parsed_names: list[str] = []
    parsed: list[tuple[str, str]] = []
    for line in lines:
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9._-]+)", line)
        if not match:
            fail(f"invalid checksum entry in {checksum_name}")
        parsed.append((match.group(1), match.group(2)))
        parsed_names.append(match.group(2))
    if parsed_names != expected_names:
        fail(f"unexpected paths or order in {checksum_name}")
    for expected_digest, name in parsed:
        if hash_file(extract_dir / name) != expected_digest:
            fail(f"bundle checksum mismatch: {name}")


def extract_verified_archive(archive_path: Path, extract_dir: Path, profile: str) -> None:
    profile_config = PROFILES[profile]
    expected_files = profile_config["expected_files"]
    with zipfile.ZipFile(archive_path) as archive:
        infos = archive.infolist()
        names = [info.filename for info in infos]
        if len(names) != len(expected_files) or set(names) != expected_files:
            fail(f"artifact archive does not contain exactly the expected {profile} files")
        if len(names) != len(set(names)):
            fail("artifact archive contains duplicate paths")
        total_size = 0
        for info in infos:
            if info.is_dir() or Path(info.filename).name != info.filename:
                fail("artifact archive contains a directory or unsafe path")
            file_type = (info.external_attr >> 16) & 0o170000
            if file_type == stat.S_IFLNK:
                fail("artifact archive contains a symbolic link")
            total_size += info.file_size
        if total_size <= 0 or total_size > profile_config["max_bytes"]:
            fail("artifact extracted size is invalid or exceeds the safety limit")

        extract_dir.mkdir(mode=0o700)
        for info in infos:
            destination = extract_dir / info.filename
            with archive.open(info, "r") as source, destination.open("xb") as output:
                shutil.copyfileobj(source, output, length=1024 * 1024)
            os.chmod(destination, 0o640)

    for checksum_name, expected_names in profile_config["checksum_files"].items():
        validate_checksum_file(extract_dir, checksum_name, expected_names)


def safe_stage_root(stage_root: str) -> Path:
    configured = Path(stage_root)
    if not configured.is_absolute() or configured.is_symlink():
        fail("stage root must be an absolute real directory")
    resolved = configured.resolve(strict=True)
    if resolved != configured or not resolved.is_dir():
        fail("stage root must already be normalized and present")
    if resolved.stat().st_uid != os.getuid():
        fail("stage root must be owned by the Runner user")
    if resolved.stat().st_mode & 0o022:
        fail("stage root must not be writable by group or other users")
    return resolved


def run_download(args: argparse.Namespace) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        fail("repository must be owner/name")
    if not re.fullmatch(r"[0-9a-f]{40}", args.head_sha):
        fail("head SHA must contain exactly 40 lowercase hexadecimal characters")
    if args.run_id <= 0 or not 2 <= args.workers <= 32:
        fail("run ID or worker count is outside the accepted range")
    token = os.environ.get("GH_TOKEN", "")
    if not token:
        fail("GH_TOKEN is required")
    stage_root = safe_stage_root(args.stage_root)
    target_dir = stage_root / f"{args.head_sha}-{args.run_id}"
    partial_dir = stage_root / f".partial-{args.head_sha}-{args.run_id}"
    if target_dir.exists() or target_dir.is_symlink():
        fail(f"staging path already exists: {target_dir}")

    artifact_id, metadata_size, expected_digest = trusted_artifact(
        args.repository, args.run_id, args.head_sha, token, args.profile
    )
    initial_url = signed_archive_url(args.repository, artifact_id, token)
    archive_size = probe_archive_size(initial_url)
    if archive_size != metadata_size:
        fail(
            f"artifact size differs between metadata and storage: {metadata_size} != {archive_size}"
        )
    if partial_dir.exists() or partial_dir.is_symlink():
        if partial_dir.is_symlink() or not partial_dir.is_dir():
            fail("partial staging path is not a real directory")
        partial_stat = partial_dir.stat()
        if partial_stat.st_uid != os.getuid() or partial_stat.st_mode & 0o077:
            fail("partial staging directory is not private to the Runner user")
    else:
        partial_dir.mkdir(mode=0o700)

    part_count = min(args.workers, archive_size)
    part_size = math.ceil(archive_size / part_count)
    tasks = []
    for part_number in range(part_count):
        start = part_number * part_size
        end = min(start + part_size, archive_size) - 1
        tasks.append((part_number, start, end, partial_dir / f"part-{part_number:03d}"))

    allowed_paths = {
        candidate
        for _, _, _, part_path in tasks
        for candidate in (part_path.name, f"{part_path.name}.download")
    }
    existing_paths = list(partial_dir.iterdir())
    if any(path.name not in allowed_paths for path in existing_paths):
        fail("partial staging directory contains an unexpected path")
    if any(path.is_symlink() or not path.is_file() for path in existing_paths):
        fail("partial staging directory contains a non-regular path")
    for _, _, _, part_path in tasks:
        if part_path.exists() and part_path.with_suffix(part_path.suffix + ".download").exists():
            fail(f"partial staging contains two copies of {part_path.name}")

    print(
        f"Downloading artifact={artifact_id} bytes={archive_size} parts={part_count} "
        f"resume_files={len(existing_paths)}",
        flush=True,
    )
    with concurrent.futures.ThreadPoolExecutor(max_workers=part_count) as executor:
        futures = [
            executor.submit(
                download_part,
                part_number=part_number,
                start=start,
                end=end,
                archive_size=archive_size,
                initial_url=initial_url,
                part_path=part_path,
                repository=args.repository,
                artifact_id=artifact_id,
                token=token,
            )
            for part_number, start, end, part_path in tasks
        ]
        for future in concurrent.futures.as_completed(futures):
            part_number, byte_count = future.result()
            print(f"Downloaded part={part_number + 1}/{part_count} bytes={byte_count}", flush=True)

    archive_path = partial_dir / "artifact.zip"
    archive_digest = hashlib.sha256()
    combined_size = 0
    with archive_path.open("xb") as archive_file:
        for _, _, _, part_path in tasks:
            with part_path.open("rb") as part_file:
                while block := part_file.read(1024 * 1024):
                    archive_file.write(block)
                    archive_digest.update(block)
                    combined_size += len(block)
    if combined_size != archive_size:
        fail("combined artifact size does not match GitHub metadata")
    if archive_digest.hexdigest() != expected_digest:
        fail("combined artifact SHA256 does not match the GitHub artifact digest")

    extracted_dir = partial_dir / "extracted"
    extract_verified_archive(archive_path, extracted_dir, args.profile)
    extracted_dir.replace(target_dir)
    shutil.rmtree(partial_dir)
    print(
        f"Parallel artifact staging complete: artifact={artifact_id} "
        f"digest=sha256:{expected_digest} target={target_dir}",
        flush=True,
    )


def make_checksum(content: dict[str, bytes], names: list[str]) -> bytes:
    return "".join(
        f"{hashlib.sha256(content[name]).hexdigest()}  {name}\n" for name in names
    ).encode()


def self_test_profile(root: Path, profile: str, content: dict[str, bytes]) -> None:
    profile_config = PROFILES[profile]
    for checksum_name, names in profile_config["checksum_files"].items():
        content[checksum_name] = make_checksum(content, names)
    archive_path = root / f"valid-{profile}.zip"
    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, value in content.items():
            archive.writestr(name, value)
    extract_verified_archive(archive_path, root / f"valid-{profile}", profile)

    unsafe_path = root / f"unsafe-{profile}.zip"
    with zipfile.ZipFile(unsafe_path, "w") as archive:
        for name, value in content.items():
            archive.writestr("../escape" if name == "sources.lock" else name, value)
    try:
        extract_verified_archive(unsafe_path, root / f"unsafe-{profile}", profile)
    except RuntimeError:
        pass
    else:
        fail(f"{profile} self-test did not reject an unsafe archive path")


def self_test() -> None:
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        airgap_content = {
            "k8s-core-amd64.tar": b"core",
            "calico-amd64.tar": b"calico",
            "metrics-server-amd64.tar": b"metrics-server",
            "nvidia-amd64.tar": b"nvidia",
            "sources.lock": b"image example.invalid/test:v1 sha256:" + b"0" * 64 + b"\n",
        }
        self_test_profile(root, "airgap", airgap_content)
        cpu_content = {
            "cpu-runtime-amd64.tar": b"cpu-runtime",
            "sources.lock": b"ghcr.io/example/runtime:v1|sha256:" + b"0" * 64 + b"\n",
        }
        self_test_profile(root, "cpu-runtime", cpu_content)
        gpu_content = {
            "gpu-runtime-amd64.tar": b"gpu-runtime",
            "sources.lock": b"ghcr.io/example/gpu-runtime:v1|sha256:" + b"0" * 64 + b"\n",
        }
        self_test_profile(root, "gpu-runtime", gpu_content)
        platform_content = {
            "platform-images-amd64.tar": b"platform-images",
            "sources.lock": b"ghcr.io/example/backend:v1|sha256:" + b"0" * 64 + b"\n",
        }
        self_test_profile(root, "platform", platform_content)
        backend_content = {
            "backend-image-amd64.tar": b"backend-image",
            "sources.lock": b"ghcr.io/example/backend:v1|sha256:" + b"0" * 64 + b"\n",
        }
        self_test_profile(root, "backend-image", backend_content)
        frontend_content = {
            "frontend-image-amd64.tar": b"frontend-image",
            "sources.lock": b"frontend-lock\n",
        }
        self_test_profile(root, "frontend-image", frontend_content)

        payload = b"resume-this-download"
        resumed_part = root / "part-000"
        resumed_part.with_suffix(".download").write_bytes(payload[:7])

        class RangeResponse:
            def __init__(self, request: urllib.request.Request):
                requested_range = request.headers["Range"]
                match = re.fullmatch(r"bytes=(\d+)-(\d+)", requested_range)
                if not match:
                    fail("self-test received an invalid range")
                self.start, self.end = map(int, match.groups())
                self.headers = {
                    "Content-Range": f"bytes {self.start}-{self.end}/{len(payload)}"
                }
                self.offset = self.start

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def getcode(self):
                return 206

            def read(self, size: int = -1):
                if self.offset > self.end:
                    return b""
                stop = self.end + 1 if size < 0 else min(self.offset + size, self.end + 1)
                block = payload[self.offset:stop]
                self.offset = stop
                return block

        original_urlopen = urllib.request.urlopen
        urllib.request.urlopen = lambda request, timeout=0: RangeResponse(request)
        try:
            download_part(
                part_number=0,
                start=0,
                end=len(payload) - 1,
                archive_size=len(payload),
                initial_url="https://artifact.invalid/download",
                part_path=resumed_part,
                repository="example/project",
                artifact_id=1,
                token="self-test",
            )
        finally:
            urllib.request.urlopen = original_urlopen
        if resumed_part.read_bytes() != payload:
            fail("parallel downloader did not resume the existing partial bytes")
    print("Parallel artifact downloader self-test passed.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--repository")
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--head-sha")
    parser.add_argument("--stage-root")
    parser.add_argument("--profile", choices=sorted(PROFILES), default="airgap")
    parser.add_argument("--workers", type=int, default=16)
    args = parser.parse_args()
    if not args.self_test and not all(
        [args.repository, args.run_id, args.head_sha, args.stage_root]
    ):
        parser.error("repository, run-id, head-sha and stage-root are required")
    return args


if __name__ == "__main__":
    try:
        arguments = parse_args()
        if arguments.self_test:
            self_test()
        else:
            run_download(arguments)
    except Exception as error:  # Deliberately concise: no token-bearing URL traceback.
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
