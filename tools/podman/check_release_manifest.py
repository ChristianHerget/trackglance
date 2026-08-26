#!/usr/bin/env python3
"""Validate the security-sensitive surface of a compiled TrackGlance release APK."""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path


ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
ANDROID_NAME = f"{{{ANDROID_NAMESPACE}}}name"
APPLICATION_ID = "app.trackglance.bridge"
CODE_NAMESPACE = "io.github.christianherget.trackglance.bridge"
APP_PERMISSION = f"{APPLICATION_ID}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
MIN_SDK = "24"
COMPONENT_TAGS = {"activity", "activity-alias", "provider", "receiver", "service"}
FORBIDDEN_NETWORK_PERMISSIONS = {
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.INTERNET",
}
KNOWN_DEBUG_COMPONENTS = {
    f"{CODE_NAMESPACE}.BridgeScreenshotActivity",
    f"{CODE_NAMESPACE}.DebugStatusProvider",
}
EXPECTED_EXPORTED_COMPONENTS = {
    ("activity", f"{CODE_NAMESPACE}.MainActivity"): None,
    ("service", f"{CODE_NAMESPACE}.pebble.BridgePebbleListenerService"): None,
    ("receiver", "androidx.profileinstaller.ProfileInstallReceiver"): "android.permission.DUMP",
}
INITIALIZATION_PROVIDER = "androidx.startup.InitializationProvider"


class PolicyError(ValueError):
    """A release manifest does not match the intended security policy."""


@dataclass
class DumpElement:
    tag: str
    attributes: dict[str, str] = field(default_factory=dict)
    children: list["DumpElement"] = field(default_factory=list)


def _decode_dump_value(value: str) -> str:
    rendered = value.strip()
    if " (Raw: " in rendered:
        rendered = rendered.split(" (Raw: ", 1)[0]
    if rendered.startswith('"') and rendered.endswith('"'):
        try:
            return str(json.loads(rendered))
        except json.JSONDecodeError as error:
            raise PolicyError(f"invalid quoted aapt2 value {rendered!r}") from error
    return rendered


def parse_xmltree(text: str) -> DumpElement:
    roots: list[DumpElement] = []
    stack: list[tuple[int, DumpElement]] = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        stripped = line.lstrip()
        indent = len(line) - len(stripped)
        if stripped.startswith("E: "):
            tag = stripped[3:].split(" ", 1)[0]
            element = DumpElement(tag)
            while stack and stack[-1][0] >= indent:
                stack.pop()
            if stack:
                stack[-1][1].children.append(element)
            else:
                roots.append(element)
            stack.append((indent, element))
        elif stripped.startswith("A: "):
            if not stack:
                raise PolicyError(f"aapt2 attribute has no parent at line {line_number}")
            declaration, separator, value = stripped[3:].partition("=")
            if not separator:
                raise PolicyError(f"invalid aapt2 attribute at line {line_number}")
            declaration = re.sub(r"\(0x[0-9a-fA-F]+\)$", "", declaration)
            name = declaration.rsplit(":", 1)[-1]
            stack[-1][1].attributes[name] = _decode_dump_value(value)

    manifests = [root for root in roots if root.tag == "manifest"]
    if len(manifests) != 1:
        raise PolicyError(f"expected one manifest element, found {len(manifests)}")
    return manifests[0]


def _single(elements: list[DumpElement], label: str) -> DumpElement:
    if len(elements) != 1:
        raise PolicyError(f"expected one {label}, found {len(elements)}")
    return elements[0]


def _expect(label: str, actual: str | None, expected: str) -> None:
    if actual != expected:
        rendered = "<missing>" if actual is None else repr(actual)
        raise PolicyError(f"{label}: expected {expected!r}, found {rendered}")


def _quoted_fields(line: str) -> dict[str, str]:
    return dict(re.findall(r"([A-Za-z][A-Za-z0-9-]*)='([^']*)'", line))


