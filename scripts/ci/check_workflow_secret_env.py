#!/usr/bin/env python3
"""Fail when a workflow can put credentials into $GITHUB_ENV or $GITHUB_OUTPUT.

A value written to $GITHUB_ENV is in the environment of every later step of the
job, and every process those steps start (actions, background harnesses, tools),
for the rest of the job. A value written to $GITHUB_OUTPUT can be read by any
later step or job. Credentials go only to the one step that needs them, through
that step's own `env:` from `secrets.*` (Argus, after #4806).

Static and deliberately cheap: it reads the text of every workflow and composite
action (no YAML library) and splits it into steps (`- ` items under `steps:`).
"Credential-looking" = an identifier containing PASSWORD, EMAIL, TOKEN or SECRET
(any case). "Secret" = secrets.X / secrets['X'] / secrets["X"] in any case and
spacing, or toJSON(secrets).

  1. Write lines. A line that redirects / tees / opens $GITHUB_ENV or
     $GITHUB_OUTPUT and names a credential-looking identifier or a secret fails
     (workflows, composite actions and scripts).
  2. Steps (fail-closed). A step whose text mentions GITHUB_ENV or GITHUB_OUTPUT
     in any form (redirection, `f=$GITHUB_ENV`, os.environ['GITHUB_ENV'],
     process.env.GITHUB_OUTPUT, ...), or that names a repo script (scripts/**,
     .github/actions/**) whose text mentions them, AND reads any secret or names a
     credential-looking identifier, fails unless listed in STEP_ALLOWLIST with a
     reason. A listed entry that matches no step any more fails too.
  3. QA scope (pinned). QA secrets may appear only in the step-level env: of the
     steps in QA_SECRET_STEPS, with exactly the pinned keys and expressions and
     the pinned step `if:`. So an extra alias, another spelling, a changed
     staging/production pair, a removed/changed bootstrap gate, a renamed step, a
     job/workflow-level env:, a with:, a run: body or an unlisted workflow all
     fail; every listed workflow must exist and every listed step must match.
  4. Scripts (fail-closed, whole file). A file under scripts/ or .github/actions/
     that mentions GITHUB_ENV/GITHUB_OUTPUT in any form and names a
     credential-looking identifier anywhere fails unless listed in
     SCRIPT_ALLOWLIST. This covers a read on one line and a write on another,
     through any alias of the file, as long as the credential's name appears.

Allow-list identity and content (rules 2-4). Entries bind to workflow + job id +
step name (or "id:<id>"); an identity must match exactly one step, so a second
step cannot borrow an allowed name. STEP_ALLOWLIST and SCRIPT_ALLOWLIST entries
also pin the sha256 of the reviewed step source / script file: any edit fails
until a person re-reviews it and re-pins the digest. Changing an allow-list entry
(or this file) is therefore the human-review boundary. This file itself is skipped
by path (it names credentials in its patterns and fake fixtures).

Not enforced (regex, not dataflow): a script that writes those files and gets a
credential under a name that does not look like one (e.g. os.environ[argv[1]]),
or builds the GITHUB_ENV name at run time; scripts reached only transitively
(imported or called by another script); third-party actions' own code. Nor does
any of this isolate a credential from other processes inside the step that
legitimately receives it: it narrows exposure to those steps.

Usage: check_workflow_secret_env.py [--root DIR] | --self-test
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import sys
import tempfile
from pathlib import Path

SENSITIVE = re.compile(r"PASSWORD|EMAIL|TOKEN|SECRET", re.IGNORECASE)
TARGET = r"GITHUB_(?:ENV|OUTPUT)"
MENTIONS_TARGET = re.compile(r"\b" + TARGET + r"\b")
# A shell redirection / tee / language-level open of the env or output file.
WRITE_LINE = re.compile(
    r"(>>?\s*[\"']?\$\{?" + TARGET + r"|tee\s+(?:-a\s+)?[\"']?\$\{?" + TARGET
    + r"|environ\s*\[\s*[\"']" + TARGET + r"[\"']\s*\]|environ\.get\(\s*[\"']" + TARGET
    + r"|process\.env\." + TARGET + r"|process\.env\[\s*[\"']" + TARGET + r"|getenv\(\s*[\"']" + TARGET + r")"
)
# Any secret: secrets.X, secrets . X, secrets['X'], secrets[ "X" ], toJSON(secrets); any case.
ANY_SECRET = re.compile(
    r"\bsecrets\s*(?:\.\s*[A-Za-z_][\w-]*|\[\s*['\"][^'\"]*['\"]\s*\])|\btojson\s*\(\s*secrets\s*\)", re.IGNORECASE)
QA_SECRET = re.compile(
    r"\bsecrets\s*(?:\.\s*qa_[\w-]*|\[\s*['\"]\s*qa_[^'\"]*['\"]\s*\])|\btojson\s*\(\s*secrets\s*\)", re.IGNORECASE)
IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
STEP_ITEM = re.compile(r"^\s*-\s")
NAME_LINE = re.compile(r"^\s*(?:-\s+)?name\s*:\s*(.+?)\s*$")
ID_LINE = re.compile(r"^\s*(?:-\s+)?id\s*:\s*(.+?)\s*$")
USES_LINE = re.compile(r"^\s*(?:-\s+)?uses\s*:\s*(.+?)\s*$")

# Steps that mention $GITHUB_ENV/$GITHUB_OUTPUT and also read a secret or name a
# credential-looking variable, reviewed: none of them writes a credential. Value: (reason, sha256
# of the step source as reviewed); any edit to the step fails until it is re-reviewed and
# re-pinned (the digest is printed in the failure). Key:
# (workflow file name, job id, step name or "id:<id>" for an unnamed step). Each key must match
# exactly one step: a second step with the same identity fails, so a name cannot be borrowed.
STEP_ALLOWLIST = {
    ("android-realtime-e2e.yml", "e2e", "Find and download the Alpha APK"):
        ("GH_TOKEN is github.token for `gh`; outputs are run id, artifact name, APK path, package, sha256, "
         "the artifact's source sha and the compare status of an optional required commit (no credentials)",
         "86c3359962a8a808512b06ab5edf52f76a83aa770936d1b4aeda2a637d17c674"),
    ("release-auto-login.yml", "sign-in", "Download the release APK"):
        ("GH_TOKEN is github.token for `gh run download`; outputs are the APK path and version",
         "c206125a3ef184fbf55a29ad8040936ef304b169e08f11625268f9176c69c822"),
    ("github-release.yml", "tag", "Resolve version and commit"):
        ("GH_TOKEN is github.token for `gh`; outputs are version and commit",
         "718ad690a3522aea41bfe0c251627b17eb18786c3cb267bc8b37eaf696fe26de"),
    ("android-update-e2e.yml", "plan", "id:p"):
        ("HANDS_AUTH_TOKEN is used by the Hands CLI; outputs are build flags, source sha and the versionCode plan",
         "8308184b7cd82d02f59084aeebd61dafc79f8c83200a03d755b6702343dfbb06"),
    ("android-release.yml", "build_android_release", "Sign + align APK"):
        ("signing secrets go to apksigner via files/env; outputs are paths, hashes, SDK levels and signer cert digests",
         "10a0ac8e034041a4ab456d71440aae31342677458c25fe99bd7cf82c5f68f9cf"),
    ("android-release.yml", "build_android_release", "Sign and verify AAB"):
        ("signing secrets go to the signer; outputs are the AAB path and the upload cert sha256",
         "7bb89635759036daff8b9c4e16e1306329f81972fefec6faea640f75c53844bf"),
    ("android-release.yml", "build_android_release", "Upload immutable AAB + APK to Hands"):
        ("bearer tokens are used by the Hands CLI; the only output is build_id parsed from its log",
         "403f5d26e87c5aaa9301b32af781143cba4eac3630672de6be9b4659892b345b"),
    ("android-release.yml", "create_hands_draft", "Create share page"):
        ("HANDS_BEARER_TOKEN authenticates the API call; the only output is share_url",
         "451625d4a428e1d7826e3b2e213395611f5664b93aa0cb644e171eaac7565d17"),
    ("ios-release.yml", "build_ios_release", "Import Apple signing assets"):
        ("passwords go to `security` for a temp keychain; outputs are profile uuid/name/team and keychain path",
         "0a60acff7a5fd1b36afaafcaa0157ebbff6638e059f2aee1046f133197b40121"),
    ("qa-acceptance.yml", "acceptance", "Claim Stamp run case and resolve auth mode"):
        ("STAMP_DIRECT_ANDROID_APK is an input-derived boolean. The existing token and bootstrap boolean env/output writes are unchanged; "
         "#216 adds only the claimed_case output: the claim's case_id from the spec, written only when it matches "
         "^[a-z0-9][a-z0-9-]{0,63}$ (else empty), no credential. "
         "Skipped in the release-prelogin lane, which claims through testbed_release_settings.mjs without $GITHUB_ENV/$GITHUB_OUTPUT.",
         "be5e416c13507ba41f0329e4cb683249f0ca368648635138c4e87e1b29ae6570"),
    ("qa-acceptance-ios.yml", "acceptance", "Claim Stamp run case and resolve auth mode"):
        ("STAMP_TOKEN is used by stamp_report.sh; writes only bootstrap_required / STAMP_BOOTSTRAP_REQUIRED (true|false)",
         "862107c03608e2090a1a91a7ad9d2dd0da85bf2f228d379ca90e183917d096a8"),
}

# Scope (workflow -> job id -> step name -> pin): the QA account secrets may be read only in the
# step-level env: of these steps (each identity must match exactly one step), under
# exactly these keys and values, and with exactly this step `if:` (None = no `if:`). Pinning the
# whole expression keeps the staging/production branch pair and the bootstrap gate under review:
# an extra alias, a dropped or changed gate, or another spelling of the same secret all fail.
LEGACY = "(inputs.stamp_run_case_id == '' || steps.stamp-claim.outputs.bootstrap_required != 'true')"
LEGACY_IF = "${{ inputs.stamp_run_case_id == '' || steps.stamp-claim.outputs.bootstrap_required != 'true' }}"
RECEIPT_IF = "${{ inputs.stamp_run_case_id != '' && steps.stamp-claim.outputs.bootstrap_required != 'true' }}"
# Android qa-acceptance.yml: the Testbed release settings lane (login_mode=release-prelogin) gets the
# QA account ONLY as QA_RELEASE_LOGIN_* on the emulator step (scripts/qa/release_settings_login.sh);
# every legacy password step is off in that lane. The claimed Testbed case login-email-password (#216)
# uses dedicated QA_LOGIN_ACCEPTANCE_* secrets in preflight and the emulator step with zero fallback
# to QA_TEST_* and skips legacy receipt freeze and media fixture seeding.
NOT_RELEASE = "inputs.login_mode != 'release-prelogin'"
NOT_LOGIN_ACCEPTANCE = "steps.stamp-claim.outputs.claimed_case != 'login-email-password'"
IS_LOGIN_ACCEPTANCE = "steps.stamp-claim.outputs.claimed_case == 'login-email-password'"
ANDROID_LEGACY_IF = "${{ %s && %s }}" % (NOT_RELEASE, LEGACY)
ANDROID_SEED_IF = "${{ %s && %s && %s }}" % (NOT_RELEASE, LEGACY, NOT_LOGIN_ACCEPTANCE)
ANDROID_LOGIN_ACCEPTANCE_IF = "${{ %s && %s && %s }}" % (NOT_RELEASE, LEGACY, IS_LOGIN_ACCEPTANCE)
ANDROID_RECEIPT_IF = "${{ inputs.stamp_run_case_id != '' && %s && steps.stamp-claim.outputs.bootstrap_required != 'true' && %s }}" % (NOT_RELEASE, NOT_LOGIN_ACCEPTANCE)


def _plain(*keys: str) -> dict[str, str]:
    return {k: "${{ secrets.%s }}" % k for k in keys}


def _legacy(*keys: str) -> dict[str, str]:
    return {k: "${{ %s && secrets.%s || '' }}" % (LEGACY, k) for k in keys}


def _android_preflight_env() -> dict[str, str]:
    return {
        **{k: "${{ %s && secrets.%s || '' }}" % (NOT_LOGIN_ACCEPTANCE, k) for k in ("QA_TEST_EMAIL", "QA_TEST_PASSWORD")},
        **{k: "${{ %s && secrets.%s || '' }}" % (IS_LOGIN_ACCEPTANCE, k) for k in ("QA_LOGIN_ACCEPTANCE_EMAIL", "QA_LOGIN_ACCEPTANCE_PASSWORD")},
    }


def _android_agent_env() -> dict[str, str]:
    # The release lane gets nothing here: its account goes through "Stage release login credential".
    # login-email-password (#216) also gets nothing here: its dedicated account goes through
    # "Stage login acceptance credential" (0600 file consumed by login_phase.mjs only).
    return {k: "${{ %s && %s && %s && secrets.%s || '' }}" % (NOT_RELEASE, LEGACY, NOT_LOGIN_ACCEPTANCE, k) for k in ("QA_TEST_EMAIL", "QA_TEST_PASSWORD")}


def _android_evidence_gate_env() -> dict[str, str]:
    return {k: "${{ %s && secrets.%s || '' }}" % (IS_LOGIN_ACCEPTANCE, k) for k in ("QA_LOGIN_ACCEPTANCE_EMAIL", "QA_LOGIN_ACCEPTANCE_PASSWORD")}


def _env_pair(selector: str) -> dict[str, str]:
    return {f"QA_LOGIN_{kind}": "${{ (%s == 'staging' && secrets.QA_STAGING_TEST_%s) || secrets.QA_TEST_%s }}" % (selector, kind, kind)
            for kind in ("EMAIL", "PASSWORD")}


PREFLIGHT = "Preflight — QA account must not see the production server"
QA_SECRET_STEPS: dict[str, dict[str, dict[str, dict]]] = {
    "release-auto-login.yml": {"old-cli-task": {
        "Old CLI task": {"if": None, "env": _env_pair("inputs.environment")},
    }, "sign-in": {
        "Check the QA credentials are configured": {"if": None, "env": _env_pair("needs.plan.outputs.environment")},
        "Sign in on the emulator": {"if": None, "env": _env_pair("needs.plan.outputs.environment")},
    }},
    "android-realtime-e2e.yml": {"e2e": {
        PREFLIGHT: {"if": None, "env": _plain("QA_TEST_EMAIL", "QA_TEST_PASSWORD")},
        "Run the scenario on the emulator": {"if": None, "env": _plain("QA_TEST_EMAIL", "QA_TEST_PASSWORD")},
    }},
    "android-update-e2e.yml": {"e2e": {
        PREFLIGHT: {"if": None, "env": _plain("QA_TEST_EMAIL", "QA_TEST_PASSWORD")},
        "Run update e2e on the emulator": {"if": None, "env": _plain("QA_TEST_EMAIL", "QA_TEST_PASSWORD")},
    }},
    "qa-acceptance.yml": {"acceptance": {
        PREFLIGHT: {"if": ANDROID_LEGACY_IF, "env": _android_preflight_env()},
        "Freeze legacy credential identity receipt": {"if": ANDROID_RECEIPT_IF, "env": _plain("QA_TEST_EMAIL")},
        "Seed media fixtures (image + workspace)": {"if": ANDROID_SEED_IF, "env": _plain("QA_TEST_EMAIL", "QA_TEST_PASSWORD")},
        "Run acceptance agent": {"if": None, "env": _android_agent_env()},
        # Release settings lane: one 0600 file read (and removed) by release_auto_login.mjs only.
        "Stage release login credential": {"if": "${{ inputs.stamp_run_case_id != '' && inputs.login_mode == 'release-prelogin' }}",
                                           "env": _plain("QA_TEST_EMAIL", "QA_TEST_PASSWORD")},
        # Login acceptance (#216): one 0600 file read (and removed) by login_phase.mjs only.
        "Stage login acceptance credential": {"if": ANDROID_LOGIN_ACCEPTANCE_IF,
                                              "env": _plain("QA_LOGIN_ACCEPTANCE_EMAIL", "QA_LOGIN_ACCEPTANCE_PASSWORD")},
        # Dedicated post-emulator evidence gate for login-email-password (emulator step has no QA_LOGIN_ACCEPTANCE_*).
        "Secure evidence gate": {"if": "always()", "env": _android_evidence_gate_env()},
    }},
    "qa-acceptance-ios.yml": {"acceptance": {
        PREFLIGHT: {"if": LEGACY_IF, "env": _plain("QA_TEST_EMAIL", "QA_TEST_PASSWORD")},
        "Freeze legacy credential identity receipt": {"if": RECEIPT_IF, "env": _plain("QA_TEST_EMAIL")},
        # #222 re-pin: "Run acceptance agent" and "Run deterministic repro" no longer get the QA
        # account. iOS has no safe way to type a secret (password login fails closed) and the
        # deterministic repro never signs in, so QA secrets in either step now fail.
        # Pre-existing, unchanged by #4817: the reporter reads the email on every path.
        "Report to Stamp": {"if": "always()", "env": _plain("QA_TEST_EMAIL")},
    }},
    "ios-simulator-app.yml": {"build-app": {
        "Sign in and screenshot home": {"if": "${{ inputs.home_screenshot }}", "env": _plain("QA_TEST_EMAIL", "QA_TEST_PASSWORD")},
    }},
}

# Script files (scripts/**, .github/actions/**) that mention GITHUB_ENV/GITHUB_OUTPUT and also name
# a credential-looking identifier, reviewed. Value: (reason, sha256 of the file as reviewed); an
# edit fails until re-reviewed and re-pinned. Any other such file fails (rule 4).
SCRIPT_ALLOWLIST = {
    ".github/actions/gradle-cache-policy/apply.sh": ("'token' is an error message about the epoch value",
        "b2f89c03f02fdcc90c78d5d780457a5ff93184a4bfbcc5e4f2adaa2ac385f7b0"),
}
# The checker itself names credentials in its patterns and fake fixtures. It cannot pin its own
# digest, so it is skipped by path; any change to it is itself the reviewed change.
SELF = "scripts/ci/check_workflow_secret_env.py"
SCRIPT_REF = re.compile(r"((?:scripts|\.github/actions)/[\w./-]+\.(?:sh|bash|py|mjs|cjs|js|ts))")
SCRIPT_EXTS = {".sh", ".bash", ".py", ".mjs", ".cjs", ".js", ".ts"}


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def step_digest(lines: list[str], start: int, end: int) -> str:
    """Digest of a step's source (comment lines dropped, trailing spaces trimmed)."""
    return sha256("\n".join(l.rstrip() for l in lines[start:end] if l.strip() and not is_comment(l)))


