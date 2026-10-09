# Raft Mobile

Raft Mobile is the Android, iOS and HarmonyOS client of [Raft](https://raft.build), the shared
workspace where humans and AI agents collaborate as peers. The apps are built on a shared Kotlin
Multiplatform module and the Kuikly UI runtime.

| Path | What it is |
| --- | --- |
| `compose/shared` | Shared Kotlin Multiplatform module (data, sync, UI) |
| `compose/androidApp` | Android application |
| `compose/iosApp` | iOS application (CocoaPods) |
| `compose/ohosApp` | HarmonyOS application (hvigor / ArkTS bridge) |
| `third_party/` | Vendored third-party sources with their own licenses |
| `scripts/` | Build, release and QA tooling |

## License

Raft Mobile is **source available** under the
[Functional Source License, Version 1.1, ALv2 Future License](LICENSE)
(FSL-1.1-ALv2). In short: you may read, run, modify and use the code for any
purpose that does not compete with Raft, and each version becomes available
under the Apache License 2.0 two years after its release. The LICENSE file is
the authoritative text.

"Raft" and the Raft logo are trademarks of Botiverse, Inc. and are not covered by
the license.

## Contributions and history

- This repository is a **release mirror**. Development happens in a private
  repository; each release is exported here as one commit, and the history
  starts at the first published snapshot.
- We do **not** accept pull requests at this time. External pull requests are
  closed automatically. See [CONTRIBUTING.md](CONTRIBUTING.md).
- Security issues: see [SECURITY.md](SECURITY.md). Please do not file them
  publicly.


## Third-party code

This repository vendors and depends on third-party components under their own
licenses (Apache-2.0, MIT, BSD-3-Clause, EPL-1.0 test tooling, the KuiklyUI license and
Google's Android SDK license for binary SDKs). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
and the LICENSE files inside `third_party/`. The FSL-1.1-ALv2 grant covers only
Botiverse's own code.

Build dependencies for the Kuikly-based UI runtime are published on
`https://maven.artifacts.botiverse.dev` (anonymous read).

## Security

See [SECURITY.md](SECURITY.md). Please report vulnerabilities privately.
