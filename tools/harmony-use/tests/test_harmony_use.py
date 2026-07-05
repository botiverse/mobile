from __future__ import annotations

import unittest

from android_use.adb import direction_to_swipe as android_direction_to_swipe
from android_use.cli import collect_extras
from android_use.config import AndroidConfig
from android_use.logcat import analyze_logcat_text
from device_use.remote import shell_join as shared_shell_join
from harmony_use.hdc import direction_to_swipe, quote_shell
from harmony_use.hilog import TLS_NATIVE_STUB, analyze_hilog_text
from harmony_use.config import HarmonyConfig
from harmony_use.remote import remote_env_prefix, shell_join
from ios_use.config import IosConfig
from ios_use.log import analyze_ios_log_text
from ios_use.simctl import safe_prefix as ios_safe_prefix


class HarmonyUseTest(unittest.TestCase):
    def test_quote_shell_escapes_single_quotes(self) -> None:
        self.assertEqual("'can'\\''t'", quote_shell("can't"))

    def test_direction_to_swipe_has_expected_orientation(self) -> None:
        up = direction_to_swipe("up", width=1000, height=2000)
        down = direction_to_swipe("down", width=1000, height=2000)
        self.assertLess(up[3], up[1])
        self.assertGreater(down[3], down[1])

    def test_hilog_analysis_detects_ohos_ktor_tls_stub(self) -> None:
        text = (
            "07-05 I [KLog][SlockSharedAuth]:shared_auth_network "
            f"slock_auth_ktor_failed detail={TLS_NATIVE_STUB}.\n"
        )
        analysis = analyze_hilog_text(text)
        self.assertTrue(analysis["tls_native_stub"])
        self.assertEqual(1, analysis["network_failure_count"])
        self.assertEqual(1, analysis["slock_auth_count"])

    def test_hilog_analysis_detects_networkkmm_and_tls_verify_failures(self) -> None:
        text = "\n".join(
            [
                "07-05 E slock_networkkmm_failed method=POST path=auth/login reason=NetworkKMMError",
                "07-05 E slock_networkkmm_init_failed detail=VBTransportInitHelper missing",
                "07-05 E slock_networkkmm tag=pbcurl error=CURLE_SSL_CACERT SSL certificate problem: peer verify failed",
            ]
        )
        analysis = analyze_hilog_text(text)
        self.assertTrue(analysis["networkkmm_failure"])
        self.assertTrue(analysis["tls_verify_failure"])
        self.assertEqual(3, analysis["network_failure_count"])
        self.assertEqual(1, analysis["tls_verify_failure_count"])

    def test_shell_join_quotes_remote_command_parts(self) -> None:
        self.assertEqual("hdc tconn 192.168.50.129:5555", shell_join(["hdc", "tconn", "192.168.50.129:5555"]))
        self.assertEqual("'hello world'", shell_join(["hello world"]))

    def test_remote_env_prefix_preserves_remote_path_expansion(self) -> None:
        config = HarmonyConfig.from_defaults(deveco_home="/Applications/DevEco-Studio.app/Contents")
        prefix = remote_env_prefix(config)
        self.assertIn("DEVECO_SDK_HOME=", prefix)
        self.assertIn("PATH=", prefix)
        self.assertIn(":$PATH", prefix)

    def test_shared_shell_join_is_platform_neutral(self) -> None:
        self.assertEqual("adb devices", shared_shell_join(["adb", "devices"]))
        self.assertEqual("'hello world'", shared_shell_join(["hello world"]))

    def test_android_defaults_keep_slock_components_independent(self) -> None:
        config = AndroidConfig.from_defaults(package="build.raft.dev")
        self.assertEqual("build.raft.dev", config.package)
        self.assertEqual("build.raft.dev/.MainActivity", config.main_component)
        self.assertEqual("build.raft.dev/.shared.SlockKuiklyActivity", config.kuikly_component)

    def test_android_direction_to_swipe_matches_legacy_orientation(self) -> None:
        up = android_direction_to_swipe("up", width=1000, height=2000)
        left = android_direction_to_swipe("left", width=1000, height=2000)
        self.assertLess(up[3], up[1])
        self.assertLess(left[2], left[0])

    def test_android_logcat_analysis_detects_network_and_crash(self) -> None:
        analysis = analyze_logcat_text(
            "\n".join(
                [
                    "E SlockSharedAuth: slock_auth_ktor_failed reason=SSLHandshakeException",
                    "E AndroidRuntime: FATAL EXCEPTION: main",
                ]
            )
        )
        self.assertTrue(analysis["has_network_failure"])
        self.assertTrue(analysis["has_crash"])

    def test_android_extra_normalization(self) -> None:
        class Args:
            string_extra = ["pageName=compose_thread"]
            bool_extra = ["enable=true"]
            long_extra = ["auto_close_ms=1000"]
            int_extra = ["count=2"]

        self.assertEqual(
            [
                ("--es", "pageName", "compose_thread"),
                ("--ez", "enable", "true"),
                ("--el", "auto_close_ms", "1000"),
                ("--ei", "count", "2"),
            ],
            collect_extras(Args()),
        )

    def test_ios_defaults_and_safe_prefix(self) -> None:
        config = IosConfig.from_defaults(bundle="build.raft.dev")
        self.assertEqual("build.raft.dev", config.bundle)
        self.assertEqual("state_1", ios_safe_prefix("state 1"))

    def test_ios_log_analysis_detects_realtime_and_crash(self) -> None:
        analysis = analyze_ios_log_text(
            "\n".join(
                [
                    "SlockIosRealtimeHost socket connected",
                    "Fatal error: unexpectedly found nil",
                ]
            )
        )
        self.assertTrue(analysis["has_crash"])
        self.assertGreaterEqual(analysis["realtime_signal_count"], 1)


if __name__ == "__main__":
    unittest.main()