def pinned(value) -> tuple[str, str | None]:
    """(reason, digest) of an allow-list value; a bare reason has no digest and never matches."""
    return (value[0], value[1]) if isinstance(value, tuple) else (value, None)


def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip())


def is_comment(line: str) -> bool:
    return line.lstrip().startswith("#")


def sensitive_idents(text: str) -> list[str]:
    return [w for w in IDENT.findall(text) if SENSITIVE.search(w) and not re.fullmatch(TARGET, w)]


def parent_of(lines: list[str], i: int) -> int | None:
    return next((j for j in range(i - 1, -1, -1) if lines[j].strip() and not is_comment(lines[j])
                 and indent_of(lines[j]) < indent_of(lines[i])), None)


def steps_of(lines: list[str]) -> list[tuple[int, int, str, str]]:
    """(start, end, label, job id) of every `- ` item directly under a `steps:` key. The label is
    the step name, else "id:<id>", else "uses:<action>"; the job id is the key that owns `steps:`."""
    out = []
    for i, line in enumerate(lines):
        if not STEP_ITEM.match(line) or is_comment(line):
            continue
        p = parent_of(lines, i)
        if p is None or lines[p].strip() != "steps:":
            continue
        end = next((j for j in range(i + 1, len(lines)) if lines[j].strip() and not is_comment(lines[j])
                    and indent_of(lines[j]) <= indent_of(line)), len(lines))
        own = [l for l in lines[i:end] if indent_of(l) <= indent_of(line) + 2 and not is_comment(l)]
        label = None
        for pattern, prefix in ((NAME_LINE, ""), (ID_LINE, "id:"), (USES_LINE, "uses:")):
            m = next((m for l in own if (m := pattern.match(l))), None)
            if m:
                label = prefix + m.group(1).strip("'\"")
                break
        owner = parent_of(lines, p)
        job = lines[owner].strip().rstrip(":").strip("'\"") if owner is not None else ""
        out.append((i, end, label or f"line {i + 1}", job))
    return out


