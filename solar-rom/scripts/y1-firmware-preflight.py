#!/usr/bin/env python3
"""Read-only Y1 firmware/backup validator.

This tool deliberately has no flash or erase command.  It pins ROM inputs to
audited archives and refuses to identify a device variant unless at least two
independent boot-critical partition fingerprints agree.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import sys
import zipfile


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_REGISTRY = SCRIPT_DIR.parent / "config" / "y1-supported-bases.json"
CHUNK_SIZE = 1024 * 1024

REQUIRED_ARCHIVE_MEMBERS = {
    "boot.img",
    "lk.bin",
    "recovery.img",
    "system.img",
    "userdata.img",
}

# MTKClient and vendor packs use several names for the same scatter partition.
PARTITION_ALIASES = {
    "preloader": {"preloader"},
    "mbr": {"mbr"},
    "ebr1": {"ebr1"},
    "pro_info": {"proinfo", "pro_info"},
    "nvram": {"nvram"},
    "protect_f": {"protectf", "protect_f"},
    "protect_s": {"protects", "protect_s"},
    "seccfg": {"seccfg"},
    "uboot": {"uboot", "lk"},
    "boot": {"boot", "bootimg"},
    "recovery": {"recovery"},
    "sec_ro": {"secro", "sec_ro"},
    "misc": {"misc"},
    "logo": {"logo"},
    "expdb": {"expdb"},
    "android": {"android", "system"},
    "cache": {"cache"},
    "userdata": {"userdata", "usrdata"},
    "fat": {"fat"},
}

REQUIRED_BACKUP_PARTITIONS = {
    "preloader",
    "mbr",
    "ebr1",
    "pro_info",
    "nvram",
    "protect_f",
    "protect_s",
    "seccfg",
    "uboot",
    "boot",
    "recovery",
    "sec_ro",
    "misc",
    "logo",
    "expdb",
    "android",
    "cache",
    "userdata",
    "fat",
}


class PreflightError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(CHUNK_SIZE)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest().upper()


def load_registry(path: Path = DEFAULT_REGISTRY) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise PreflightError(f"cannot read registry {path}: {exc}") from exc
    if data.get("schema_version") != 1 or not isinstance(data.get("variants"), dict):
        raise PreflightError(f"unsupported registry schema in {path}")
    return data


def require_plain_file(path: Path, label: str) -> Path:
    try:
        resolved = path.resolve(strict=True)
    except OSError as exc:
        raise PreflightError(f"{label} does not exist: {path}") from exc
    if not resolved.is_file() or resolved.is_symlink():
        raise PreflightError(f"{label} must be a regular non-symlink file: {path}")
    return resolved


def verify_base_archive(archive: Path, variant: str, registry: dict) -> dict:
    if variant not in registry["variants"]:
        raise PreflightError(f"unsupported Y1 variant: {variant}")
    archive = require_plain_file(archive, "base archive")
    expected = registry["variants"][variant]["base_archive"]
    actual_size = archive.stat().st_size
    actual_hash = sha256_file(archive)
    if actual_size != int(expected["size"]) or actual_hash != expected["sha256"].upper():
        raise PreflightError(
            "base archive is not the audited "
            f"{registry['variants'][variant]['label']} input "
            f"(size {actual_size}, sha256 {actual_hash})"
        )
    if not zipfile.is_zipfile(str(archive)):
        raise PreflightError("base archive hash matched but ZIP structure is unreadable")
    with zipfile.ZipFile(str(archive), "r") as firmware:
        members = firmware.namelist()
        unsafe = [
            name
            for name in members
            if name.startswith(("/", "\\"))
            or ".." in Path(name.replace("\\", "/")).parts
        ]
        if unsafe:
            raise PreflightError(f"base archive contains unsafe paths: {unsafe[:3]}")
        basenames = {Path(name.replace("\\", "/")).name.lower() for name in members}
        missing = sorted(REQUIRED_ARCHIVE_MEMBERS - basenames)
        if missing:
            raise PreflightError(f"base archive is missing required members: {', '.join(missing)}")
        if not any(name.lower().endswith("_android_scatter.txt") for name in basenames):
            raise PreflightError("base archive is missing an Android scatter file")
    return {
        "ok": True,
        "variant": variant,
        "label": registry["variants"][variant]["label"],
        "archive": str(archive),
        "size": actual_size,
        "sha256": actual_hash,
    }


def normalized_partition_name(path: Path) -> str | None:
    name = path.name.lower()
    for suffix in (".img", ".bin", ".raw", ".dump"):
        if name.endswith(suffix):
            name = name[: -len(suffix)]
            break
    compact = "".join(ch for ch in name if ch.isalnum() or ch == "_")
    compact_no_underscore = compact.replace("_", "")
    for canonical, aliases in PARTITION_ALIASES.items():
        for alias in aliases:
            if compact == alias or compact_no_underscore == alias.replace("_", ""):
                return canonical
    return None


def inventory_backup(directory: Path) -> tuple[list[dict], dict[str, list[dict]]]:
    try:
        root = directory.resolve(strict=True)
    except OSError as exc:
        raise PreflightError(f"backup directory does not exist: {directory}") from exc
    if not root.is_dir() or root.is_symlink():
        raise PreflightError(f"backup directory must be a real directory: {directory}")

    files: list[dict] = []
    by_partition: dict[str, list[dict]] = {}
    for candidate in sorted(root.rglob("*")):
        if not candidate.is_file() or candidate.is_symlink():
            continue
        rel = candidate.relative_to(root).as_posix()
        item = {
            "path": rel,
            "size": candidate.stat().st_size,
            "sha256": sha256_file(candidate),
        }
        partition = normalized_partition_name(candidate)
        if partition:
            item["partition"] = partition
            by_partition.setdefault(partition, []).append(item)
        files.append(item)
    if not files:
        raise PreflightError(f"backup directory is empty: {directory}")
    return files, by_partition


def detect_variant(
    by_partition: dict[str, list[dict]], registry: dict, minimum_matches: int = 2
) -> tuple[str | None, dict[str, list[str]]]:
    matches: dict[str, list[str]] = {key: [] for key in registry["variants"]}
    for variant, definition in registry["variants"].items():
        expected = definition.get("partition_fingerprints", {})
        for partition, expected_hash in expected.items():
            for item in by_partition.get(partition, []):
                if item["sha256"].upper() == expected_hash.upper():
                    matches[variant].append(partition)
                    break

    eligible = [
        variant
        for variant, partition_matches in matches.items()
        if len(set(partition_matches)) >= minimum_matches
    ]
    if len(eligible) != 1:
        return None, matches
    selected = eligible[0]
    # Mixed evidence indicates a merged/incorrect backup and must fail closed.
    for other, partition_matches in matches.items():
        if other != selected and partition_matches:
            return None, matches
    return selected, matches


def validate_backup_inventory(
    by_partition: dict[str, list[dict]], files: list[dict], expected_variant: str, registry: dict
) -> dict:
    missing = sorted(REQUIRED_BACKUP_PARTITIONS - set(by_partition))
    full_flash = [
        item
        for item in files
        if Path(item["path"]).name.lower()
        in {"flash.bin", "full_flash.bin", "fullflash.bin", "emmc.bin"}
    ]
    if missing:
        raise PreflightError(
            "backup is incomplete; missing partition dumps: " + ", ".join(missing)
        )
    if not full_flash:
        raise PreflightError(
            "backup is incomplete; keep an independent full-flash dump named flash.bin"
        )
    detected, evidence = detect_variant(by_partition, registry)
    if detected is None:
        raise PreflightError(
            "cannot identify one supported Y1 variant from at least two agreeing "
            f"boot-critical fingerprints; evidence={evidence}"
        )
    if detected != expected_variant:
        raise PreflightError(
            f"backup identifies variant {detected}, not requested variant {expected_variant}"
        )
    return {
        "detected_variant": detected,
        "fingerprint_evidence": evidence[detected],
        "full_flash": full_flash[0]["path"],
    }


def create_backup_manifest(
    directory: Path, output: Path, expected_variant: str, device_note: str, registry: dict
) -> dict:
    if expected_variant not in registry["variants"]:
        raise PreflightError(f"unsupported Y1 variant: {expected_variant}")
    if output.exists() or output.is_symlink():
        raise PreflightError(f"refusing to overwrite existing manifest: {output}")
    files, by_partition = inventory_backup(directory)
    validation = validate_backup_inventory(
        by_partition, files, expected_variant, registry
    )
    manifest = {
        "schema_version": 1,
        "created_at_utc": dt.datetime.now(dt.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z"),
        "device": "Innioasis Y1",
        "device_note": device_note,
        "variant": expected_variant,
        "variant_label": registry["variants"][expected_variant]["label"],
        "variant_evidence": validation["fingerprint_evidence"],
        "full_flash": validation["full_flash"],
        "backup_root": ".",
        "files": files,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive create is the final overwrite guard.
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(manifest, stream, indent=2, sort_keys=True)
        stream.write("\n")
    return manifest


def verify_backup_manifest(manifest_path: Path, backup_dir: Path | None, registry: dict) -> dict:
    manifest_path = require_plain_file(manifest_path, "backup manifest")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except ValueError as exc:
        raise PreflightError(f"invalid backup manifest JSON: {exc}") from exc
    if manifest.get("schema_version") != 1:
        raise PreflightError("unsupported backup manifest schema")
    variant = manifest.get("variant")
    if variant not in registry["variants"]:
        raise PreflightError(f"manifest names unsupported Y1 variant: {variant}")
    root = (backup_dir or manifest_path.parent).resolve(strict=True)
    files = manifest.get("files")
    if not isinstance(files, list) or not files:
        raise PreflightError("manifest has no files")
    by_partition: dict[str, list[dict]] = {}
    checked: list[dict] = []
    for recorded in files:
        rel = recorded.get("path")
        if not isinstance(rel, str) or not rel:
            raise PreflightError("manifest contains an invalid relative path")
        candidate = (root / rel).resolve(strict=True)
        try:
            candidate.relative_to(root)
        except ValueError as exc:
            raise PreflightError(f"manifest path escapes backup root: {rel}") from exc
        candidate = require_plain_file(candidate, f"backup member {rel}")
        actual = {
            "path": rel,
            "size": candidate.stat().st_size,
            "sha256": sha256_file(candidate),
        }
        if actual["size"] != int(recorded.get("size", -1)):
            raise PreflightError(f"size mismatch for backup member: {rel}")
        if actual["sha256"] != str(recorded.get("sha256", "")).upper():
            raise PreflightError(f"SHA-256 mismatch for backup member: {rel}")
        partition = recorded.get("partition") or normalized_partition_name(candidate)
        if partition:
            actual["partition"] = partition
            by_partition.setdefault(partition, []).append(actual)
        checked.append(actual)
    validation = validate_backup_inventory(by_partition, checked, variant, registry)
    return {
        "ok": True,
        "manifest": str(manifest_path),
        "variant": variant,
        "files_verified": len(checked),
        "fingerprint_evidence": validation["fingerprint_evidence"],
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Fail-closed, read-only Innioasis Y1 firmware preflight"
    )
    parser.add_argument(
        "--registry", type=Path, default=DEFAULT_REGISTRY, help=argparse.SUPPRESS
    )
    commands = parser.add_subparsers(dest="command", required=True)

    base = commands.add_parser("verify-base", help="verify an audited Y1 base ZIP")
    base.add_argument("--variant", required=True, choices=("a", "b"))
    base.add_argument("--archive", required=True, type=Path)

    create = commands.add_parser(
        "create-backup-manifest",
        help="hash a new complete backup and prove its Y1 variant",
    )
    create.add_argument("--variant", required=True, choices=("a", "b"))
    create.add_argument("--backup-dir", required=True, type=Path)
    create.add_argument("--output", required=True, type=Path)
    create.add_argument(
        "--device-note",
        default="",
        help="non-secret physical label; do not put credentials here",
    )

    verify = commands.add_parser(
        "verify-backup", help="rehash every file in an existing backup manifest"
    )
    verify.add_argument("--manifest", required=True, type=Path)
    verify.add_argument("--backup-dir", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        registry = load_registry(args.registry)
        if args.command == "verify-base":
            result = verify_base_archive(args.archive, args.variant, registry)
        elif args.command == "create-backup-manifest":
            result = create_backup_manifest(
                args.backup_dir,
                args.output,
                args.variant,
                args.device_note,
                registry,
            )
        elif args.command == "verify-backup":
            result = verify_backup_manifest(
                args.manifest, args.backup_dir, registry
            )
        else:
            raise PreflightError(f"unsupported command: {args.command}")
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (PreflightError, OSError, zipfile.BadZipFile) as exc:
        print(f"REFUSED: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
