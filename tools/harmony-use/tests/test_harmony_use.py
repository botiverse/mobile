from __future__ import annotations

import unittest

from harmony_use.hdc import direction_to_swipe, quote_shell
from harmony_use.hilog import TLS_NATIVE_STUB, analyze_hilog_text
from harmony_use.config import HarmonyConfig
from harmony_use.remote import remote_env_prefix, shell_join


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


if __name__ == "__main__":
    unittest.main()
