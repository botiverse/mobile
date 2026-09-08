from pathlib import Path
import unittest


class OhosBridgeUnknownMethodTest(unittest.TestCase):
    def test_unknown_method_does_not_call_missing_callback(self):
        source = Path(__file__).parents[1] / "compose/ohosApp/entry/src/main/ets/kuikly/modules/KRBridgeModule.ets"
        text = source.read_text(encoding="utf-8")
        self.assertIn("if (callback !== null && callback !== undefined)", text)


if __name__ == "__main__":
    unittest.main()
