import unittest
from pathlib import Path

from check_release_manifest import PolicyError, validate_release


FIXTURES = Path(__file__).resolve().parent / "fixtures" / "release-manifest"
VALID_BADGING = (FIXTURES / "valid-badging.txt").read_text(encoding="utf-8")
VALID_XMLTREE = (FIXTURES / "valid-xmltree.txt").read_text(encoding="utf-8")
DEBUG_MANIFEST = (FIXTURES / "debug-manifest.xml").read_text(encoding="utf-8")


class ReleaseManifestPolicyTest(unittest.TestCase):
    def validate(self, *, badging: str = VALID_BADGING, manifest: str = VALID_XMLTREE) -> None:
        validate_release(
            badging=badging,
            manifest=manifest,
            debug_manifest=DEBUG_MANIFEST,
            expected_version_code="16",
            expected_version_name="0.2.5",
            expected_target_sdk="36",
        )

    def rejected(self, message: str, *, badging: str = VALID_BADGING, manifest: str) -> None:
        with self.assertRaisesRegex(PolicyError, message):
            self.validate(badging=badging, manifest=manifest)

    def test_valid_release_manifest_passes(self):
        self.validate()

    def test_existing_release_metadata_checks_remain_enforced(self):
        replacements = {
            "app.trackglance.bridge": "example.invalid",
            "versionCode='16'": "versionCode='17'",
            "versionName='0.2.5'": "versionName='9.9.9'",
            "minSdkVersion:'24'": "minSdkVersion:'25'",
            "targetSdkVersion:'36'": "targetSdkVersion:'35'",
        }
        messages = (
            "application ID",
            "version code",
            "version name",
            "minimum SDK",
            "target SDK",
        )
        for (old, new), message in zip(replacements.items(), messages, strict=True):
            with self.subTest(message=message):
                badging = VALID_BADGING.replace(old, new, 1)
                with self.assertRaisesRegex(PolicyError, message):
                    self.validate(badging=badging)

    def test_network_permissions_are_rejected(self):
        for permission_name in (
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.INTERNET",
        ):
            with self.subTest(permission=permission_name):
                permission = f"""
      E: uses-permission
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="{permission_name}" (Raw: "{permission_name}")
"""
                manifest = VALID_XMLTREE.replace("      E: application", f"{permission}      E: application")
                self.rejected(f"forbidden network permission: {permission_name}", manifest=manifest)

    def test_every_other_unexpected_permission_is_rejected(self):
        permission = """
      E: uses-permission
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.CAMERA" (Raw: "android.permission.CAMERA")
"""
        manifest = VALID_XMLTREE.replace("      E: application", f"{permission}      E: application")
        self.rejected("uses-permission set", manifest=manifest)

    def test_unprotected_exported_profile_receiver_is_rejected(self):
        permission = (
            '            A: http://schemas.android.com/apk/res/android:permission(0x01010006)='
            '"android.permission.DUMP" (Raw: "android.permission.DUMP")\n'
        )
        self.rejected(
            "ProfileInstallReceiver permission",
            manifest=VALID_XMLTREE.replace(permission, ""),
        )

    def test_unexpected_exported_component_is_rejected(self):
        component = """
          E: service
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="example.UnexpectedService" (Raw: "example.UnexpectedService")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
"""
        manifest = VALID_XMLTREE.replace("          E: provider", f"{component}          E: provider")
        self.rejected("unexpected exported component: service example.UnexpectedService", manifest=manifest)

    def test_current_and_future_debug_components_are_rejected(self):
        for tag, name in (
            ("activity", "io.github.christianherget.trackglance.bridge.BridgeScreenshotActivity"),
            ("provider", "io.github.christianherget.trackglance.bridge.DebugStatusProvider"),
            ("service", "io.github.christianherget.trackglance.bridge.FutureDebugService"),
        ):
            with self.subTest(name=name):
                component = f"""
          E: {tag}
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="{name}" (Raw: "{name}")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false
"""
                manifest = VALID_XMLTREE.replace("          E: provider", f"{component}          E: provider")
                self.rejected("debug-only component leaked into release", manifest=manifest)

    def test_backup_enabled_is_rejected(self):
        self.rejected(
            "android:allowBackup",
            manifest=VALID_XMLTREE.replace("allowBackup(0x01010280)=false", "allowBackup(0x01010280)=true"),
        )

    def test_debuggable_and_test_only_builds_are_rejected(self):
        for attribute, message in (
            ("debuggable", "debuggable build"),
            ("testOnly", "test-only build"),
        ):
            with self.subTest(attribute=attribute):
                manifest = VALID_XMLTREE.replace(
                    "        A: http://schemas.android.com/apk/res/android:allowBackup",
                    f"        A: http://schemas.android.com/apk/res/android:{attribute}(0x0101000f)=true\n"
                    "        A: http://schemas.android.com/apk/res/android:allowBackup",
                )
                self.rejected(message, manifest=manifest)

    def test_cleartext_network_opt_in_is_rejected(self):
        manifest = VALID_XMLTREE.replace(
            "        A: http://schemas.android.com/apk/res/android:allowBackup",
            "        A: http://schemas.android.com/apk/res/android:usesCleartextTraffic(0x010104ec)=true\n"
            "        A: http://schemas.android.com/apk/res/android:allowBackup",
        )
        self.rejected("cleartext network opt-in", manifest=manifest)

    def test_app_permission_must_remain_signature_protected(self):
        self.rejected(
            "protection level",
            manifest=VALID_XMLTREE.replace("protectionLevel(0x01010009)=0x00000002", "protectionLevel(0x01010009)=0x0"),
        )

    def test_androidx_initialization_provider_must_remain_non_exported(self):
        self.rejected(
            "InitializationProvider android:exported",
            manifest=VALID_XMLTREE.replace(
                'name(0x01010003)="androidx.startup.InitializationProvider" '
                '(Raw: "androidx.startup.InitializationProvider")\n'
                "            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false",
                'name(0x01010003)="androidx.startup.InitializationProvider" '
                '(Raw: "androidx.startup.InitializationProvider")\n'
                "            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true",
            ),
        )


if __name__ == "__main__":
    unittest.main()
