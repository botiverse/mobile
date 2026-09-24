# iOS visual capture on the shared Mac runners

`Visual Testing / iOS capture` selects the `jacky-macmini` runner group and
`self-hosted`, `macOS`, `ARM64` labels. GitHub requires both group access and all
labels to match ([routing documentation](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/use-in-a-workflow)).
The group must allow `botiverse/mobile`. The other visual jobs stay on Linux.

The runner account needs an initialized Xcode installation (`xcodebuild -version`),
an available iOS Simulator runtime with the iPhone 14 device type, CocoaPods
(`pod`), and Python 3 on PATH. The workflow installs JDK 17 and retains the
existing Gradle setup/cache policy and immutable Kuikly release verification.
It does not download Xcode or change the machine's selected developer directory.

Every job creates a temporary directory under `RUNNER_TEMP` and a new simulator.
The captured UDID is the only device it boots, installs into, or deletes. Build
DerivedData, the manifest, case list, and console log belong to that directory.
An `always()` step shuts down/deletes the owned simulator and removes the temporary
directory. A failed delete fails cleanup and retains its ownership record.
Runner loss or a force-killed process can prevent cleanup; recover using the
exact `simulator-udid` file under that job's `ios-visual.*` directory. Never use
`simctl delete all` or shutdown another user's simulator.

The first real execution must confirm that GitHub assigns a runner from this
group, the environment preflight passes, the app builds, actual screenshots and
metadata upload, and cleanup completes. A Linux scripts test or a queued job is
not evidence of a working Mac environment. Keep first-attempt failures visible;
switching runners does not establish that earlier iOS build failures are fixed.
