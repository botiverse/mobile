# Temporary Hands SDK archive

`hands.har` is `@botiverse/hands` 0.3.5, temporarily vendored while OHPM reviews this release. The entry manifest uses a local file dependency so builds do not require public registry approval.

Source: https://github.com/botiverse/hands/commit/503a9d83fa554bf9d1996454f5bf75a7b99d35cd
Build and submission: https://github.com/botiverse/hands/actions/runs/36731513504
Workflow artifact: `botiverse-hands-ohos-0.3.5.har`, ID `11105656939`.
SHA256: `fc9a5b30ce103084389bc17b5add6b1f347a733513d608259e6a0eef599d29cc`.
License: MIT (see `package/LICENSE` inside the archive).

After registry approval, switch the entry dependency back to exact `0.3.5`, regenerate its lockfile with OHPM, and remove this temporary HAR. Run `scripts/check_ohos_hands_dependency.py` against the corresponding pinned bytes before handoff.
