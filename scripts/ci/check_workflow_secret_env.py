#!/usr/bin/env python3
"""Fail when a workflow puts credentials into $GITHUB_ENV or $GITHUB_OUTPUT.

A value written to $GITHUB_ENV is in the environment of every later step of the
job, and every process those steps start (actions, background harnesses, tools),
for the rest of the job. A value written to $GITHUB_OUTPUT can be read by any
later step or job. Credentials go only to the one step that needs them, through
that step's own `env:` from `secrets.*` (Argus, after #4806).

Static and deliberately cheap: it reads the text of every workflow and composite
action (no YAML library), splits it into steps, and in every step that writes
$GITHUB_ENV / $GITHUB_OUTPUT flags

  1. a write line naming a variable or value that looks like a credential
     (PASSWORD, EMAIL, TOKEN or SECRET, any case), or reading `secrets.*`;
  2. any credential-looking name written through a program (python/node opening
     the file, a heredoc or `cat` redirected into it), where names are not on
     the write line itself;
  3. a step of kind 2 whose own `env:` takes a value from `secrets.*`.

Scripts under scripts/ get rule 1 only (their write lines).

It also checks scope: in workflows, secrets.QA_* may appear only in the step-level env: of the
steps listed in QA_SECRET_STEPS (never job/workflow env:, with:, or a run: body).

Usage: check_workflow_secret_env.py [--root DIR] | --self-test
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import tempfile
from pathlib import Path

SENSITIVE = re.compile(r"PASSWORD|EMAIL|TOKEN|SECRET", re.IGNORECASE)
TARGET = r"GITHUB_(?:ENV|OUTPUT)"
# A shell redirection / tee / language-level open of the env or output file.
WRITE_LINE = re.compile(
    r"(>>?\s*[\"']?\$\{?" + TARGET + r"|tee\s+(?:-a\s+)?[\"']?\$\{?" + TARGET
    + r"|environ\s*\[\s*[\"']" + TARGET + r"[\"']\s*\]|environ\.get\(\s*[\"']" + TARGET
    + r"|process\.env\." + TARGET + r"|getenv\(\s*[\"']" + TARGET + r")"
)
# Writes whose variable names are not on the write line (a program writes them).
OPAQUE_WRITE = re.compile(
    r"(environ\s*\[\s*[\"']" + TARGET + r"|environ\.get\(\s*[\"']" + TARGET
    + r"|process\.env\." + TARGET + r"|getenv\(\s*[\"']" + TARGET
    + r"|<<-?\s*[\"']?\w+[\"']?\s*(?:\|\s*\S+\s*)?>>?\s*[\"']?\$\{?" + TARGET
    + r"|\bcat\b[^\n|]*>>?\s*[\"']?\$\{?" + TARGET + r")"
)
IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
STEP_START = re.compile(r"^\s*-\s+(?:name|uses|id|run|if|env|shell|working-directory|with|timeout-minutes|continue-on-error)\s*:")
ENV_KEY = re.compile(r"^(\s*)env\s*:\s*$")


def sensitive_idents(text: str, include_reads: bool) -> list[str]:
    hits = []
    for m in IDENT.finditer(text):
        word = m.group(0)
        if not SENSITIVE.search(word) or word.startswith("GITHUB_"):
            continue
        before = text[max(0, m.start() - 2):m.start()]
        is_read = before.endswith("$") or before.endswith("${") or before.endswith("env.") or before[-1:] == "."
        if is_read and not include_reads:
            continue
        hits.append(word)
    return hits


def strip_comment(line: str) -> str:
    s = line.lstrip()
    return "" if s.startswith("#") else line


def split_steps(lines: list[str]) -> list[tuple[int, list[str]]]:
    starts = [i for i, line in enumerate(lines) if STEP_START.match(line)]
    if not starts:
        return [(0, lines)]
    blocks = [(0, lines[:starts[0]])]
    for a, b in zip(starts, starts[1:] + [len(lines)]):
        blocks.append((a, lines[a:b]))
    return blocks


def step_env_secrets(block: list[str]) -> list[str]:
    out = []
    for i, line in enumerate(block):
        m = ENV_KEY.match(line)
        if not m:
            continue
        indent = len(m.group(1))
        for sub in block[i + 1:]:
            if sub.strip() and (len(sub) - len(sub.lstrip())) <= indent:
                break
            if "secrets." in sub and ":" in sub:
                out.append(sub.split(":", 1)[0].strip())
    return out


def check_text(path: str, text: str, workflow: bool) -> list[str]:
    errors = []
    lines = text.splitlines()
    blocks = split_steps(lines) if workflow else [(0, lines)]
    for offset, block in blocks:
        writes = [(offset + i, l) for i, l in enumerate(block) if WRITE_LINE.search(strip_comment(l))]
        if not writes:
            continue
        for lineno, line in writes:
            names = sensitive_idents(line, include_reads=True)
            if names or "secrets." in line:
                errors.append(f"{path}:{lineno + 1}: writes credential-looking {', '.join(sorted(set(names))) or 'secrets.*'} into $GITHUB_ENV/$GITHUB_OUTPUT")
        if not workflow:
            continue
        body = "\n".join(strip_comment(l) for l in block)
        if not OPAQUE_WRITE.search(body):
            continue
        # Only the run body counts here: env: entries, names and if: conditions are not written.
        env_lines = set()
        for i, l in enumerate(block):
            m = ENV_KEY.match(l)
            if m:
                indent = len(m.group(1))
                for j in range(i + 1, len(block)):
                    sub = block[j]
                    if sub.strip() and (len(sub) - len(sub.lstrip())) <= indent:
                        break
                    env_lines.add(j)
        run_text = "\n".join(strip_comment(l) for j, l in enumerate(block) if j not in env_lines and not re.match(r"^\s*(-\s+)?(name|if|uses|id)\s*:", l))
        names = sensitive_idents(run_text, include_reads=False)
        first = writes[0][0] + 1
        if names:
            errors.append(f"{path}:{first}: a program writes $GITHUB_ENV/$GITHUB_OUTPUT in a step that names credential-looking {', '.join(sorted(set(names)))}")
        secret_env = step_env_secrets(block)
        if secret_env:
            errors.append(f"{path}:{first}: a program writes $GITHUB_ENV/$GITHUB_OUTPUT in a step whose env takes secrets ({', '.join(secret_env)})")
    return errors


# Scope: the QA account secrets (secrets.QA_*) may be read only in the env: of these steps.
# Anything else that reads them (another step, a job- or workflow-level env:, a with:, a run:
# body, a new workflow) fails until it is reviewed and listed here.
QA_SECRET_STEPS = {
    "release-auto-login.yml": {"Old CLI task", "Check the QA credentials are configured", "Sign in on the emulator"},
    "android-realtime-e2e.yml": {"Preflight — QA account must not see the production server", "Run realtime e2e on the emulator"},
    "android-update-e2e.yml": {"Preflight — QA account must not see the production server", "Run update e2e on the emulator"},
    "qa-acceptance.yml": {"Preflight — QA account must not see the production server", "Freeze legacy credential identity receipt",
                          "Seed media fixtures (image + workspace)", "Run acceptance agent"},
    "qa-acceptance-ios.yml": {"Preflight — QA account must not see the production server", "Freeze legacy credential identity receipt",
                              "Run acceptance agent", "Run deterministic repro", "Report to Stamp"},
    "ios-simulator-app.yml": {"Sign in and screenshot home"},
}
QA_SECRET = re.compile(r"secrets\.QA_[A-Z0-9_]+")
STEP_NAME = re.compile(r"^\s*(?:-\s+)?name\s*:\s*(.+?)\s*$")


def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip())


def check_qa_secret_scope(path: str, text: str, allowed: dict[str, set[str]] = QA_SECRET_STEPS) -> list[str]:
    lines = text.splitlines()
    uses = [i for i, l in enumerate(lines) if QA_SECRET.search(strip_comment(l))]
    if not uses:
        return []
    name = Path(path).name
    if name not in allowed:
        return [f"{path}: reads secrets.QA_* but is not listed in QA_SECRET_STEPS (review its scope, then list its steps)"]
    errors = []
    seen = set()
    for i in uses:
        # The parent key of this line must be `env:`, and that env: must belong to a step (`- ` item).
        parent = next((j for j in range(i - 1, -1, -1) if lines[j].strip() and indent_of(lines[j]) < indent_of(lines[i])), None)
        if parent is None or lines[parent].strip() != "env:":
            errors.append(f"{path}:{i + 1}: secrets.QA_* outside a step env: entry")
            continue
        step = next((j for j in range(parent - 1, -1, -1) if lines[j].strip() and indent_of(lines[j]) < indent_of(lines[parent])), None)
        if step is None or not lines[step].lstrip().startswith("- ") or indent_of(lines[step]) + 2 != indent_of(lines[parent]):
            errors.append(f"{path}:{i + 1}: secrets.QA_* in a job- or workflow-level env: (reaches every step)")
            continue
        block_end = next((j for j in range(step + 1, len(lines)) if lines[j].strip() and indent_of(lines[j]) <= indent_of(lines[step])), len(lines))
        step_name = next((m.group(1).strip("'\"") for l in lines[step:block_end] if (m := STEP_NAME.match(l)) and indent_of(l) <= indent_of(lines[step]) + 2), None)
        if step_name not in allowed[name]:
            errors.append(f"{path}:{i + 1}: secrets.QA_* in step {step_name!r}, which is not an allowed credential step")
        else:
            seen.add(step_name)
    for missing in sorted(allowed[name] - seen):
        errors.append(f"{path}: allowed credential step {missing!r} no longer reads secrets.QA_* (renamed? update QA_SECRET_STEPS)")
    return errors


def scan(root: Path) -> list[str]:
    errors = []
    gh = root / ".github"
    for p in sorted(list(gh.glob("workflows/*.y*ml")) + list(gh.glob("actions/**/*.y*ml"))):
        errors += check_text(str(p.relative_to(root)), p.read_text(encoding="utf-8"), workflow=True)
        if p.parent.name == "workflows":
            errors += check_qa_secret_scope(str(p.relative_to(root)), p.read_text(encoding="utf-8"))
    for pattern in ("actions/**/*.sh",):
        for p in sorted(gh.glob(pattern)):
            errors += check_text(str(p.relative_to(root)), p.read_text(encoding="utf-8"), workflow=False)
    for ext in ("sh", "py", "mjs", "js"):
        for p in sorted((root / "scripts").rglob(f"*.{ext}")):
            if p.name == Path(__file__).name or re.search(r"(^test_|\.test\.|_test\.)", p.name):
                continue
            errors += check_text(str(p.relative_to(root)), p.read_text(encoding="utf-8", errors="replace"), workflow=False)
    return errors


BAD_CASES = {
    "echo_env": """      - name: Leak
        run: echo "QA_TEST_PASSWORD=${{ secrets.QA_TEST_PASSWORD }}" >> "$GITHUB_ENV"
