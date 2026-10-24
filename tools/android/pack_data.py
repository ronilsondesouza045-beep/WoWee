#!/usr/bin/env python3
"""Pack an extracted Data tree for the Android data installer."""

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import time
import zipfile
from pathlib import Path

CORE_DIRS = {"opcodes"}
SKIP_NAMES = {".nomedia", ".cache", ".unpacked", ".wowee-packs"}
SKIP_DIRS = {"downloads"}
FIXED_TIME = (1980, 1, 1, 0, 0, 0)
CHUNK = 1024 * 1024


def human(size):
    value = float(size)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if value < 1024.0 or unit == "TB":
            return f"{value:.1f} {unit}" if unit != "B" else f"{int(value)} B"
        value /= 1024.0
    return f"{value:.1f} TB"


def skipped(name):
    return name in SKIP_NAMES or name in SKIP_DIRS or name.startswith(".")


def collect(root, relative):
    base = root / relative
    entries = []
    for entry in sorted(base.iterdir(), key=lambda item: item.name):
        if skipped(entry.name) or entry.is_symlink():
            continue
        child = relative / entry.name
        if entry.is_dir():
            entries.extend(collect(root, child))
        elif entry.is_file():
            entries.append((child, entry, entry.stat().st_size))
    return entries


def core_entries(root):
    entries = []
    for entry in sorted(root.iterdir(), key=lambda item: item.name):
        if skipped(entry.name) or entry.is_symlink() or entry.is_dir():
            continue
        entries.append((Path(entry.name), entry, entry.stat().st_size))
    for name in sorted(CORE_DIRS):
        directory = root / name
        if directory.is_dir() and not directory.is_symlink():
            entries.extend(collect(root, Path(name)))
    return entries


def split_entries(entries, budget):
    total = sum(size for _, _, size in entries)
    if total <= budget or len(entries) <= 1:
        return [entries]
    buckets = {}
    for entry in entries:
        buckets.setdefault(entry[0].parts[0], []).append(entry)
    if len(buckets) <= 1:
        groups = []
        current = []
        current_size = 0
        for entry in entries:
            if current and current_size + entry[2] > budget:
                groups.append(current)
                current = []
                current_size = 0
            current.append(entry)
            current_size += entry[2]
        if current:
            groups.append(current)
        return groups
    groups = []
    current = []
    current_size = 0
    for key in sorted(buckets):
        bucket = buckets[key]
        bucket_size = sum(size for _, _, size in bucket)
        if current and current_size + bucket_size > budget:
            groups.append(current)
            current = []
            current_size = 0
        if bucket_size <= budget:
            current.extend(bucket)
            current_size += bucket_size
        else:
            groups.extend(split_entries(bucket, budget))
    if current:
        groups.append(current)
    return groups


def sanitize(name):
    text = re.sub(r"[^A-Za-z0-9._-]+", "_", name).strip("._-").lower()
    return text or "pack"


def unique(base, used):
    candidate = base
    index = 2
    while candidate in used:
        candidate = f"{base}-{index}"
        index += 1
    used.add(candidate)
    return candidate


def build_pack_groups(root, max_bytes):
    groups = []
    used = {"core"}
    core = core_entries(root)
    if core:
        groups.append(("core", core))
    for entry in sorted(root.iterdir(), key=lambda item: item.name):
        if (not entry.is_dir() or entry.is_symlink() or skipped(entry.name)
                or entry.name in CORE_DIRS):
            continue
        files = collect(root, Path(entry.name))
        if not files:
            continue
        parts = split_entries(files, max_bytes)
        base = sanitize(entry.name)
        if len(parts) == 1:
            groups.append((unique(base, used), parts[0]))
        else:
            for index, part in enumerate(parts, 1):
                groups.append((unique(f"{base}-{index:02d}", used), part))
    return groups


