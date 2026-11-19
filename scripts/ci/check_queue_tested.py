#!/usr/bin/env python3
"""Decide whether a push to main may reuse Stamp's merge-queue CI (2026-11-19).

Stamp's native merge queue fast-forwards main to a candidate head that the Test
workflow already ran on (Stamp's managed draft PR from stamp-mq/<batch>). Before
it pushes, Stamp writes the commit status `stamp/queue-tested` on that SHA,
description `v1 run=<run id> base=<base sha>` (Stamp docs/batch-merge-queue.md,
"Queue-tested status for CI reuse"). The status is only a pointer, so this
script re-checks everything the contract names and answers queue_tested=true
only when all of it holds:

1. a `stamp/queue-tested` status, state success, created by stamp-build[bot]
   (user id 328845999); statuses by anyone else are ignored;
2. its description matches ^v1 run=([0-9]+) base=([0-9a-f]{40})$;
3. the run it names: same workflow as this run, head_sha == the pushed SHA,
   run_attempt 1, status completed, conclusion success, a pull_request run from
   a stamp-mq/ branch, and every job of attempt 1 concluded success (only
   `queue-ci-reuse` may be skipped);
4. the push's `before` SHA equals the description's base (the push is exactly
   the tested fast-forward).

Anything else, including any API error, prints queue_tested=false and the push
runs the full main-push tier. Run with --self-test for the fixture table.
"""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.request

STATUS_CONTEXT = "stamp/queue-tested"
STAMP_BOT_USER_ID = 328845999  # stamp-build[bot], production Stamp App
DESCRIPTION_RE = re.compile(r"^v1 run=([0-9]+) base=([0-9a-f]{40})$")
REUSE_JOB = "queue-ci-reuse"
SHA_RE = re.compile(r"^[0-9a-f]{40}$")


def pick_status(statuses: list[dict]) -> tuple[int, str] | None:
    """Return (run_id, base) of the first valid queue-tested status, newest first."""
    for status in statuses:
        if status.get("context") != STATUS_CONTEXT or status.get("state") != "success":
            continue
        if (status.get("creator") or {}).get("id") != STAMP_BOT_USER_ID:
            continue
        match = DESCRIPTION_RE.match(status.get("description") or "")
        if match:
            return int(match.group(1)), match.group(2)
    return None


def check_run(run: dict, jobs: list[dict], sha: str, workflow_id: int) -> str | None:
    """Return None when the candidate run proves the SHA, else the reason it does not."""
    if run.get("workflow_id") != workflow_id:
        return f"run is workflow {run.get('workflow_id')}, not this workflow {workflow_id}"
    if run.get("head_sha") != sha:
        return f"run head_sha {run.get('head_sha')} != pushed {sha}"
    if run.get("run_attempt") != 1:
        return f"run_attempt is {run.get('run_attempt')}, not 1"
    if run.get("status") != "completed" or run.get("conclusion") != "success":
        return f"run is {run.get('status')}/{run.get('conclusion')}"
    if run.get("event") != "pull_request":
        return f"run event is {run.get('event')}, not pull_request"
    if not str(run.get("head_branch") or "").startswith("stamp-mq/"):
        return f"run head_branch {run.get('head_branch')} is not a stamp-mq/ candidate"
    if not jobs:
        return "run has no jobs"
    for job in jobs:
        name, conclusion = job.get("name"), job.get("conclusion")
        if conclusion == "success":
            continue
        if name == REUSE_JOB and conclusion == "skipped":
            continue
        return f"job {name!r} concluded {conclusion}"
    return None


def evaluate(statuses, run_loader, sha: str, before: str, workflow_id: int) -> tuple[bool, str, int | None]:
    if not SHA_RE.match(sha or ""):
        return False, f"pushed SHA {sha!r} is not a full SHA", None
    picked = pick_status(statuses)
    if picked is None:
        return False, f"no valid {STATUS_CONTEXT} status by stamp-build[bot]", None
    run_id, base = picked
    if before != base:
        return False, f"push before {before} != tested base {base}", run_id
    run, jobs = run_loader(run_id)
    problem = check_run(run, jobs, sha, workflow_id)
    if problem:
        return False, problem, run_id
    return True, f"run {run_id} tested {sha} on base {base}", run_id


class Api:
    def __init__(self, token: str, api_url: str, repo: str):
        self.token, self.api_url, self.repo = token, api_url.rstrip("/"), repo

    def get(self, path: str):
        request = urllib.request.Request(
            f"{self.api_url}/repos/{self.repo}/{path}",
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)

    def statuses(self, sha: str) -> list[dict]:
        out: list[dict] = []
        for page in range(1, 4):
            batch = self.get(f"commits/{sha}/statuses?per_page=100&page={page}")
            out.extend(batch)
            if len(batch) < 100:
                break
        return out

    def run_and_jobs(self, run_id: int) -> tuple[dict, list[dict]]:
        run = self.get(f"actions/runs/{run_id}")
        jobs: list[dict] = []
        for page in range(1, 6):
            batch = self.get(f"actions/runs/{run_id}/attempts/1/jobs?per_page=100&page={page}")
            jobs.extend(batch.get("jobs", []))
            if len(jobs) >= batch.get("total_count", 0) or not batch.get("jobs"):
                break
        return run, jobs