""",
    "heredoc_env": """      - name: Leak
        run: |
          echo "API_TOKEN<<EOF" >> $GITHUB_ENV
""",
    "value_from_secret_output": """      - name: Leak
        env:
          P: ${{ secrets.P }}
        run: echo "value=$QA_PASSWORD" >> "$GITHUB_OUTPUT"
""",
    "python_loop": """      - name: Expose QA credentials
        env:
          QA_TEST_EMAIL_SECRET: ${{ secrets.QA_TEST_EMAIL }}
        run: |
          python3 - <<'PY'
          import os
          with open(os.environ["GITHUB_ENV"], "a") as out:
              for name in ("QA_TEST_EMAIL",):
                  out.write(f"{name}={os.environ[name + '_SECRET']}\\n")
          PY
""",
    "python_env_secret_only": """      - name: Expose
        env:
          A: ${{ secrets.QA_TEST_EMAIL }}
        run: |
          python3 - <<'PY'
          import os
          with open(os.environ["GITHUB_ENV"], "a") as out:
              out.write("X=" + os.environ["A"] + "\\n")
          PY
""",
    "lowercase_email_output": """      - id: p
        run: echo "email=$E" | tee -a "$GITHUB_OUTPUT"
""",
}

GOOD_CASES = {
    "step_env_only": """      - name: Sign in
        env:
          QA_TEST_EMAIL: ${{ secrets.QA_TEST_EMAIL }}
          QA_TEST_PASSWORD: ${{ secrets.QA_TEST_PASSWORD }}
        run: node login.mjs