def check_write_lines(path: str, lines: list[str]) -> list[str]:
    errors = []
    for i, line in enumerate(lines):
        if is_comment(line) or not WRITE_LINE.search(line):
            continue
        names = sensitive_idents(line)
        if names or ANY_SECRET.search(line):
            errors.append(f"{path}:{i + 1}: writes credential-looking {', '.join(sorted(set(names))) or 'secret'} into $GITHUB_ENV/$GITHUB_OUTPUT")
    return errors


def step_text(lines: list[str], start: int, end: int) -> str:
    return "\n".join(l for l in lines[start:end] if not is_comment(l) and not NAME_LINE.match(l))


def identity_counts(path: str, lines: list[str]) -> dict[tuple[str, str, str], int]:
    counts: dict[tuple[str, str, str], int] = {}
    for _, _, label, job in steps_of(lines):
        key = (Path(path).name, job, label)
        counts[key] = counts.get(key, 0) + 1
    return counts


def check_steps(path: str, lines: list[str], allow: dict[tuple[str, str, str], str],
                root: Path | None = None, script_allow: dict[str, str] = SCRIPT_ALLOWLIST) -> tuple[list[str], set[tuple[str, str, str]]]:
    """Rule 2: a step that touches $GITHUB_ENV/$GITHUB_OUTPUT (itself, or through a repo script
    it names that is not a reviewed SCRIPT_ALLOWLIST file) and reads a secret or names a
    credential fails unless allow-listed."""
    errors, used = [], set()
    name = Path(path).name
    counts = identity_counts(path, lines)
    for key in sorted(k for k in allow if k[0] == name and counts.get(k, 0) > 1):
        errors.append(f"{path}: {counts[key]} steps share the allow-listed identity {key!r}; an allow-listed step must be unique in its job")
    content_ok = set()
    for start, end, label, job in steps_of(lines):
        key = (name, job, label)
        if key in allow and counts[key] == 1:
            got = step_digest(lines, start, end)
            if pinned(allow[key])[1] == got:
                content_ok.add(key)
            else:
                errors.append(f"{path}:{start + 1}: allow-listed step {label!r} (job {job}) changed since it was reviewed; "
                              f"re-review it, then pin its new digest {got} in STEP_ALLOWLIST")
    for start, end, label, job in steps_of(lines):
        text = step_text(lines, start, end)
        touches = [] if not MENTIONS_TARGET.search(text) else ["the step itself"]
        if root is not None:
            for ref in sorted(set(SCRIPT_REF.findall(text))):
                f = root / ref
                if ref != SELF and ref not in script_allow and f.is_file() and MENTIONS_TARGET.search(f.read_text(encoding="utf-8", errors="replace")):
                    touches.append(ref)
        if not touches:
            continue
        secrets = ANY_SECRET.findall(text)
        names = sorted(set(sensitive_idents(text)))
        if not secrets and not names:
            continue
        if (name, job, label) in allow and counts[(name, job, label)] == 1:
            used.add((name, job, label))
            continue  # exempt when its digest matches; a changed digest was reported above
        why = ", ".join(([f"reads {len(secrets)} secret(s)"] if secrets else []) + ([f"names {', '.join(names)}"] if names else []))
        errors.append(f"{path}:{start + 1}: step {label!r} (job {job}) touches $GITHUB_ENV/$GITHUB_OUTPUT ({', '.join(touches)}) and {why}; "
                      "pass credentials only through the env: of the step that uses them, or list the step in STEP_ALLOWLIST with a reason")
    return errors, used


