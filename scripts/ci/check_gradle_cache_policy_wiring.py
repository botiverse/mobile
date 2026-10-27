#!/usr/bin/env python3
"""CI invariant for the Gradle cache policy wiring (task #786).

Asserts, for every workflow job, that each gradle/actions/setup-gradle step is
preceded (within the same job) by a step using ./.github/actions/gradle-cache-policy,
so no setup-gradle invocation can silently run outside the epoch/quarantine policy.
Also re-validates the two policy files with the same fail-closed rules the runtime
reader enforces, and that the epoch reaches the Gradle build-cache directory: apply.sh exports
RAFT_GRADLE_BUILD_CACHE_EPOCH, compose/settings.gradle.kts applies the settings plugin that
reads it (gradle/actions v6 keys the build-cache entry by file names only, so the epoch must
be in the directory name for a rotation to produce a new entry). Run with --self-test to exercise the checker against fixtures.
"""
import glob
import re
import sys

import yaml

POLICY_ACTION = "./.github/actions/gradle-cache-policy"
SETUP_GRADLE = "gradle/actions/setup-gradle@"
EPOCH_RE = re.compile(r"^[A-Za-z0-9_.-]{1,32}$")
ALLOWLIST = {"dependencies", "transforms"}
BUILD_CACHE_EPOCH_ENV = "RAFT_GRADLE_BUILD_CACHE_EPOCH"
BUILD_CACHE_EPOCH_PLUGIN = "gradle/build-cache-epoch.settings.gradle.kts"
SETTINGS_APPLY_RE = re.compile(
    r'(?m)^apply\(from = "' + re.escape(BUILD_CACHE_EPOCH_PLUGIN) + r'"\)\s*$'
)


def check_workflows(paths):
    errors = []
    for path in sorted(paths):
        doc = yaml.safe_load(open(path))
        for job_name, job in (doc.get("jobs") or {}).items():
            steps = job.get("steps") or []
            policy_seen = False
            for index, step in enumerate(steps):
                uses = step.get("uses") or ""
                # Exact match: a lookalike path (e.g. .../gradle-cache-policy-broken) must not
                # count as wiring.
                if uses == POLICY_ACTION:
                    policy_seen = True
                if uses.startswith(SETUP_GRADLE) and not policy_seen:
                    errors.append(
                        f"{path}: job '{job_name}' step {index} uses {uses} "
                        f"without a preceding {POLICY_ACTION} step"
                    )
    return errors


def check_build_cache_epoch_wiring(apply_path, settings_path, plugin_path):
    errors = []
    try:
        apply_src = open(apply_path).read()
        settings_src = open(settings_path).read()
        plugin_src = open(plugin_path).read()
    except OSError as error:
        return [f"build-cache epoch wiring: unreadable ({error})"]
    if f'echo "{BUILD_CACHE_EPOCH_ENV}=${{epoch}}" >> "${{github_env}}"' not in apply_src:
        errors.append(f"{apply_path}: must export {BUILD_CACHE_EPOCH_ENV}=${{epoch}}")
    if not SETTINGS_APPLY_RE.search(settings_src):
        errors.append(f'{settings_path}: must apply(from = "{BUILD_CACHE_EPOCH_PLUGIN}")')
    if f'environmentVariable("{BUILD_CACHE_EPOCH_ENV}")' not in plugin_src:
        errors.append(f"{plugin_path}: must read {BUILD_CACHE_EPOCH_ENV}")
    if '"caches/build-cache-epoch-$buildCacheEpoch"' not in plugin_src:
        errors.append(f"{plugin_path}: must put the epoch in the build-cache directory name")
    return errors


def check_policy_files(epoch_path, quarantine_path):
    errors = []
    try:
        epoch_lines = open(epoch_path, newline="").read().split("\n")
    except OSError as error:
        return [f"{epoch_path}: unreadable ({error})"]
    # Byte contract shared with apply.sh (mapfile): the final newline is optional, exactly one
    # logical line, and CR never matches EPOCH_RE.
    non_trailing = epoch_lines[:-1] if epoch_lines and epoch_lines[-1] == "" else epoch_lines
    if len(non_trailing) != 1 or not EPOCH_RE.match(non_trailing[0] if non_trailing else ""):
        errors.append(f"{epoch_path}: must be exactly one [A-Za-z0-9_.-]{{1,32}} logical line")
    try:
        seen = set()
        for line in open(quarantine_path, newline=""):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            if stripped != line.rstrip("\n") or stripped not in ALLOWLIST or stripped in seen:
                errors.append(f"{quarantine_path}: invalid line '{line.rstrip()}'")
            seen.add(stripped)
    except OSError as error:
        errors.append(f"{quarantine_path}: unreadable ({error})")
    return errors