""",
    "unrelated_env_with_token_in_step_env": """      - name: Claim
        env:
          STAMP_TOKEN: ${{ secrets.STAMP_TOKEN }}
        run: |
          bash claim.sh
          echo "STAMP_BOOTSTRAP_REQUIRED=$required" >> "$GITHUB_ENV"
""",
    "comment_mentions_password": """      - name: Pick simulator
        run: |
          # never write the PASSWORD here
          python3 - <<'PY' >> "$GITHUB_ENV"
          print("SIM_UDID=1")
          PY
""",
}


def self_test() -> int:
    failed = 0
    for name, text in BAD_CASES.items():
        if not check_text(f"bad/{name}.yml", text, workflow=True):
            print(f"self-test FAIL: {name} was not flagged")
            failed += 1
    for name, text in GOOD_CASES.items():
        errs = check_text(f"good/{name}.yml", text, workflow=True)
        if errs:
            print(f"self-test FAIL: {name} flagged: {errs}")
            failed += 1
    if not check_text("bad/script.sh", 'echo "DEPLOY_SECRET=$x" >> "${GITHUB_ENV:?}"\n', workflow=False):
        print("self-test FAIL: script write not flagged")
        failed += 1
    scope_ok = """jobs:
  a:
    steps:
      - name: Sign in
        env:
          QA_LOGIN_PASSWORD: ${{ secrets.QA_FAKE_PASSWORD }}
        run: node login.mjs
      - name: Later step
        run: node other.mjs