def script_credential_names(text: str) -> list[str]:
    """Credential-looking names in a script that mentions GITHUB_ENV/GITHUB_OUTPUT ([] otherwise)."""
    body = "\n".join(l for l in text.splitlines() if not l.lstrip().startswith(("#", "//")))
    return sorted(set(sensitive_idents(body))) if MENTIONS_TARGET.search(body) else []


def check_script_file(path: str, text: str, allow: dict[str, str] = SCRIPT_ALLOWLIST) -> list[str]:
    """Rule 4 (fail-closed, whole file): a script that mentions GITHUB_ENV/GITHUB_OUTPUT in any form
    and names a credential-looking identifier anywhere fails unless allow-listed. Cross-line flow
    (read a credential on one line, write it on another, through any alias of the file) is
    therefore covered as long as the credential's name appears in the file."""
    names = script_credential_names(text)
    if not names:
        return []
    if path in allow:
        got = sha256(text)
        if pinned(allow[path])[1] == got:
            return []
        return [f"{path}: allow-listed script changed since it was reviewed; re-review it, then pin its new digest {got} in SCRIPT_ALLOWLIST"]
    return [f"{path}: mentions $GITHUB_ENV/$GITHUB_OUTPUT and names {', '.join(names[:6])}; "
            "keep credentials out of scripts that write those files, or list it in SCRIPT_ALLOWLIST with a reason"]


def own_key(lines: list[str], start: int, end: int, key: str) -> tuple[int, str] | None:
    """A step's own `key:` line (index, inline value)."""
    step_indent = indent_of(lines[start])
    for j in range(start, end):
        l = lines[j]
        if is_comment(l):
            continue
        m = re.match(r"^\s*(?:-\s+)?" + key + r"\s*:\s*(.*?)\s*$", l)
        if m and (j == start or indent_of(l) == step_indent + 2):
            return j, m.group(1)
    return None


def check_qa_secret_scope(path: str, lines: list[str], allowed: dict[str, dict[str, dict[str, dict]]] = QA_SECRET_STEPS) -> list[str]:
    """Rule 3."""
    name = Path(path).name
    uses = [i for i, l in enumerate(lines) if not is_comment(l) and QA_SECRET.search(l)]
    if name not in allowed:
        return [f"{path}:{uses[0] + 1}: reads QA secrets but is not listed in QA_SECRET_STEPS (review its scope, then list its steps)"] if uses else []
    errors = []
    steps = steps_of(lines)
    pins = {(job, label): pin for job, by_name in allowed[name].items() for label, pin in by_name.items()}
    matches: dict[tuple[str, str], list[tuple[int, int]]] = {}
    for s, e, label, job in steps:
        matches.setdefault((job, label), []).append((s, e))
    for i in uses:
        parent = parent_of(lines, i)
        step = next(((s, e, label, job) for s, e, label, job in steps if s < i < e), None)
        if parent is None or lines[parent].strip() != "env:":
            errors.append(f"{path}:{i + 1}: QA secret outside a step env: entry")
        elif step is None or indent_of(lines[step[0]]) + 2 != indent_of(lines[parent]):
            errors.append(f"{path}:{i + 1}: QA secret in a job- or workflow-level env: (reaches every step)")
        elif (step[3], step[2]) not in pins:
            errors.append(f"{path}:{i + 1}: QA secret in step {step[2]!r} (job {step[3]}), which is not an allowed credential step")
    for (job, label), pin in pins.items():
        found = matches.get((job, label), [])
        if len(found) != 1:
            errors.append(f"{path}: allowed credential step {label!r} (job {job}) matches {len(found)} steps; it must match exactly one"
                          + (" (renamed or removed? update QA_SECRET_STEPS)" if not found else " (duplicate step name)"))
            continue
        s, e = found[0]
        got_if = own_key(lines, s, e, "if")
        got_if = got_if[1] if got_if else None
        if got_if != pin["if"]:
            errors.append(f"{path}:{s + 1}: step {label!r} if: is {got_if!r}, pinned {pin['if']!r}")
        env = own_key(lines, s, e, "env")
        entries = {}
        if env:
            j = env[0] + 1
            while j < e and (not lines[j].strip() or is_comment(lines[j]) or indent_of(lines[j]) > indent_of(lines[env[0]])):
                if lines[j].strip() and not is_comment(lines[j]) and ":" in lines[j]:
                    k, v = lines[j].strip().split(":", 1)
                    entries[k.strip()] = v.strip()
                j += 1
        got = {k: v for k, v in entries.items() if QA_SECRET.search(v) or k in pin["env"]}
        if got != pin["env"]:
            extra = sorted(set(got) - set(pin["env"]))
            missing = sorted(set(pin["env"]) - set(got))
            changed = sorted(k for k in set(got) & set(pin["env"]) if got[k] != pin["env"][k])
            errors.append(f"{path}:{s + 1}: step {label!r} QA env differs from its pin (extra {extra}, missing {missing}, changed {changed})")
    return errors


def scan(root: Path, allow: dict[tuple[str, str, str], str] = STEP_ALLOWLIST,
         qa_allowed: dict[str, dict[str, dict[str, dict]]] = QA_SECRET_STEPS,
         script_allow: dict[str, str] = SCRIPT_ALLOWLIST) -> list[str]:
    errors, used = [], set()
    gh = root / ".github"
    seen_workflows = set()
    for p in sorted(list(gh.glob("workflows/*.y*ml")) + list(gh.glob("actions/**/*.y*ml"))):
        rel = str(p.relative_to(root))
        lines = p.read_text(encoding="utf-8").splitlines()
        errors += check_write_lines(rel, lines)
        step_errors, step_used = check_steps(rel, lines, allow, root, script_allow)
        errors += step_errors
        used |= step_used
        if p.parent.name == "workflows":
            seen_workflows.add(p.name)
            errors += check_qa_secret_scope(rel, lines, qa_allowed)
    for missing in sorted(set(qa_allowed) - seen_workflows):
        errors.append(f".github/workflows/{missing}: listed in QA_SECRET_STEPS but missing")
    for stale in sorted(set(allow) - used):
        errors.append(f"STEP_ALLOWLIST entry {stale!r} matches no unique step that needs it; remove or fix it")
    used_scripts: set[str] = set()
    scripts = [p for base in (root / "scripts", gh / "actions") if base.is_dir()
               for p in sorted(base.rglob("*")) if p.is_file() and p.suffix in SCRIPT_EXTS]
    for p in scripts:
        rel = str(p.relative_to(root))
        if rel == SELF:
            continue
        text = p.read_text(encoding="utf-8", errors="replace")
        script_errors = check_script_file(rel, text, script_allow)
        if rel in script_allow and script_credential_names(text):
            used_scripts.add(rel)
            if not script_errors:
                continue  # reviewed, and unchanged since
        errors += check_write_lines(rel, text.splitlines()) + script_errors
    for stale in sorted(set(script_allow) - used_scripts):
        errors.append(f"SCRIPT_ALLOWLIST entry {stale!r} matches no script that needs it; remove it")
    return errors


