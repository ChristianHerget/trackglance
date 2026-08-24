#!/usr/bin/env python3
"""Validate a Locus fixture set without copying or naming it in output."""

import argparse
import hashlib
import os
import re
import struct
import subprocess
import sys
from pathlib import Path

PACKAGE_ID = "menion.android.locus"
MAX_MIN_SDK = 32


def read_properties(path: Path) -> dict[str, str]:
    properties = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not key:
            raise ValueError("invalid Locus fixture configuration")
        properties[key] = value
    return properties


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def certificate_sha256(apksigner: str, apk: Path) -> str:
    result = subprocess.run(
        [apksigner, "verify", "--print-certs", str(apk)],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if result.returncode:
        raise ValueError("an APK signature could not be verified")
    value = capture(
        result.stdout,
        r"^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)$",
        "signing certificate SHA-256",
    )
    return value.lower()


def badging(aapt: str, apk: Path) -> str:
    result = subprocess.run(
        [aapt, "dump", "badging", str(apk)],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if result.returncode:
        raise ValueError("an APK could not be parsed by aapt")
    return result.stdout


def capture(text: str, pattern: str, description: str, optional: bool = False) -> str | None:
    match = re.search(pattern, text, re.MULTILINE)
    if match:
        return match.group(1)
    if optional:
        return None
    raise ValueError(f"missing {description}")


def private_fingerprint(apks: list[Path]) -> str:
    """Hash names, sizes, and contents without exposing private APK names in output."""
    digest = hashlib.sha256()
    for apk in sorted(apks, key=lambda path: os.fsencode(path.name)):
        encoded_name = os.fsencode(apk.name)
        digest.update(struct.pack(">I", len(encoded_name)))
        digest.update(encoded_name)
        digest.update(struct.pack(">Q", apk.stat().st_size))
        with apk.open("rb") as source:
            while chunk := source.read(1024 * 1024):
                digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--aapt", default="aapt")
    parser.add_argument("--apksigner", default="apksigner")
    parser.add_argument("--fixture-config", type=Path)
    parser.add_argument("--fingerprint-only", action="store_true")
    args = parser.parse_args()
    if not args.directory.is_absolute() or not args.directory.is_dir():
        raise ValueError("the Locus APK directory must be an existing absolute directory")
    apks = sorted(path for path in args.directory.iterdir() if path.is_file() and path.suffix.lower() == ".apk")
    if not apks:
        raise ValueError("the fixture directory contains no APK files")
    if args.fingerprint_only:
        print(private_fingerprint(apks))
        return

    packages = set()
    versions = set()
    splits = set()
    bases = 0
    native_abis = set()
    has_native_code = False
    version_names = set()
    minimum_sdks = set()
    target_sdks = set()
    for apk in apks:
        text = badging(args.aapt, apk)
        package_line = next((line for line in text.splitlines() if line.startswith("package:")), "")
        package = capture(package_line, r"name='([^']+)'", "package ID")
        version = capture(package_line, r"versionCode='([^']+)'", "version code")
        version_name = capture(package_line, r"versionName='([^']+)'", "version name")
        split = capture(package_line, r"split='([^']+)'", "split name", optional=True)
        minimum = int(capture(text, r"^sdkVersion:'(\d+)'", "minimum SDK"))
        target = int(capture(text, r"^targetSdkVersion:'(\d+)'", "target SDK"))
        if minimum > MAX_MIN_SDK:
            raise ValueError("the supplied Locus build cannot run on API 32")
        packages.add(package)
        versions.add(version)
        version_names.add(version_name)
        minimum_sdks.add(minimum)
        target_sdks.add(target)
        if split is None:
            bases += 1
        elif split in splits:
            raise ValueError("the split set contains duplicate split names")
        else:
            splits.add(split)
        native_line = next((line for line in text.splitlines() if line.startswith("native-code:")), "")
        if native_line:
            has_native_code = True
            native_abis.update(re.findall(r"'([^']+)'", native_line))

    if packages != {PACKAGE_ID}:
        raise ValueError("every APK must use the Locus Map package ID")
    if len(versions) != 1 or bases != 1:
        raise ValueError("the APK set must contain one base and one consistent version")
    if has_native_code and "x86_64" not in native_abis:
        raise ValueError("the APK set does not contain x86_64 native code")
    abi_splits = {split for split in splits if any(abi in split for abi in ("arm64", "armeabi", "x86"))}
    if abi_splits and not any("x86_64" in split or "x86.64" in split for split in abi_splits):
        raise ValueError("the APK set is missing its x86_64 configuration split")

    if args.fixture_config:
        expected = read_properties(args.fixture_config)
        if len(apks) != 1:
            raise ValueError("the pinned public Locus fixture must be exactly one APK")
        apk = apks[0]
        checks = (
            (file_sha256(apk), expected["LOCUS_APK_SHA256"].lower(), "SHA-256"),
            (str(apk.stat().st_size), expected["LOCUS_APK_SIZE"], "size"),
            (next(iter(packages)), expected["LOCUS_APK_PACKAGE"], "package"),
            (next(iter(versions)), expected["LOCUS_APK_VERSION_CODE"], "version code"),
            (next(iter(version_names)), expected["LOCUS_APK_VERSION_NAME"], "version name"),
            (str(next(iter(minimum_sdks))), expected["LOCUS_APK_MIN_SDK"], "minimum SDK"),
            (str(next(iter(target_sdks))), expected["LOCUS_APK_TARGET_SDK"], "target SDK"),
            (
                certificate_sha256(args.apksigner, apk),
                expected["LOCUS_APK_CERT_SHA256"].lower(),
                "signing certificate",
            ),
        )
        for actual, pinned, description in checks:
            if actual != pinned:
                raise ValueError(f"the APK does not match the pinned {description}")
        if expected["LOCUS_APK_ABI"] not in native_abis:
            raise ValueError("the APK does not contain the pinned native ABI")
    print(f"Validated one API-32-compatible Locus Map install set ({len(apks)} APKs).")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as error:
        print(f"Locus APK validation failed: {error}", file=sys.stderr)
        sys.exit(1)