"""
    allowed = {"w.yml": {"Sign in"}}
    if check_qa_secret_scope("w.yml", scope_ok, allowed):
        print(f"self-test FAIL: scope ok case flagged: {check_qa_secret_scope('w.yml', scope_ok, allowed)}")
        failed += 1
    scope_bad = {
        "other_step": scope_ok.replace("        run: node other.mjs", "        env:\n          X: ${{ secrets.QA_FAKE_EMAIL }}\n        run: node other.mjs"),
        "job_env": scope_ok.replace("    steps:", "    env:\n      QA_LOGIN_EMAIL: ${{ secrets.QA_FAKE_EMAIL }}\n    steps:"),
        "workflow_env": "env:\n  QA_LOGIN_EMAIL: ${{ secrets.QA_FAKE_EMAIL }}\n" + scope_ok,
        "run_body": scope_ok.replace("node other.mjs", "echo ${{ secrets.QA_FAKE_EMAIL }}"),
        "with_input": scope_ok.replace("        run: node other.mjs", "        uses: x/y@v1\n        with:\n          pw: ${{ secrets.QA_FAKE_PASSWORD }}"),
        "renamed": scope_ok.replace("name: Sign in", "name: Log in"),
    }
    for case, text in scope_bad.items():
        if not check_qa_secret_scope("w.yml", text, allowed):
            print(f"self-test FAIL: scope case {case} was not flagged")
            failed += 1
    if not check_qa_secret_scope("new.yml", scope_ok, allowed):
        print("self-test FAIL: unlisted workflow reading secrets.QA_* was not flagged")
        failed += 1
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / ".github/workflows").mkdir(parents=True)
        (root / ".github/workflows/a.yml").write_text("jobs:\n  a:\n    steps:\n" + BAD_CASES["echo_env"], encoding="utf-8")
        if not scan(root):
            print("self-test FAIL: scan() missed a workflow file")
            failed += 1
    print("self-test " + ("FAILED" if failed else "passed") + f" ({len(BAD_CASES) + len(GOOD_CASES) + 2 + 1 + 6 + 1} cases)")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=str(Path(__file__).resolve().parents[2]))
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    errors = scan(Path(args.root))
    for e in errors:
        print(f"::error::{e}" if "GITHUB_ACTIONS" in os.environ else e)
    if errors:
        print(f"{len(errors)} credential write(s) into $GITHUB_ENV/$GITHUB_OUTPUT. Pass credentials only to the step that needs them, through its own env: from secrets.*.")
        return 1
    print("no credential-looking writes into $GITHUB_ENV/$GITHUB_OUTPUT")
    return 0


if __name__ == "__main__":
    sys.exit(main())