def self_test():
    import tempfile, os, textwrap

    good = {"jobs": {"a": {"steps": [
        {"uses": POLICY_ACTION},
        {"uses": "gradle/actions/setup-gradle@v6"},
    ]}}}
    bad = {"jobs": {"a": {"steps": [
        {"uses": "gradle/actions/setup-gradle@v6"},
        {"uses": POLICY_ACTION},
    ]}}}
    with tempfile.TemporaryDirectory() as tmp:
        good_path = os.path.join(tmp, "good.yml")
        bad_path = os.path.join(tmp, "bad.yml")
        yaml.safe_dump(good, open(good_path, "w"))
        yaml.safe_dump(bad, open(bad_path, "w"))
        assert not check_workflows([good_path]), "good workflow must pass"
        assert check_workflows([bad_path]), "policy-after-setup must fail"

        epoch = os.path.join(tmp, "epoch")
        quarantine = os.path.join(tmp, "quarantine")
        open(epoch, "w").write("1\n")
        open(quarantine, "w").write("# none\n")
        assert not check_policy_files(epoch, quarantine)
        open(epoch, "w").write("1\n2\n")
        assert check_policy_files(epoch, quarantine), "two-line epoch must fail"
        open(epoch, "w").write("ok\n")
        open(quarantine, "w").write("not-allowed\n")
        assert check_policy_files(epoch, quarantine), "non-allowlisted bundle must fail"

        # Byte-contract parity with apply.sh: final LF optional, CRLF rejected.
        open(epoch, "w").write("7")
        open(quarantine, "w").write("# c\ndependencies")
        assert not check_policy_files(epoch, quarantine), "no-final-LF epoch and trailing quarantine entry must pass"
        open(epoch, "w").write("1\r\n")
        open(quarantine, "w").write("# c\n")
        assert check_policy_files(epoch, quarantine), "CRLF epoch must fail"
        open(epoch, "w").write("1\n")
        open(quarantine, "w").write("dependencies\r\n")
        assert check_policy_files(epoch, quarantine), "CRLF quarantine must fail"

        # Lookalike policy path must not count as wiring; .yaml workflows must be scanned.
        lookalike = {"jobs": {"a": {"steps": [
            {"uses": POLICY_ACTION + "-broken"},
            {"uses": "gradle/actions/setup-gradle@v6"},
        ]}}}
        lookalike_path = os.path.join(tmp, "lookalike.yaml")
        yaml.safe_dump(lookalike, open(lookalike_path, "w"))
        assert check_workflows([lookalike_path]), "lookalike policy path must not satisfy the invariant"

        # Build-cache epoch wiring: the real files pass; each broken link fails.
        real = (".github/actions/gradle-cache-policy/apply.sh", "compose/settings.gradle.kts",
                "compose/" + BUILD_CACHE_EPOCH_PLUGIN)
        assert not check_build_cache_epoch_wiring(*real), check_build_cache_epoch_wiring(*real)
        sources = [open(path).read() for path in real]
        mutations = [
            (0, f"{BUILD_CACHE_EPOCH_ENV}=${{epoch}}", "OTHER_EPOCH=${epoch}"),
            (1, f'apply(from = "{BUILD_CACHE_EPOCH_PLUGIN}")', f'// apply(from = "{BUILD_CACHE_EPOCH_PLUGIN}")'),
            (2, f'environmentVariable("{BUILD_CACHE_EPOCH_ENV}")', 'environmentVariable("OTHER_EPOCH")'),
            (2, '"caches/build-cache-epoch-$buildCacheEpoch"', '"caches/build-cache-1"'),
        ]
        for which, old, new in mutations:
            assert old in sources[which], old
            paths = []
            for index, source in enumerate(sources):
                mutated = os.path.join(tmp, f"wiring-{index}")
                open(mutated, "w").write(source.replace(old, new) if index == which else source)
                paths.append(mutated)
            assert check_build_cache_epoch_wiring(*paths), f"mutation must fail: {old} -> {new}"
    print("check_gradle_cache_policy_wiring self-test: OK")


def main():
    if "--self-test" in sys.argv:
        self_test()
        return
    workflow_paths = glob.glob(".github/workflows/*.yml") + glob.glob(".github/workflows/*.yaml")
    errors = check_workflows(workflow_paths)
    errors += check_policy_files(".github/gradle-cache-epoch", ".github/gradle-cache-quarantine")
    errors += check_build_cache_epoch_wiring(
        ".github/actions/gradle-cache-policy/apply.sh",
        "compose/settings.gradle.kts",
        "compose/" + BUILD_CACHE_EPOCH_PLUGIN,
    )
    if errors:
        print("\n".join(errors), file=sys.stderr)
        sys.exit(1)
    print("gradle cache policy wiring: all setup-gradle steps covered; policy files valid")


if __name__ == "__main__":
    main()