def validate_badging(
    text: str,
    *,
    expected_version_code: str,
    expected_version_name: str,
    expected_target_sdk: str,
) -> None:
    lines = text.splitlines()
    package_lines = [line for line in lines if line.startswith("package:")]
    if len(package_lines) != 1:
        raise PolicyError(f"expected one badging package line, found {len(package_lines)}")
    package = _quoted_fields(package_lines[0])
    _expect("application ID", package.get("name"), APPLICATION_ID)
    _expect("version code", package.get("versionCode"), expected_version_code)
    _expect("version name", package.get("versionName"), expected_version_name)

    def sdk_value(prefix: str, label: str) -> str:
        matches = [line.removeprefix(prefix).removesuffix("'") for line in lines if line.startswith(prefix)]
        if len(matches) != 1:
            raise PolicyError(f"expected one {label} badging line, found {len(matches)}")
        return matches[0]

    _expect("minimum SDK", sdk_value("minSdkVersion:'", "minimum SDK"), MIN_SDK)
    _expect("target SDK", sdk_value("targetSdkVersion:'", "target SDK"), expected_target_sdk)

    permissions = [
        _quoted_fields(line).get("name")
        for line in lines
        if line.startswith("uses-permission:")
    ]
    if permissions != [APP_PERMISSION]:
        raise PolicyError(
            f"badging uses-permission set: expected {[APP_PERMISSION]!r}, found {permissions!r}"
        )


def _resolved_component_name(name: str) -> str:
    if name.startswith("."):
        return f"{CODE_NAMESPACE}{name}"
    if "." not in name:
        return f"{CODE_NAMESPACE}.{name}"
    return name


def debug_component_names(text: str) -> set[str]:
    try:
        root = ET.fromstring(text)
    except ET.ParseError as error:
        raise PolicyError(f"invalid debug source manifest: {error}") from error
    application = root.find("application")
    if application is None:
        raise PolicyError("debug source manifest has no application element")
    names = set(KNOWN_DEBUG_COMPONENTS)
    for component in application:
        tag = component.tag.rsplit("}", 1)[-1]
        if tag not in COMPONENT_TAGS:
            continue
        name = component.get(ANDROID_NAME)
        if not name:
            raise PolicyError(f"debug {tag} has no android:name")
        names.add(_resolved_component_name(name))
    return names


def _permission_set(manifest: DumpElement) -> None:
    declarations = [child for child in manifest.children if child.tag == "permission"]
    declared_names = [element.attributes.get("name") for element in declarations]
    if declared_names != [APP_PERMISSION]:
        raise PolicyError(
            f"declared permission set: expected {[APP_PERMISSION]!r}, found {declared_names!r}"
        )
    protection_level = declarations[0].attributes.get("protectionLevel")
    try:
        numeric_protection_level = int(protection_level or "", 0)
    except ValueError as error:
        raise PolicyError(
            f"{APP_PERMISSION} protection level: expected signature (0x2), found {protection_level!r}"
        ) from error
    if numeric_protection_level != 0x2:
        raise PolicyError(
            f"{APP_PERMISSION} protection level: expected signature (0x2), found {protection_level!r}"
        )

    uses_permissions = [
        child.attributes.get("name")
        for child in manifest.children
        if child.tag.startswith("uses-permission")
    ]
    for permission in uses_permissions:
        if permission in FORBIDDEN_NETWORK_PERMISSIONS:
            raise PolicyError(f"forbidden network permission: {permission}")
    if uses_permissions != [APP_PERMISSION]:
        raise PolicyError(
            f"uses-permission set: expected {[APP_PERMISSION]!r}, found {uses_permissions!r}"
        )