# ---------------------------------------------------------------- self-test (fake values only)

def wf(steps: str, header: str = "") -> str:
    return header + "jobs:\n  a:\n    runs-on: ubuntu-latest\n    steps:\n" + steps


SIGN_IN = """      - name: Sign in
        env:
          QA_LOGIN_PASSWORD: ${{ secrets.QA_FAKE_PASSWORD }}
        run: node login.mjs
"""
LATER = """      - name: Later step
        run: node other.mjs
"""
FAKE_PINS = {"w.yml": {"a": {"Sign in": {"if": None, "env": {"QA_LOGIN_PASSWORD": "${{ secrets.QA_FAKE_PASSWORD }}"}}}}}


def env_on(step: str, line: str) -> str:
    return step.replace("        run:", f"        env:\n          {line}\n        run:", 1)


# Workflow steps that must be flagged by rule 1 or 2 (no allowlist).
BAD = {
    "echo_env": """      - name: Leak
        run: echo "QA_TEST_PASSWORD=${{ secrets.QA_FAKE_PASSWORD }}" >> "$GITHUB_ENV"
""",
    "heredoc_env": """      - name: Leak
        run: |
          echo "API_TOKEN<<EOF" >> $GITHUB_ENV
""",
    "value_from_secret_output": env_on("""      - name: Leak
        run: echo "value=$P" >> "$GITHUB_OUTPUT"
""", "P: ${{ secrets.FAKE }}"),
    "lowercase_email_output": """      - id: p
        run: echo "email=$E" | tee -a "$GITHUB_OUTPUT"
""",
    "indirect_var": env_on("""      - name: Leak
        run: |
          f=$GITHUB_ENV
          echo "X=$Y" >> "$f"
""", "Y: ${{ secrets.FAKE }}"),
    "printf_braced": env_on("""      - name: Leak
        run: printf 'X=%s\\n' "$Y" >> "${GITHUB_ENV}"
""", "Y: ${{ secrets.FAKE }}"),
    "tee_a": env_on("""      - name: Leak
        run: echo "X=$Y" | tee -a "$GITHUB_ENV"
""", "Y: ${{ secrets.FAKE }}"),
    "python_environ": env_on("""      - name: Leak
        run: |
          python3 - <<'PY'
          import os
          f = open(os.environ['GITHUB_ENV'], 'a')
          f.write('X=' + os.environ['A'])
          PY
""", "A: ${{ secrets.FAKE }}"),
    "node_process_env": env_on("""      - name: Leak
        run: node -e 'require("fs").appendFileSync(process.env.GITHUB_OUTPUT, "x=" + process.env.A)'
""", "A: ${{ secrets.FAKE }}"),
    "bracket_secret_env": env_on("""      - name: Leak
        run: echo "X=$A" >> "$GITHUB_ENV"
""", "A: ${{ secrets['FAKE'] }}"),
    "names_password_no_secret": """      - name: Leak
        run: |
          out=$GITHUB_OUTPUT
          echo "p=$MY_PASSWORD" >> "$out"
""",
    "python_names_email": """      - name: Leak
        run: |
          python3 - <<'PY'
          import os
          for name in ("QA_TEST_EMAIL",):
              open(os.environ["GITHUB_ENV"], "a").write(name + "=" + os.environ[name])
          PY
""",
    "action_alias_with_input": """      - id: leak
        uses: some/action@v1
        with:
          value: ${{ secrets.FAKE }}
          target: GITHUB_ENV
""",
}

# Must pass with no allowlist.
GOOD = {
    "step_env_only": SIGN_IN,
    "env_write_without_secrets": """      - name: Pick simulator
        run: |
          # never write the PASSWORD here (comment)
          f=$GITHUB_ENV
          python3 - <<'PY' >> "$f"
          print("SIM_UDID=1")
          PY
""",
    "secrets_without_env_write": env_on("""      - name: Upload
        run: node upload.mjs
""", "A: ${{ secrets.FAKE }}"),
}

ALLOW_CASE = env_on("""      - name: Claim
        run: |
          bash claim.sh
          echo "REQUIRED=$required" >> "$GITHUB_ENV"
""", "STAMP_TOKEN: ${{ secrets.FAKE_TOKEN }}")

# QA scope (rule 3) on fake workflows against FAKE_PINS: (text, flagged?).
SCOPE = {
    "ok": (wf(SIGN_IN + LATER), False),
    "other_step_dot": (wf(SIGN_IN + env_on(LATER, "X: ${{ secrets.QA_FAKE_PASSWORD }}")), True),
    "other_step_bracket_single": (wf(SIGN_IN + env_on(LATER, "X: ${{ secrets['QA_FAKE_PASSWORD'] }}")), True),
    "other_step_bracket_double_spaces": (wf(SIGN_IN + env_on(LATER, 'X: ${{ secrets[ "QA_FAKE_PASSWORD" ] }}')), True),
    "other_step_lowercase": (wf(SIGN_IN + env_on(LATER, "X: ${{ secrets.qa_fake_password }}")), True),
    "other_step_mixed_case_spaced": (wf(SIGN_IN + env_on(LATER, "X: ${{ Secrets . Qa_Fake_Password }}")), True),
    "other_step_tojson": (wf(SIGN_IN + env_on(LATER, "X: ${{ toJSON(secrets) }}")), True),
    "allowed_step_bracket_spelling": (wf(SIGN_IN.replace("secrets.QA_FAKE_PASSWORD", "secrets['QA_FAKE_PASSWORD']") + LATER), True),
    "allowed_step_extra_alias": (wf(SIGN_IN.replace("        run:", "          EXTRA_ALIAS: ${{ secrets.QA_FAKE_PASSWORD }}\n        run:") + LATER), True),
    "allowed_step_gate_added_true": (wf(SIGN_IN.replace("        env:", "        if: ${{ true }}\n        env:")), True),
    "job_env": (wf(SIGN_IN).replace("    runs-on:", "    env:\n      QA_LOGIN_EMAIL: ${{ secrets.QA_FAKE_EMAIL }}\n    runs-on:"), True),
    "workflow_env": (wf(SIGN_IN, header="env:\n  QA_LOGIN_EMAIL: ${{ secrets.QA_FAKE_EMAIL }}\n"), True),
    "run_body": (wf(SIGN_IN + LATER.replace("node other.mjs", "echo ${{ secrets.QA_FAKE_EMAIL }}")), True),
    "action_with_input": (wf(SIGN_IN + "      - uses: x/y@v1\n        with:\n          pw: ${{ secrets.QA_FAKE_PASSWORD }}\n"), True),
    "step_renamed_to_alias": (wf(SIGN_IN.replace("name: Sign in", "name: Log in")), True),
    "step_name_replaced_by_id": (wf(SIGN_IN.replace("- name: Sign in", "- id: sign-in")), True),
    "all_references_deleted": (wf(SIGN_IN.replace("QA_LOGIN_PASSWORD: ${{ secrets.QA_FAKE_PASSWORD }}", "X: plain") + LATER), True),
    # Review finding (a) at 28c4bea: a second step with an allowed step's name gets QA secrets.
    "duplicate_allowed_name_same_job": (wf(SIGN_IN + LATER + SIGN_IN.replace("node login.mjs", "node other.mjs")), True),
    "duplicate_allowed_name_without_secrets": (wf(SIGN_IN + LATER.replace("Later step", "Sign in")), True),
    "allowed_name_in_other_job": (wf(SIGN_IN) + "  b:\n    runs-on: ubuntu-latest\n    steps:\n" + SIGN_IN, True),
    "background_step_gets_secret": (wf(SIGN_IN + env_on("""      - name: Start harness (background)
        run: node harness.mjs &
""", "QA_LOGIN_PASSWORD: ${{ secrets.QA_FAKE_PASSWORD }}")), True),
}

