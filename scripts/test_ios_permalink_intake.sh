#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
python3 - "$test_dir/main.swift" <<'PYTHON'
import re, pathlib, sys
files = [pathlib.Path('compose/iosApp/iosAppTests/SlockIosPermalinkIntakeTests.swift'),
         pathlib.Path('scripts/tests/SlockIosPermalinkSceneHostTests.swift')]
classes = [path.stem for path in files]
body = 'import Foundation\nimport XCTest\nlet suite = XCTestSuite(name: "iOS permalink transport")\n'
body += '\n'.join('suite.addTest(XCTestSuite(forTestCaseClass: %s.self))' % name for name in classes)
body += '\nsuite.run()\nguard let run = suite.testRun, run.executionCount == 12 else { fatalError("Expected all 12 tests to execute") }\nexit(run.hasSucceeded ? 0 : 1)\n'
pathlib.Path(sys.argv[1]).write_text(body)
PYTHON
frameworks="$(xcode-select -p)/Platforms/MacOSX.platform/Developer/Library/Frameworks"
test_libs="$(xcode-select -p)/Platforms/MacOSX.platform/Developer/usr/lib"
private_frameworks="$(xcode-select -p)/Platforms/MacOSX.platform/Developer/Library/PrivateFrameworks"
xcrun swiftc -Xlinker -rpath -Xlinker "$private_frameworks" -I "$test_libs" -L "$test_libs" -Xlinker -rpath -Xlinker "$test_libs" -F "$frameworks" -Xlinker -rpath -Xlinker "$frameworks" -module-cache-path "$test_dir/cache" \
  compose/iosApp/iosApp/SlockIosPermalinkIntake.swift \
  compose/iosApp/iosAppTests/SlockIosPermalinkIntakeTests.swift \
  compose/iosApp/iosApp/SlockIosPermalinkSceneHost.swift \
  scripts/tests/SlockIosPermalinkSceneHostTests.swift \
  "$test_dir/main.swift" -o "$test_dir/tests"
"$test_dir/tests"
