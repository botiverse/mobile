# iOS visual capture on the shared Mac runners

`Visual Testing / iOS capture` selects the `jacky-macmini` runner group and
`self-hosted`, `macOS`, `ARM64` labels. GitHub requires both group access and all
labels to match ([routing documentation](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/use-in-a-workflow)).
The group must allow `botiverse/mobile`. The other visual jobs stay on Linux.

The runner account needs an initialized Xcode installation (`xcodebuild -version`),
an available iOS Simulator runtime with the iPhone 14 device type, CocoaPods
(`pod`), and Python 3 on PATH. The workflow installs JDK 17 and retains the
existing Gradle setup/cache policy and immutable Kuikly release verification.
It selects a full installed Xcode through job-local `DEVELOPER_DIR`, without
changing the machine-wide developer directory or downloading Xcode.

Every job creates a temporary directory under `RUNNER_TEMP` and a new simulator.
The captured UDID is the only device it boots, installs into, or deletes. Build
DerivedData, the manifest, case list, and console log belong to that directory.
An `always()` step shuts down/deletes the owned simulator and removes the temporary
directory. A failed delete fails cleanup and writes the exact device UUID,
runner name, and run identity to `ios-diagnostics/simulator-cleanup-failure.json`
in the uploaded results artifact. Use that receipt for recovery: `RUNNER_TEMP`
is cleared at job end, so the temporary ownership file is not durable evidence.
Runner loss or a force-killed process can prevent both cleanup and artifact
upload; inspect the affected runner before attempting recovery. Never use
`simctl delete all` or shutdown another user's simulator.

The first real execution must confirm that GitHub assigns a runner from this
group, the environment preflight passes, the app builds, actual screenshots and
metadata upload, and cleanup completes. A Linux scripts test or a queued job is
not evidence of a working Mac environment. Keep first-attempt failures visible;
switching runners does not establish that earlier iOS build failures are fixed.

## Bootstrap the first Mac

Run **iOS runner bootstrap** manually on `main`. It targets the confirmed
`mac-mini-1` label within `jacky-macmini`; it does not run on PRs or a schedule.
A GitHub-hosted preflight rejects non-main dispatches before the Mac job; that
job also requires the main ref and checks out the dispatch commit. This is a
workflow guard, not a replacement for repository and runner-group access control.
An optional `xcode_app_path` accepts only `/Applications/Xcode*.app`, with no
symlink or traversal components. The same restriction applies to inherited
`DEVELOPER_DIR`; workspace or temporary apps cannot be selected.
Otherwise selection preserves a valid job developer directory, prefers
`/Applications/Xcode.app`, or accepts exactly one other installed Xcode app.
Missing or ambiguous full Xcode fails before installing dependencies.

The workflow sets up JDK 17, installs missing Python/CocoaPods with existing
Homebrew, and downloads an iOS runtime only when none is available. Existing
Homebrew formulae are not explicitly upgraded. If Apple's first-launch components
are pending, it uses noninteractive sudo for `xcodebuild -runFirstLaunch`; lack of
permission fails the job. It never prompts for a password or accepts an Apple
license. Full Xcode installation, Apple sign-in/license acceptance, and initial
Homebrew installation remain machine-owner prerequisites.

The final readiness check creates, boots, and deletes a fresh iPhone 14 using the
same helper as capture. A successful bootstrap proves those prerequisites, not
an app build or screenshot. The subsequent capture job selects Xcode again so a
machine-wide CommandLineTools setting cannot silently undo the bootstrap choice.
For nonstandard installations, arrange a direct `/Applications/Xcode*.app`
installation before scheduled capture; an explicit bootstrap input affects only
that bootstrap job.

Runtime installation uses [Apple's documented component commands](https://developer.apple.com/documentation/xcode/downloading-and-installing-additional-xcode-components).
Homebrew prerequisites follow the [installation documentation](https://docs.brew.sh/Installation).