def _component_surface(application: DumpElement, debug_components: set[str]) -> None:
    components = [child for child in application.children if child.tag in COMPONENT_TAGS]
    seen: set[tuple[str, str]] = set()
    exported: set[tuple[str, str]] = set()
    by_key: dict[tuple[str, str], DumpElement] = {}
    for component in components:
        name = component.attributes.get("name")
        if not name:
            raise PolicyError(f"compiled {component.tag} has no android:name")
        key = (component.tag, name)
        if key in seen:
            raise PolicyError(f"duplicate compiled component: {component.tag} {name}")
        seen.add(key)
        by_key[key] = component
        if name in debug_components:
            raise PolicyError(f"debug-only component leaked into release: {component.tag} {name}")

        exported_value = component.attributes.get("exported")
        if exported_value not in {None, "false", "true"}:
            raise PolicyError(
                f"{component.tag} {name} android:exported: expected a boolean, found {exported_value!r}"
            )
        if exported_value == "true":
            exported.add(key)

    startup_providers = [
        component
        for component in components
        if component.tag == "provider"
        and (component.attributes.get("name") or "").startswith("androidx.startup.")
    ]
    _single(
        [
            component
            for component in startup_providers
            if component.attributes.get("name") == INITIALIZATION_PROVIDER
        ],
        INITIALIZATION_PROVIDER,
    )
    for startup_provider in startup_providers:
        name = startup_provider.attributes.get("name")
        _expect(
            f"provider {name} android:exported",
            startup_provider.attributes.get("exported"),
            "false",
        )

    expected = set(EXPECTED_EXPORTED_COMPONENTS)
    unexpected = sorted(exported - expected)
    if unexpected:
        tag, name = unexpected[0]
        raise PolicyError(f"unexpected exported component: {tag} {name}")
    missing = sorted(expected - exported)
    if missing:
        tag, name = missing[0]
        raise PolicyError(f"required exported component is missing or non-exported: {tag} {name}")

    for key, expected_permission in EXPECTED_EXPORTED_COMPONENTS.items():
        actual_permission = by_key[key].attributes.get("permission")
        if actual_permission != expected_permission:
            tag, name = key
            rendered = "<missing>" if actual_permission is None else repr(actual_permission)
            expected_rendered = "<none>" if expected_permission is None else repr(expected_permission)
            raise PolicyError(
                f"{tag} {name} permission: expected {expected_rendered}, found {rendered}"
            )

def validate_manifest(text: str, *, debug_manifest: str) -> None:
    manifest = parse_xmltree(text)
    _expect("compiled manifest package", manifest.attributes.get("package"), APPLICATION_ID)
    _permission_set(manifest)

    application = _single(
        [child for child in manifest.children if child.tag == "application"],
        "application element",
    )
    _expect("android:allowBackup", application.attributes.get("allowBackup"), "false")
    for attribute, description in (
        ("debuggable", "debuggable build"),
        ("testOnly", "test-only build"),
        ("usesCleartextTraffic", "cleartext network opt-in"),
    ):
        value = application.attributes.get(attribute)
        if value not in {None, "false"}:
            raise PolicyError(f"{description}: android:{attribute}={value!r}")

    _component_surface(application, debug_component_names(debug_manifest))


def validate_release(
    *,
    badging: str,
    manifest: str,
    debug_manifest: str,
    expected_version_code: str,
    expected_version_name: str,
    expected_target_sdk: str,
) -> None:
    validate_badging(
        badging,
        expected_version_code=expected_version_code,
        expected_version_name=expected_version_name,
        expected_target_sdk=expected_target_sdk,
    )
    validate_manifest(manifest, debug_manifest=debug_manifest)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--badging", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--debug-manifest", required=True, type=Path)
    parser.add_argument("--version-code", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--target-sdk", required=True)
    arguments = parser.parse_args()
    try:
        validate_release(
            badging=arguments.badging.read_text(encoding="utf-8"),
            manifest=arguments.manifest.read_text(encoding="utf-8"),
            debug_manifest=arguments.debug_manifest.read_text(encoding="utf-8"),
            expected_version_code=arguments.version_code,
            expected_version_name=arguments.version_name,
            expected_target_sdk=arguments.target_sdk,
        )
    except (OSError, PolicyError) as error:
        print(f"Release APK policy violation: {error}", file=sys.stderr)
        return 1
    print("Release APK manifest policy passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
