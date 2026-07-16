#!/usr/bin/env python3
"""Compute the next Solar release version from git tags (timestamp-based main + nightly)."""
from __future__ import annotations

import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from typing import Iterable, List, Optional, Tuple

NIGHTLY_NUM_TAG_RE = re.compile(r"^nightly-(\d+)$")
# nightly-YYYYMMDD-HHMM or plain YYYYMMDD-HHMM (main)
TS_TAG_RE = re.compile(r"^(?:nightly-)?(\d{8}-\d{4})$")
STABLE_TAG_RE = re.compile(r"^v(\d+\.\d+(?:\.\d+)?)$")


def git_tags() -> List[str]:
    out = subprocess.check_output(["git", "tag", "-l"], text=True)
    return [line.strip() for line in out.splitlines() if line.strip()]


def release_ts_minutes(version_or_tag: str) -> int:
    match = TS_TAG_RE.match(version_or_tag)
    if not match:
        return 0
    body = match.group(1)
    parts = body.split("-", 1)
    if len(parts) != 2 or len(parts[0]) != 8 or len(parts[1]) < 4:
        return 0
    date_part, time_part = parts[0], parts[1]
    y = int(date_part[:4])
    mo = int(date_part[4:6])
    d = int(date_part[6:8])
    hh = int(time_part[:2])
    mm = int(time_part[2:4])
    dt = datetime(y, mo, d, hh, mm, tzinfo=timezone.utc)
    epoch = datetime(2020, 1, 1, tzinfo=timezone.utc)
    return int((dt - epoch).total_seconds() // 60)


def max_release_ts_code(tags: Iterable[str]) -> int:
    codes: List[int] = []
    for tag in tags:
        if TS_TAG_RE.match(tag):
            codes.append(release_ts_minutes(tag))
        match = NIGHTLY_NUM_TAG_RE.match(tag)
        if match:
            codes.append(int(match.group(1)))
    return max(codes) if codes else 0


def read_gradle_version_code(gradle_path: str) -> int:
    text = open(gradle_path, encoding="utf-8").read()
    match = re.search(r"versionCode\s+(\d+)", text)
    return int(match.group(1)) if match else 0


def release_timestamp_name(*, nightly: bool) -> str:
    ts = os.environ.get("SOURCE_DATE_EPOCH")
    if ts:
        dt = datetime.fromtimestamp(int(ts), tz=timezone.utc)
    else:
        dt = datetime.now(timezone.utc)
    base = dt.strftime("%Y%m%d-%H%M")
    return f"nightly-{base}" if nightly else base


def resolve(branch: str, gradle_path: str, tags: Optional[Iterable[str]] = None) -> Tuple[str, str, int, str]:
    """Return (channel, version_name, version_code, tag)."""
    tag_list = list(tags) if tags is not None else git_tags()
    gradle_code = read_gradle_version_code(gradle_path)
    prior_code = max(max_release_ts_code(tag_list), gradle_code)

    if branch == "nightly":
        version_name = release_timestamp_name(nightly=True)
        ts_code = release_ts_minutes(version_name)
        version_code = max(prior_code, ts_code)
        return "nightly", version_name, version_code, version_name

    if branch == "main":
        version_name = release_timestamp_name(nightly=False)
        ts_code = release_ts_minutes(version_name)
        version_code = max(prior_code, ts_code)
        return "stable", version_name, version_code, version_name

    raise SystemExit(f"releases only from main or nightly (branch: {branch})")


def self_test() -> None:
    os.environ["SOURCE_DATE_EPOCH"] = "1719069900"  # 2024-06-22 15:25 UTC
    try:
        channel, name, code, tag = resolve("nightly", __file__, ["nightly-39", "nightly-49"])
        assert channel == "nightly" and tag == name, (channel, name, code, tag)
        assert name == "nightly-20240622-1525", name
        assert code >= 49, code

        channel, name, code, tag = resolve("main", __file__, ["20240622-1530", "nightly-20240622-1545"])
        assert channel == "stable" and tag == name, (channel, name, code, tag)
        assert name == "20240622-1525", name
        assert not name.startswith("nightly-"), name
        assert code >= release_ts_minutes("20240622-1545"), code

        channel, name, code, tag = resolve("nightly", __file__, [])
        assert TS_TAG_RE.match(name), (name, code)
        assert code >= 1, code
    finally:
        os.environ.pop("SOURCE_DATE_EPOCH", None)

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
