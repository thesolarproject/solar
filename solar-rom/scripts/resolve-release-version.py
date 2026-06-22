#!/usr/bin/env python3
"""Compute the next Solar release version from git tags (sequential, not local gradle)."""
from __future__ import annotations

import re
import subprocess
import sys
from decimal import Decimal
from typing import Iterable, List, Optional, Tuple

NIGHTLY_TAG_RE = re.compile(r"^nightly-(\d+)$")
STABLE_TAG_RE = re.compile(r"^v(\d+\.\d+(?:\.\d+)?)$")


def git_tags() -> List[str]:
    out = subprocess.check_output(["git", "tag", "-l"], text=True)
    return [line.strip() for line in out.splitlines() if line.strip()]


def max_nightly_number(tags: Iterable[str]) -> int:
    nums: List[int] = []
    for tag in tags:
        match = NIGHTLY_TAG_RE.match(tag)
        if match:
            nums.append(int(match.group(1)))
    return max(nums) if nums else 0


def max_stable_version(tags: Iterable[str]) -> Optional[Decimal]:
    versions: List[Decimal] = []
    v0_track: List[Decimal] = []
    for tag in tags:
        match = STABLE_TAG_RE.match(tag)
        if match:
            ver = Decimal(match.group(1))
            versions.append(ver)
            if tag.startswith("v0."):
                v0_track.append(ver)
    pool = v0_track if v0_track else versions
    return max(pool) if pool else None


def bump_semver_patch_tenth(current: Decimal) -> str:
    nxt = current + Decimal("0.1")
    text = format(nxt, "f").rstrip("0").rstrip(".")
    if "." not in text:
        text += ".0"
    return text


def read_gradle_version_code(gradle_path: str) -> int:
    text = open(gradle_path, encoding="utf-8").read()
    match = re.search(r"versionCode\s+(\d+)", text)
    return int(match.group(1)) if match else 0


def resolve(branch: str, gradle_path: str, tags: Optional[Iterable[str]] = None) -> Tuple[str, str, int, str]:
    """Return (channel, version_name, version_code, tag)."""
    tag_list = list(tags) if tags is not None else git_tags()
    nightly_max = max_nightly_number(tag_list)
    gradle_code = read_gradle_version_code(gradle_path)

    if branch == "nightly":
        number = nightly_max + 1
        version_name = f"nightly-{number}"
        version_code = number
        return "nightly", version_name, version_code, version_name

    if branch == "main":
        latest = max_stable_version(tag_list)
        version_name = bump_semver_patch_tenth(latest) if latest is not None else "0.1"
        version_code = max(nightly_max, gradle_code) + 1
        return "stable", version_name, version_code, f"v{version_name}"

    raise SystemExit(f"releases only from main or nightly (branch: {branch})")


def self_test() -> None:
    tags = ["nightly-39", "nightly-49", "nightly-52", "v0.2", "v1.5"]
    channel, name, code, tag = resolve("nightly", __file__, tags)
    assert channel == "nightly" and name == "nightly-53" and code == 53 and tag == "nightly-53", (
        channel, name, code, tag
    )

    channel, name, code, tag = resolve("nightly", __file__, [])
    assert name == "nightly-1" and code == 1, (name, code)

    channel, name, code, tag = resolve("main", __file__, ["nightly-52", "v1.5", "v0.2"])
    assert name == "0.3" and tag == "v0.3" and code == 53, (name, code, tag)

    channel, name, code, tag = resolve("main", __file__, ["nightly-10"])
    assert name == "0.1" and tag == "v0.1", (name, tag)

    print("resolve-release-version: self-test ok")


def main() -> None:
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        self_test()
        return
    if len(sys.argv) != 3:
        raise SystemExit(f"usage: {sys.argv[0]} <main|nightly> <app/build.gradle>")

    channel, version_name, version_code, tag = resolve(sys.argv[1], sys.argv[2])
    print(f"channel={channel}")
    print(f"version_name={version_name}")
    print(f"version_code={version_code}")
    print(f"tag={tag}")


if __name__ == "__main__":
    main()