def write_outputs(ok: bool, reason: str, run_id: int | None) -> None:
    lines = [f"queue_tested={'true' if ok else 'false'}", f"queue_tested_run={run_id or ''}"]
    print("\n".join(lines))
    print(f"queue-tested: {reason}", file=sys.stderr)
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")


def main() -> int:
    sha = os.environ.get("PUSH_SHA", "")
    before = os.environ.get("PUSH_BEFORE_SHA", "")
    try:
        api = Api(os.environ["GH_TOKEN"], os.environ.get("GITHUB_API_URL", "https://api.github.com"),
                  os.environ["GITHUB_REPOSITORY"])
        workflow_id = api.get(f"actions/runs/{os.environ['GITHUB_RUN_ID']}")["workflow_id"]
        ok, reason, run_id = evaluate(api.statuses(sha), api.run_and_jobs, sha, before, workflow_id)
    except Exception as error:  # fail to the full tier, never to a skip
        ok, reason, run_id = False, f"could not verify ({type(error).__name__}: {error})", None
    write_outputs(ok, reason, run_id)
    return 0


def self_test() -> int:
    sha, base, wf = "a" * 40, "b" * 40, 307215240
    good_status = {"context": STATUS_CONTEXT, "state": "success", "creator": {"id": STAMP_BOT_USER_ID},
                   "description": f"v1 run=42 base={base}"}
    good_run = {"workflow_id": wf, "head_sha": sha, "run_attempt": 1, "status": "completed",
                "conclusion": "success", "event": "pull_request", "head_branch": "stamp-mq/b1"}
    good_jobs = [{"name": "CI gate", "conclusion": "success"},
                 {"name": "iOS compile (Xcode 27.1)", "conclusion": "success"},
                 {"name": REUSE_JOB, "conclusion": "skipped"}]

    def loader(run=good_run, jobs=good_jobs):
        return lambda run_id: (dict(run), [dict(j) for j in jobs])

    def case(name, want, statuses=(good_status,), run=good_run, jobs=good_jobs, pushed=sha, before=base):
        got, reason, _ = evaluate(list(statuses), loader(run, jobs), pushed, before, wf)
        if got != want:
            raise SystemExit(f"FAIL {name}: want {want}, got {got} ({reason})")
        print(f"ok   {name} -> {got} ({reason})")

    case("valid status and run", True)
    case("no statuses", False, statuses=())
    case("other creator", False, statuses=({**good_status, "creator": {"id": 1}},))
    case("staging bot is not production", False, statuses=({**good_status, "creator": {"id": 316203141}},))
    case("other context", False, statuses=({**good_status, "context": "stamp/queue"},))
    case("pending state", False, statuses=({**good_status, "state": "pending"},))
    case("bad description shape", False, statuses=({**good_status, "description": f"v1 run=42 base={base} x"},))
    case("v2 description", False, statuses=({**good_status, "description": f"v2 run=42 base={base}"},))
    case("uppercase base", False, statuses=({**good_status, "description": f"v1 run=42 base={base.upper()}"},))
    case("forged first, valid second", True, statuses=({**good_status, "creator": {"id": 7}}, good_status))
    case("push before != base", False, before="c" * 40)
    case("new-branch before", False, before="0" * 40)
    case("run head differs", False, run={**good_run, "head_sha": "d" * 40})
    case("rerun attempt", False, run={**good_run, "run_attempt": 2})
    case("run failed", False, run={**good_run, "conclusion": "failure"})
    case("run in progress", False, run={**good_run, "status": "in_progress", "conclusion": None})
    case("other workflow", False, run={**good_run, "workflow_id": 1})
    case("push event run", False, run={**good_run, "event": "push"})
    case("not a stamp-mq branch", False, run={**good_run, "head_branch": "feature"})
    case("a job skipped", False, jobs=good_jobs + [{"name": "Android compile & tests", "conclusion": "skipped"}])
    case("a job failed", False, jobs=good_jobs + [{"name": "Android compile & tests", "conclusion": "failure"}])
    case("reuse job ran", True, jobs=[{"name": REUSE_JOB, "conclusion": "success"}, good_jobs[0]])
    case("no jobs", False, jobs=[])
    case("short pushed sha", False, pushed="abc")
    print("check_queue_tested self-test: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(self_test() if sys.argv[1:] == ["--self-test"] else main())
