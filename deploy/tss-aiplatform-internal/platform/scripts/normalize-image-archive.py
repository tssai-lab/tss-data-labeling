#!/usr/bin/env python3
"""Rewrite a Docker image archive with deterministic tar metadata."""

from __future__ import annotations

import copy
import io
import os
from pathlib import Path, PurePosixPath
import sys
import tarfile
import tempfile


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def validate_member(member: tarfile.TarInfo, seen: set[str]) -> None:
    name = member.name
    path = PurePosixPath(name)
    if (
        not name
        or path.is_absolute()
        or "\\" in name
        or any(part in {"", ".", ".."} for part in path.parts)
    ):
        fail(f"image archive contains an unsafe member: {name!r}")
    if name in seen:
        fail(f"image archive contains a duplicate member: {name}")
    if not (member.isfile() or member.isdir()):
        fail(f"image archive contains an unsupported member type: {name}")
    seen.add(name)


def normalize_archive(source: Path, output: Path) -> None:
    if not source.is_absolute() or not output.is_absolute():
        fail("image archive paths must be absolute")
    if not source.is_file() or source.is_symlink():
        fail("source image archive is absent or symbolic")
    if output.exists() or output.is_symlink():
        fail("normalized image archive already exists")
    if not output.parent.is_dir() or output.parent.is_symlink():
        fail("normalized image archive parent is unsafe")

    temporary_fd, temporary_name = tempfile.mkstemp(
        prefix=f".{output.name}.", dir=output.parent
    )
    os.close(temporary_fd)
    temporary = Path(temporary_name)
    try:
        with tarfile.open(source, "r:") as source_archive:
            members = source_archive.getmembers()
            seen: set[str] = set()
            for member in members:
                validate_member(member, seen)
            if "manifest.json" not in seen:
                fail("image archive manifest is absent")

            with tarfile.open(
                temporary, "w:", format=tarfile.USTAR_FORMAT
            ) as output_archive:
                for member in sorted(members, key=lambda item: item.name):
                    normalized = copy.copy(member)
                    normalized.uid = 0
                    normalized.gid = 0
                    normalized.uname = ""
                    normalized.gname = ""
                    normalized.mtime = 0
                    normalized.mode = 0o755 if member.isdir() else 0o644
                    normalized.pax_headers = {}
                    if member.isfile():
                        content = source_archive.extractfile(member)
                        if content is None:
                            fail(f"image archive member cannot be read: {member.name}")
                        output_archive.addfile(normalized, content)
                    else:
                        output_archive.addfile(normalized)
        os.chmod(temporary, 0o600)
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)


def add_test_file(
    archive: tarfile.TarFile, name: str, content: bytes, *, mtime: int, uid: int
) -> None:
    member = tarfile.TarInfo(name)
    member.size = len(content)
    member.mtime = mtime
    member.uid = uid
    member.gid = uid
    member.uname = f"user{uid}"
    member.gname = f"group{uid}"
    member.mode = 0o600
    archive.addfile(member, io.BytesIO(content))


def add_test_directory(
    archive: tarfile.TarFile, name: str, *, mtime: int, uid: int
) -> None:
    member = tarfile.TarInfo(name)
    member.type = tarfile.DIRTYPE
    member.mtime = mtime
    member.uid = uid
    member.gid = uid
    member.mode = 0o700
    archive.addfile(member)


def self_test() -> None:
    with tempfile.TemporaryDirectory() as temporary_directory:
        root = Path(temporary_directory)
        first = root / "first.tar"
        second = root / "second.tar"
        first_normalized = root / "first-normalized.tar"
        second_normalized = root / "second-normalized.tar"
        entries = {
            "manifest.json": b"[]\n",
            "blobs/sha256/example": b"stable image bytes\n",
        }
        with tarfile.open(first, "w:") as archive:
            add_test_directory(archive, "blobs", mtime=100, uid=1000)
            add_test_directory(archive, "blobs/sha256", mtime=101, uid=1000)
            for name in ("manifest.json", "blobs/sha256/example"):
                add_test_file(archive, name, entries[name], mtime=102, uid=1000)
        with tarfile.open(second, "w:") as archive:
            for name in ("blobs/sha256/example", "manifest.json"):
                add_test_file(archive, name, entries[name], mtime=900, uid=2000)
            add_test_directory(archive, "blobs/sha256", mtime=901, uid=2000)
            add_test_directory(archive, "blobs", mtime=902, uid=2000)

        normalize_archive(first.resolve(), first_normalized.resolve())
        normalize_archive(second.resolve(), second_normalized.resolve())
        if first_normalized.read_bytes() != second_normalized.read_bytes():
            fail("normalized archives differ for identical content")
        with tarfile.open(first_normalized, "r:") as archive:
            members = archive.getmembers()
            if [member.name for member in members] != sorted(
                member.name for member in members
            ):
                fail("normalized archive order differs")
            for member in members:
                expected_mode = 0o755 if member.isdir() else 0o644
                if (
                    member.uid != 0
                    or member.gid != 0
                    or member.mtime != 0
                    or member.mode != expected_mode
                ):
                    fail("normalized archive metadata differs")
    print("PASS: deterministic image archive normalization")


def main() -> None:
    if sys.argv[1:] == ["--self-test"]:
        self_test()
        return
    if len(sys.argv) != 3:
        fail("usage: normalize-image-archive.py --self-test|SOURCE OUTPUT")
    normalize_archive(Path(sys.argv[1]), Path(sys.argv[2]))


if __name__ == "__main__":
    main()