# Script files (rule 1 + rule 4): (text, flagged?). From the review: a Python helper that reads a
# fake credential on one line and writes it to $GITHUB_ENV on a later one, and shell aliases.
SCRIPTS = {
    "py_cross_line": ('import os\np = os.environ["QA_TEST_PASSWORD"]\nf = open(os.environ["GITHUB_ENV"], "a")\nf.write("X=" + p + "\\n")\n', True),
    "sh_alias": ('f="$GITHUB_ENV"\nv="$QA_LOGIN_EMAIL"\necho "X=$v" >> "$f"\n', True),
    "sh_alias_output_tee": ('out=${GITHUB_OUTPUT}\nprintf "x=%s\\n" "$DEPLOY_TOKEN" | tee -a "$out"\n', True),
    "node_cross_line": ('const p = process.env.API_SECRET;\nconst f = process.env.GITHUB_OUTPUT;\nrequire("fs").appendFileSync(f, `x=${p}\\n`);\n', True),
    "sh_direct": ('echo "DEPLOY_SECRET=$x" >> "${GITHUB_ENV:?}"\n', True),
    "sh_clean": ('f="$GITHUB_ENV"\necho "SIM_UDID=$udid" >> "$f"\n', False),
    "py_secret_without_env_write": ('import os\np = os.environ["QA_TEST_PASSWORD"]\nprint(len(p))\n', False),
}


