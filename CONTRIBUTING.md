# Contributing

Thank you for your interest in Raft Mobile (the Android, iOS and HarmonyOS
clients of Raft). External pull requests are welcome. Please read this page
before opening one.

## Before you start

- For anything larger than a small fix, open an issue first so we can agree on
  the direction before you spend time on it.
- Security issues must **not** be filed publicly. See [SECURITY.md](SECURITY.md)
  for the private reporting channels.
- The build depends on Kuikly runtime artifacts published on
  `https://maven.artifacts.botiverse.dev` (anonymous read); see the README for
  how the three platforms are built and tested.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Keep each pull request focused on one change, with tests where the area has
   them. Describe what changed and how you verified it.
3. Sign off every commit (`git commit -s`). By signing off you certify the
   [Developer Certificate of Origin](https://developercertificate.org/) for
   your contribution. No separate contributor license agreement is required.
4. Continuous integration for pull requests from forks runs only after a
   maintainer approves the workflow run; jobs that need repository secrets
   (signing, release, internal tooling) are skipped for fork pull requests by
   design. A maintainer may ask for changes before approving the run.
5. A maintainer reviews and merges. Pull requests that stay inactive for a long
   time may be closed; feel free to reopen when you pick them up again.

## License of contributions

This repository is licensed under [FSL-1.1-ALv2](LICENSE). By submitting a
contribution you agree that it is provided under the same license, and that
Botiverse, Inc. may distribute it under that license and the Apache License 2.0
change license described in the LICENSE file.

## Issues

Bug reports and feature requests are welcome as GitHub Issues. Please include
the platform (Android / iOS / HarmonyOS), app version and build, and steps to
reproduce. Product feedback and support requests can also go through the
in-app feedback tool or the support channels listed on raft.build.
