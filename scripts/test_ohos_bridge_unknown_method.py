from pathlib import Path
import re
import subprocess
import unittest


class OhosBridgeUnknownMethodTest(unittest.TestCase):
    def test_unknown_method_does_not_call_missing_callback(self):
        source = Path(__file__).parents[1] / "compose/ohosApp/entry/src/main/ets/kuikly/modules/KRBridgeModule.ets"
        dispatcher = source.read_text(encoding="utf-8").split("  call(", 1)[1].split("  onDestroy(", 1)[0]
        fallback = re.search(r"default: \{(.*?)\n        break;", dispatcher, re.S)
        self.assertIsNotNone(fallback)
        program = "const reply = new Function('callback', " + repr(fallback[1]) + ");\n" + """
const assert = require('node:assert/strict');
assert.doesNotThrow(() => reply(null));
assert.doesNotThrow(() => reply(undefined));
const received = [];
reply((value) => received.push(value));
assert.deepEqual(received, [{code: -1, message: 'Method not implemented'}]);
"""
        subprocess.run(["node", "-e", program], check=True, capture_output=True, text=True)


if __name__ == "__main__":
    unittest.main()
