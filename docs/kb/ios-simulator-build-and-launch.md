# Building and launching the iOS app on a simulator

Owner: 辣条 · Verified at: 2026-10-18 · Status: current

How to get `build.raft.app` running on an iOS simulator from a macOS workstation, and the three traps that make a clean-looking failure look like a code problem.

**Evidence level.** Everything below was measured on one machine: macOS arm64, Xcode 26.1, an iOS 26.1 simulator, on a host under heavy load (~36) from other concurrent builds. It was **not** reproduced on CI. Treat the OOM section as "observed here, likely applies on CI, unverified there".

## Prerequisites

- Xcode 26.1 with an installed iOS simulator runtime (the run below used an iPhone 17 / iOS 26.1 device).
- CocoaPods (1.17 was in use).
- JDK for Gradle; the Kotlin/Native link needs markedly more heap than the default (see the OOM trap).

## The chain

1. **Generate the framework before touching CocoaPods.**

   ```bash
   cd compose && ./gradlew :shared:generateDummyFramework
   ```

   `--no-daemon` is optional; the measured run reused the daemon (~1m).

   Skipping this makes `pod install` fail inside `shared.podspec` with `framework doesn't exist` — the message points at the podspec, not at the missing step, which is why it reads like a broken spec.

2. **Install pods.**

   ```bash
   cd compose/iosApp && pod install
   ```

   The run that produced this note resolved the renderer at `2.28.0-raft.13-2.1.21` (release-set name `2.28.0-raft.13`) and 9 pods.

3. **Build for the simulator without signing.**

   ```bash
   xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
     -sdk iphonesimulator -destination 'id=<SIMULATOR_UDID>' \
     CODE_SIGNING_ALLOWED=NO build
   ```

   Simulator builds need no signing material; `CODE_SIGNING_ALLOWED=NO` keeps the build out of provisioning entirely. Get `<SIMULATOR_UDID>` from `xcrun simctl list devices available`.

4. **Install and launch.**

   ```bash
   xcrun simctl boot <SIMULATOR_UDID>
   xcrun simctl install <SIMULATOR_UDID> <path to .app>
   xcrun simctl launch <SIMULATOR_UDID> build.raft.app
   ```

## Trap 1: the link stage OOMs on the default heap

`:shared:linkPodDebugFrameworkIosSimulatorArm64` dies with `Java heap space` (`Kotlin/Native`), while every preceding compile step succeeds. This is the single most misleading failure in the chain: the build looks like it got stuck on a Kotlin/Native compile error, and it is actually an out-of-memory during linking.

What was observed:

- default heap → link fails with `Java heap space`
- `kotlin.native.jvmArgs=-Xmx8g` → link succeeds (8m28s on the loaded host), `xcodebuild` reports `BUILD SUCCEEDED`

Two things this note does **not** claim: that 8g is a minimum (no bisection was done — it is an amount that worked), and that CI hits the same wall (untested there).

## Trap 2: do not "fix" it by committing the heap setting

`compose/gradle.properties` is shared. Adding `kotlin.native.jvmArgs=-Xmx8g` there changes the link memory behaviour of every developer **and of CI**; it is a build-behaviour change, not a local convenience. The correct handling is a local override that stays out of the commit — and the reason to write that down is that the shortcut (`git add` the line that just made the build pass) is the most natural thing to do at the moment of the fix.

If the setting is ever wanted for real, it belongs in a reviewed change with CI evidence, not as a drive-by line in a feature PR.

## Trap 3: a launched app is not a verified feature

Reaching the login screen proves the toolchain: framework generation, pod resolution, Kotlin/Native link, signing bypass, simulator install and process launch. It proves nothing about the behaviour under test. Keep the two apart in reports — "simulator build and launch work" and "the feature behaves correctly on iOS" are different claims, and the second one needs a fixture run with numbers. See [mobile-visual-evidence-ceiling.md](mobile-visual-evidence-ceiling.md) for what each class of visual evidence can carry.

The same distinction generalises beyond builds: **availability is not verification.** A seat that can run the toolchain is not a result; a name in a channel's member list is not a reachable reviewer. In both cases the cheap signal (the toolchain works, the name is present) is easy to mistake for the expensive one (a measurement exists, a person answers).

## Related

- [mobile-visual-evidence-ceiling.md](mobile-visual-evidence-ceiling.md) — which carrier supports which visual claim.
- [kuikly-release-pin-recipe.md](kuikly-release-pin-recipe.md) — the framework this app links against, and how its pin is bumped.