def real_tree_mutants() -> list[tuple[str, str, str, str]]:
    """(name, file, old, new) mutants of the real workflows; each must turn a clean scan red."""
    qa, ios, ral = ".github/workflows/qa-acceptance.yml", ".github/workflows/qa-acceptance-ios.yml", ".github/workflows/release-auto-login.yml"
    kvm = "      - name: Enable KVM\n"
    gate = f"{LEGACY} && {NOT_LOGIN_ACCEPTANCE} && secrets.QA_TEST_PASSWORD || ''"
    ios_gate = f"{LEGACY} && secrets.QA_TEST_PASSWORD || ''"
    preflight_gate = ("re:" + r"(^      - name: " + re.escape(PREFLIGHT) +
                      r"\n(?:        #[^\n]*\n|\n)*)        if: " + re.escape(ANDROID_LEGACY_IF) + r"\n")
    out = []
    for spelling in ("secrets['QA_TEST_PASSWORD']", 'secrets["QA_TEST_PASSWORD"]', "secrets[ 'qa_test_password' ]", "secrets.qa_test_password", "Secrets.Qa_Test_Password"):
        out.append((f"unlisted step reads {spelling}", qa, kvm, kvm + f"        env:\n          X: ${{{{ {spelling} }}}}\n"))
    out += [
        ("extra alias in an allowed step", qa, "QA_TEST_PASSWORD: ${{ secrets.QA_TEST_PASSWORD }}",
         "QA_TEST_PASSWORD: ${{ secrets.QA_TEST_PASSWORD }}\n          EXTRA_ALIAS: ${{ secrets.QA_TEST_PASSWORD }}"),
        ("acceptance runner no longer gated on bootstrap", qa, gate, "secrets.QA_TEST_PASSWORD"),
        ("login acceptance preflight falls back to QA_TEST_PASSWORD", qa,
         f"QA_LOGIN_ACCEPTANCE_PASSWORD: ${{{{ {IS_LOGIN_ACCEPTANCE} && secrets.QA_LOGIN_ACCEPTANCE_PASSWORD || '' }}}}",
         f"QA_LOGIN_ACCEPTANCE_PASSWORD: ${{{{ {IS_LOGIN_ACCEPTANCE} && secrets.QA_LOGIN_ACCEPTANCE_PASSWORD || secrets.QA_TEST_PASSWORD }}}}"),
        ("emulator step gets login acceptance account step-wide", qa,
         "          QA_RELEASE_PRELOGIN: ",
         "          QA_LOGIN_ACCEPTANCE_PASSWORD: ${{ secrets.QA_LOGIN_ACCEPTANCE_PASSWORD }}\n          QA_RELEASE_PRELOGIN: "),
        ("login acceptance credential staged outside login-email-password", qa,
         f"        if: {ANDROID_LOGIN_ACCEPTANCE_IF}\n        env:\n          QA_LOGIN_ACCEPTANCE_EMAIL",
         f"        if: {ANDROID_LEGACY_IF}\n        env:\n          QA_LOGIN_ACCEPTANCE_EMAIL"),
        ("evidence gate drops login-email-password gate", qa,
         "      - name: Secure evidence gate\n        id: evidence-gate\n        if: always()\n        env:\n          QA_LOGIN_ACCEPTANCE_EMAIL: ${{ steps.stamp-claim.outputs.claimed_case == 'login-email-password' && secrets.QA_LOGIN_ACCEPTANCE_EMAIL || '' }}\n          QA_LOGIN_ACCEPTANCE_PASSWORD: ${{ steps.stamp-claim.outputs.claimed_case == 'login-email-password' && secrets.QA_LOGIN_ACCEPTANCE_PASSWORD || '' }}",
         "      - name: Secure evidence gate\n        id: evidence-gate\n        if: always()\n        env:\n          QA_LOGIN_ACCEPTANCE_EMAIL: ${{ secrets.QA_LOGIN_ACCEPTANCE_EMAIL }}\n          QA_LOGIN_ACCEPTANCE_PASSWORD: ${{ secrets.QA_LOGIN_ACCEPTANCE_PASSWORD }}"),
        ("release credential staged outside the release lane", qa,
         "        if: ${{ inputs.stamp_run_case_id != '' && inputs.login_mode == 'release-prelogin' }}\n        env:\n          QA_TEST_EMAIL",
         "        if: ${{ inputs.stamp_run_case_id != '' }}\n        env:\n          QA_TEST_EMAIL"),
        ("emulator step gets the release account step-wide", qa, "          QA_RELEASE_PRELOGIN: ",
         "          QA_RELEASE_LOGIN_PASSWORD: ${{ secrets.QA_TEST_PASSWORD }}\n          QA_RELEASE_PRELOGIN: "),
        ("legacy preflight runs in the release lane", qa, f"        if: {ANDROID_LEGACY_IF}\n", f"        if: {LEGACY_IF}\n"),
        # #222: neither iOS step may get the account back, gated or not.
        ("iOS deterministic repro gets the QA password back", ios, "        # Never signs in, so no QA credentials (#222).\n",
         f"        env:\n          QA_TEST_PASSWORD: ${{{{ {ios_gate} }}}}\n"),
        ("iOS acceptance agent gets the QA password back", ios, "          QA_PASSWORD_LOGIN: ",
         f"          QA_TEST_PASSWORD: ${{{{ {ios_gate} }}}}\n          QA_PASSWORD_LOGIN: "),
        ("preflight gate -> true", qa, preflight_gate, r"\g<1>        if: ${{ true }}\n"),
        ("preflight gate -> false", qa, preflight_gate, r"\g<1>        if: ${{ false }}\n"),
        ("preflight gate removed", qa, preflight_gate, r"\g<1>"),
        ("staging/production branch pair swapped", ral,
         "QA_LOGIN_PASSWORD: ${{ (needs.plan.outputs.environment == 'staging' && secrets.QA_STAGING_TEST_PASSWORD) || secrets.QA_TEST_PASSWORD }}\n        with:",
         "QA_LOGIN_PASSWORD: ${{ (needs.plan.outputs.environment == 'production' && secrets.QA_STAGING_TEST_PASSWORD) || secrets.QA_TEST_PASSWORD }}\n        with:"),
        ("sign-in step renamed (step alias)", ral, "      - name: Sign in on the emulator\n", "      - name: Sign in on the emulator (v2)\n"),
        ("credential export into $GITHUB_ENV restored", ral, "      - name: Enable KVM\n",
         "      - name: Export\n        env:\n          E: ${{ secrets.QA_TEST_EMAIL }}\n        run: |\n          f=$GITHUB_ENV\n          echo \"QA_LOGIN_EMAIL=$E\" >> \"$f\"\n\n      - name: Enable KVM\n"),
        ("background loop harness gets the account", ios, "      - name: Start loop harness (background)\n        if: ${{ env.IOS_QA_DRIVER == 'qa-loop' }}\n",
         "      - name: Start loop harness (background)\n        if: ${{ env.IOS_QA_DRIVER == 'qa-loop' }}\n        env:\n          QA_TEST_PASSWORD: ${{ secrets.QA_TEST_PASSWORD }}\n"),
    ]
    # Review findings at 28c4bea: a second step borrowing an allow-listed identity.
    out += [
        ("second step named like an allowed QA step (same job)", qa, kvm,
         "      - name: Seed media fixtures (image + workspace)\n        env:\n          QA_TEST_PASSWORD: ${{ secrets.QA_TEST_PASSWORD }}\n        run: node scripts/qa/other.mjs\n\n" + kvm),
        ("second sign-in step with the same name", ral, "      - name: Enable KVM\n",
         "      - name: Sign in on the emulator\n        env:\n          QA_LOGIN_EMAIL: ${{ (needs.plan.outputs.environment == 'staging' && secrets.QA_STAGING_TEST_EMAIL) || secrets.QA_TEST_EMAIL }}\n          QA_LOGIN_PASSWORD: ${{ (needs.plan.outputs.environment == 'staging' && secrets.QA_STAGING_TEST_PASSWORD) || secrets.QA_TEST_PASSWORD }}\n        run: node other.mjs\n\n      - name: Enable KVM\n"),
        ("new $GITHUB_ENV writer borrows an allow-listed step name", qa, kvm,
         "      - name: Claim Stamp run case and resolve auth mode\n        env:\n          STAMP_TOKEN: ${{ secrets.STAMP_TOKEN }}\n        run: |\n          f=$GITHUB_ENV\n          echo \"T=$STAMP_TOKEN\" >> \"$f\"\n\n" + kvm),
        ("new $GITHUB_OUTPUT writer borrows an allow-listed step id", ".github/workflows/android-update-e2e.yml",
         "      - name: Install Hands CLI\n", "      - id: p\n        env:\n          A: ${{ secrets.FAKE_TOKEN }}\n        run: echo \"a=$A\" | tee -a \"$GITHUB_OUTPUT\"\n      - name: Install Hands CLI\n"),
    ]
    # Round-2 replay (28c4bea): a new step borrowing an output-allow-listed name, and edits of
    # reviewed allow-listed content.
    alpha = "      - name: Find and download the Alpha APK\n        id: apk"
    out += [
        ("new GITHUB_OUTPUT writer prepended with an allow-listed name (replay 2)", ".github/workflows/android-realtime-e2e.yml", alpha,
         "      - name: Find and download the Alpha APK\n        env:\n          FAKE_SECRET: ${{ secrets.FAKE_TEST_KEY }}\n        run: |\n"
         "          out=$GITHUB_OUTPUT\n          echo \"x=$FAKE_SECRET\" >> \"$out\"\n" + alpha),
        ("allow-listed step body edited", qa, '          echo "STAMP_BOOTSTRAP_REQUIRED=$required" >> "$GITHUB_ENV"\n',
         '          echo "STAMP_BOOTSTRAP_REQUIRED=$required" >> "$GITHUB_ENV"\n          echo "T=$STAMP_TOKEN" >> "$GITHUB_OUTPUT"\n'),
        ("allow-listed step env edited", ".github/workflows/release-auto-login.yml", "          GH_TOKEN: ${{ github.token }}\n          RUN_ID: ${{ needs.plan.outputs.apk_run_id }}",
         "          GH_TOKEN: ${{ github.token }}\n          EXTRA: ${{ secrets.QA_TEST_PASSWORD }}\n          RUN_ID: ${{ needs.plan.outputs.apk_run_id }}"),
        ("allow-listed script edited", ".github/actions/gradle-cache-policy/apply.sh", 'echo "gradle-cache-policy: ${summary}"\n',
         'echo "gradle-cache-policy: ${summary}"\necho "x=$token" >> "${GITHUB_OUTPUT:-/dev/null}"\n'),
    ]
    # Whole-file rewrites: old is a regex, applied to every match.
    for wfname in ("android-update-e2e.yml", "android-realtime-e2e.yml", "qa-acceptance.yml"):
        out.append((f"all QA references deleted from {wfname}", f".github/workflows/{wfname}",
                    r"re:^\s*\w+: \$\{\{.*secrets\.QA_.*\}\}\n", ""))
    out.append(("QA references only in bracket form", ".github/workflows/android-realtime-e2e.yml",
                r"re:secrets\.(QA_[A-Z_]+)", r"secrets['\1']"))
    return out