def write_zip(path, entries, store):
    path.parent.mkdir(parents=True, exist_ok=True)
    compression = zipfile.ZIP_STORED if store else zipfile.ZIP_DEFLATED
    kwargs = {} if store else {"compresslevel": 6}
    with zipfile.ZipFile(path, "w", compression=compression, allowZip64=True, **kwargs) as archive:
        for relative, source, size in entries:
            info = zipfile.ZipInfo(relative.as_posix(), date_time=FIXED_TIME)
            info.compress_type = compression
            info.create_system = 3
            info.external_attr = 0o644 << 16
            info.file_size = size
            with source.open("rb") as src, archive.open(info, "w") as dst:
                shutil.copyfileobj(src, dst, CHUNK)


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for block in iter(lambda: file.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def next_version(out):
    manifest_path = out / "version.json"
    if not manifest_path.is_file():
        return 1
    try:
        with manifest_path.open("r", encoding="utf-8") as file:
            return int(json.load(file).get("version", 0)) + 1
    except (OSError, ValueError, json.JSONDecodeError) as error:
        raise SystemExit(f"cannot read {manifest_path}: {error}")


def clean_unreferenced(out, referenced):
    packs = out / "packs"
    if not packs.is_dir():
        return
    for path in packs.glob("*.zip"):
        if path.name not in referenced:
            path.unlink()


def build(source, out, version, max_bytes, store=False, dry_run=False,
          client_min=None, client_url=None, client_required=False, clean=False,
          base_url=None, url_template=None):
    root = Path(source)
    output = Path(out)
    if version < 1:
        raise SystemExit("version must be at least 1")
    if not root.is_dir():
        raise SystemExit(f"source is not a directory: {root}")
    if not (root / "manifest.json").is_file():
        raise SystemExit(f"missing manifest.json in {root}")
    root_resolved = root.resolve()
    output_resolved = output.resolve()
    if output_resolved == root_resolved or root_resolved in output_resolved.parents:
        raise SystemExit("output directory must be outside the source tree")
    if max_bytes < 1024 * 1024:
        raise SystemExit("--max-pack-bytes must be at least 1048576")
    base_url = base_url.rstrip("/") if base_url else None
    if base_url and not base_url.startswith(("https://", "http://")):
        raise SystemExit("--base-url must be an absolute http(s) URL")
    if url_template and not url_template.startswith(("https://", "http://")):
        raise SystemExit("--url-template must be an absolute http(s) URL")

    groups = build_pack_groups(root, max_bytes)
    if not groups:
        raise SystemExit("no files to pack")

    packs = []
    referenced = set()
    total_download = 0
    total_extracted = 0
    for pack_id, entries in groups:
        file_name = f"{pack_id}-v{version}.zip"
        path = output / "packs" / file_name
        if url_template:
            try:
                url = url_template.format(file=file_name, version=version, pack=pack_id)
            except (KeyError, IndexError, ValueError) as error:
                raise SystemExit(f"invalid --url-template: {error}")
        elif base_url:
            url = f"{base_url}/packs/{file_name}"
        else:
            url = f"packs/{file_name}"
        extracted = sum(size for _, _, size in entries)
        largest = max((size for _, _, size in entries), default=0)
        if largest > max_bytes:
            print(f"warning: {pack_id} contains a {human(largest)} file", file=sys.stderr)
        if dry_run:
            packs.append({
                "id": pack_id,
                "version": version,
                "url": url,
                "size": 0,
                "extractedSize": extracted,
                "fileCount": len(entries),
                "sha256": "",
            })
            total_extracted += extracted
            print(f"{pack_id}: {len(entries)} files, {human(extracted)} unpacked")
            continue
        write_zip(path, entries, store)
        size = path.stat().st_size
        digest = sha256_file(path)
        packs.append({
            "id": pack_id,
            "version": version,
            "url": url,
            "size": size,
            "extractedSize": extracted,
            "fileCount": len(entries),
            "sha256": digest,
        })
        referenced.add(file_name)
        total_download += size
        total_extracted += extracted
        print(f"{pack_id}: {len(entries)} files, {human(size)} download, "
              f"{human(extracted)} unpacked")

    manifest = {
        "schema": 1,
        "version": version,
        "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "totalDownloadSize": total_download,
        "totalExtractedSize": total_extracted,
        "packs": packs,
    }
    if client_min:
        client = {"minVersion": client_min, "required": bool(client_required)}
        if client_url:
            client["url"] = client_url
        manifest["client"] = client

    if dry_run:
        print()
        print(f"dry run: version {version}, {len(packs)} packs, "
              f"{human(total_extracted)} unpacked")
    if not dry_run:
        output.mkdir(parents=True, exist_ok=True)
        manifest_path = output / "version.json"
        temporary = output / "version.json.tmp"
        with temporary.open("w", encoding="utf-8") as file:
            json.dump(manifest, file, indent=2, ensure_ascii=False)
            file.write("\n")
        os.replace(temporary, manifest_path)
        if clean:
            clean_unreferenced(output, referenced)
        print()
        print(f"version: {version}")
        print(f"download: {human(total_download)}")
        print(f"installed: {human(total_extracted)}")
        print(f"manifest: {manifest_path}")
    return manifest


def main():
    parser = argparse.ArgumentParser(
        description="Pack an extracted Data tree into versioned ZIP packs and version.json.")
    parser.add_argument("--source", required=True, help="extracted Data directory")
    parser.add_argument("--out", required=True, help="output directory to upload")
    version_group = parser.add_mutually_exclusive_group(required=True)
    version_group.add_argument("--version", type=int, help="data version to publish")
    version_group.add_argument("--bump", action="store_true",
                               help="increment the version in OUT/version.json")
    parser.add_argument("--max-pack-bytes", type=int, default=1500000000,
                        help="maximum unpacked bytes per pack (default 1500000000)")
    parser.add_argument("--store", action="store_true", help="do not compress ZIP entries")
    url_group = parser.add_mutually_exclusive_group()
    url_group.add_argument("--base-url",
                           help="absolute URL for the directory holding version.json")
    url_group.add_argument("--url-template",
                           help="absolute URL template with {file}, {version} and {pack}")
    parser.add_argument("--dry-run", action="store_true", help="print the plan only")
    parser.add_argument("--clean", action="store_true",
                        help="remove packs/*.zip not referenced by the new version.json")
    parser.add_argument("--client-min", help="minimum compatible app version")
    parser.add_argument("--client-url", help="URL where a newer APK can be downloaded")
    parser.add_argument("--client-required", action="store_true",
                        help="block the data install until the app is updated")
    args = parser.parse_args()

    output = Path(args.out)
    version = next_version(output) if args.bump else args.version
    if version is None or version < 1:
        raise SystemExit("version must be at least 1")
    build(args.source, output, version, args.max_pack_bytes, args.store,
          args.dry_run, args.client_min, args.client_url, args.client_required,
          args.clean, args.base_url, args.url_template)


if __name__ == "__main__":
    main()