def self_test(repo: Path) -> int:
    failed, count = 0, 0

    def expect(case: str, errors: list[str], flagged: bool) -> None:
        nonlocal failed, count
        count += 1
        if bool(errors) != flagged:
            failed += 1
            print(f"self-test FAIL: {case}: expected {'flagged' if flagged else 'clean'}, got {errors}")

    def rules(text: str, allow: dict | None = None) -> list[str]:
        lines = text.splitlines()
        return check_write_lines("w.yml", lines) + check_steps("w.yml", lines, allow or {})[0]

    for case, steps in BAD.items():
        expect(f"bad/{case}", rules(wf(steps)), True)
    for case, steps in GOOD.items():
        expect(f"good/{case}", rules(wf(steps)), False)
    expect("allowlist/unlisted", rules(wf(ALLOW_CASE)), True)
    def digest_of(text: str, label: str) -> str:
        lines = text.splitlines()
        return next(step_digest(lines, st, en) for st, en, lb, _ in steps_of(lines) if lb == label)

    claim = {("w.yml", "a", "Claim"): ("fake reason", digest_of(wf(ALLOW_CASE), "Claim"))}
    expect("allowlist/listed", rules(wf(ALLOW_CASE), claim), False)
    expect("allowlist/listed_comment_only_edit", rules(wf(ALLOW_CASE.replace("        run: |", "        # reviewed\n        run: |")), claim), False)
    # Round-2 review: an exemption must not survive an edit of the exempt step's body.
    edited = ALLOW_CASE + '          echo "LEAK=$STAMP_TOKEN" >> "$GITHUB_ENV"\n'
    expect("allowlist/listed_step_body_changed", rules(wf(edited), claim), True)
    expect("allowlist/listed_step_env_changed", rules(wf(ALLOW_CASE.replace("secrets.FAKE_TOKEN", "secrets.OTHER_FAKE")), claim), True)
    expect("allowlist/listed_without_digest", rules(wf(ALLOW_CASE), {("w.yml", "a", "Claim"): "fake reason"}), True)
    expect("allowlist/listed_in_other_job", rules(wf(ALLOW_CASE), {("w.yml", "b", "Claim"): "fake reason"}), True)
    # Review finding (b) at 28c4bea: a new step borrows an allow-listed name and writes $GITHUB_ENV.
    expect("allowlist/name_borrowed_by_second_step", rules(wf(ALLOW_CASE + BAD["indirect_var"].replace("name: Leak", "name: Claim"))), True)
    expect("allowlist/name_borrowed_by_second_step_listed", rules(wf(ALLOW_CASE + BAD["indirect_var"].replace("name: Leak", "name: Claim")), claim), True)
    expect("allowlist/duplicate_of_listed_step_without_env_write", rules(wf(ALLOW_CASE + LATER.replace("Later step", "Claim")), claim), True)
    for case, (text, flagged) in SCOPE.items():
        expect(f"scope/{case}", check_qa_secret_scope("w.yml", text.splitlines(), FAKE_PINS), flagged)
    expect("scope/unlisted_workflow", check_qa_secret_scope("new.yml", wf(SIGN_IN).splitlines(), FAKE_PINS), True)
    expect("scope/unlisted_workflow_bracket_only", check_qa_secret_scope("new.yml", wf(SIGN_IN.replace("secrets.QA_FAKE_PASSWORD", "secrets['QA_FAKE_PASSWORD']")).splitlines(), FAKE_PINS), True)
    expect("scope/unlisted_workflow_without_qa", check_qa_secret_scope("new.yml", wf(LATER).splitlines(), FAKE_PINS), False)
    for case, (text, flagged) in SCRIPTS.items():
        expect(f"script/{case}", check_write_lines("scripts/fake.x", text.splitlines()) + check_script_file("scripts/fake.x", text, {}), flagged)
    reviewed = {"scripts/fake.x": ("fake", sha256(SCRIPTS["py_cross_line"][0]))}
    expect("script/allow-listed", check_script_file("scripts/fake.x", SCRIPTS["py_cross_line"][0], reviewed), False)
    expect("script/allow-listed_then_changed", check_script_file("scripts/fake.x", SCRIPTS["py_cross_line"][0] + 'f.write("Y=" + p)\n', reviewed), True)
    expect("script/allow-listed_without_digest", check_script_file("scripts/fake.x", SCRIPTS["py_cross_line"][0], {"scripts/fake.x": "fake"}), True)

    # Scratch trees: fake-only, then the real workflows/scripts with each mutant applied.
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp) / "fake"
        (root / ".github/workflows").mkdir(parents=True)
        (root / "scripts").mkdir()
        target = root / ".github/workflows/w.yml"
        target.write_text(wf(SIGN_IN + LATER), encoding="utf-8")
        expect("scan/fake_clean", scan(root, {}, FAKE_PINS, {}), False)
        expect("scan/listed_workflow_missing", scan(root, {}, {**FAKE_PINS, "gone.yml": FAKE_PINS["w.yml"]}, {}), True)
        expect("scan/stale_allowlist_entry", scan(root, {("w.yml", "a", "Nope"): ("fake", "0" * 64)}, FAKE_PINS, {}), True)
        target.write_text(wf(SIGN_IN.replace("secrets.QA_FAKE_PASSWORD", "secrets['QA_FAKE_PASSWORD']") + LATER), encoding="utf-8")
        expect("scan/known_workflow_bracket_only", scan(root, {}, FAKE_PINS, {}), True)
        target.write_text(wf(SIGN_IN + LATER), encoding="utf-8")
        (root / "scripts/helper.py").write_text(SCRIPTS["py_cross_line"][0], encoding="utf-8")
        expect("scan/script_cross_line", scan(root, {}, FAKE_PINS, {}), True)
        helper = 'import os\nopen(os.environ["GITHUB_ENV"], "a").write("TOKEN_KIND=fixture\\n")\n'
        (root / "scripts/helper.py").write_text(helper, encoding="utf-8")
        target.write_text(wf(SIGN_IN + env_on(LATER.replace("node other.mjs", "python3 scripts/helper.py"), "A: ${{ secrets.FAKE }}")), encoding="utf-8")
        expect("scan/secret_step_calls_env_writing_script", scan(root, {}, FAKE_PINS, {}), True)
        pin = {"scripts/helper.py": ("fake", sha256(helper))}
        expect("scan/secret_step_calls_reviewed_script", scan(root, {}, FAKE_PINS, pin), False)
        (root / "scripts/helper.py").write_text(helper + 'open(os.environ["GITHUB_ENV"], "a").write("X=" + os.environ["A"])\n', encoding="utf-8")
        expect("scan/reviewed_script_changed", scan(root, {}, FAKE_PINS, pin), True)
        (root / "scripts/helper.py").write_text('print("no env writes")\n', encoding="utf-8")
        expect("scan/script_allowlist_entry_no_longer_needed", scan(root, {}, FAKE_PINS, pin), True)

        real = Path(tmp) / "real"
        if (repo / ".github/workflows").is_dir():
            import shutil
            for sub in (".github", "scripts"):
                if (repo / sub).is_dir():
                    shutil.copytree(repo / sub, real / sub, ignore=shutil.ignore_patterns("node_modules", "__pycache__", "*.png", "*.jpg", "*.mp4", "*.zip"))
            expect("real/unmutated", scan(real), False)
            for case, rel, old, new in real_tree_mutants():
                f = real / rel
                original = f.read_text(encoding="utf-8")
                if old.startswith("re:"):
                    mutated = re.sub(old[3:], new, original, flags=re.MULTILINE)
                    if mutated == original or QA_SECRET.search(mutated) and not new:
                        failed += 1
                        count += 1
                        print(f"self-test FAIL: real/{case}: rewrite did not apply cleanly in {rel}")
                        continue
                else:
                    if old not in original:
                        failed += 1
                        count += 1
                        print(f"self-test FAIL: real/{case}: mutant anchor not found in {rel}; update real_tree_mutants()")
                        continue
                    mutated = original.replace(old, new, 1)
                f.write_text(mutated, encoding="utf-8")
                expect(f"real/{case}", scan(real), True)
                f.write_text(original, encoding="utf-8")
            helper = real / "scripts/qa/fake_helper.py"
            helper.write_text(SCRIPTS["py_cross_line"][0], encoding="utf-8")
            expect("real/script reads a credential and writes $GITHUB_ENV on a later line", scan(real), True)
            helper.unlink()
    print("self-test " + ("FAILED" if failed else "passed") + f" ({count} cases, {failed} failed)")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=str(Path(__file__).resolve().parents[2]))
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test(Path(args.root))
    errors = scan(Path(args.root))
    for e in errors:
        print(f"::error::{e}" if "GITHUB_ACTIONS" in os.environ else e)
    if errors:
        print(f"{len(errors)} finding(s). Pass credentials only to the step that needs them, through its own env: from secrets.*.")
        return 1
    print("no credential writes into $GITHUB_ENV/$GITHUB_OUTPUT; QA secrets only in their pinned steps")
    return 0


if __name__ == "__main__":
    sys.exit(main())
