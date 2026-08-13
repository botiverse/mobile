#!/usr/bin/env python3
"""Audit the exact history carried by changed submodule gitlinks."""

from __future__ import annotations

import argparse
import configparser
import json
import re
import subprocess
import sys
import tempfile
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable
from urllib.parse import urlparse


SCHEMA = "botiverse-mobile-gitlink-carry-in/v1"
COMMENT_MARKER = "<!-- gitlink-carry-in-gate -->"
ZERO_SHA = "0" * 40
RAW_DIFF_PATTERN = re.compile(
    r"^:(?P<old_mode>[0-7]{6}) (?P<new_mode>[0-7]{6}) "
    r"(?P<old_object>[0-9a-f]{40}) (?P<new_object>[0-9a-f]{40}) (?P<status>[A-Z])$"
)


class GateError(RuntimeError):
    pass


@dataclass(frozen=True)
class GitlinkChange:
    path: str
    old: str
    new: str
    url: str


def run_git(
    repo: Path,
    *args: str,
    check: bool = True,
    text: bool = True,
) -> subprocess.CompletedProcess[str] | subprocess.CompletedProcess[bytes]:
    result = subprocess.run(
        ["git", "-C", str(repo), *args],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=text,
    )
    if check and result.returncode != 0:
        stderr = result.stderr if text else result.stderr.decode("utf-8", "replace")
        raise GateError(f"git {' '.join(args)} failed in {repo}: {stderr.strip()}")
    return result


def require_commit(repo: Path, value: str, label: str) -> str:
    if not value or not value.strip():
        raise GateError(f"{label} commit is empty")
    result = run_git(repo, "rev-parse", "--verify", f"{value}^{{commit}}")
    return result.stdout.strip()


def gitlinks_at(repo: Path, commit: str) -> dict[str, str]:
    raw = run_git(repo, "ls-tree", "-r", "-z", commit, text=False).stdout
    result: dict[str, str] = {}
    for record in raw.split(b"\0"):
        if not record:
            continue
        metadata, path_bytes = record.split(b"\t", 1)
        mode, object_type, object_id = metadata.decode("ascii").split(" ")
        if mode == "160000" and object_type == "commit":
            result[path_bytes.decode("utf-8", "surrogateescape")] = object_id
    return result


def gitmodules_at(repo: Path, commit: str) -> dict[str, dict[str, str]]:
    result = run_git(repo, "show", f"{commit}:.gitmodules", check=False)
    if result.returncode != 0:
        return {}
    parser = configparser.RawConfigParser()
    parser.read_string(result.stdout)
    modules: dict[str, dict[str, str]] = {}
    for section in parser.sections():
        path = parser.get(section, "path", fallback="").strip()
        if not path:
            continue
        modules[path] = {
            "url": parser.get(section, "url", fallback="").strip(),
            "branch": parser.get(section, "branch", fallback="").strip(),
        }
    return modules


def path_exists_at(repo: Path, commit: str, path: str) -> bool:
    return bool(run_git(repo, "ls-tree", commit, "--", path).stdout.strip())


def changed_gitlinks(
    repo: Path,
    base: str,
    head: str,
) -> tuple[list[GitlinkChange], list[dict[str, str]]]:
    base_links = gitlinks_at(repo, base)
    head_links = gitlinks_at(repo, head)
    base_modules = gitmodules_at(repo, base)
    head_modules = gitmodules_at(repo, head)
    changes: list[GitlinkChange] = []
    removals: list[dict[str, str]] = []
    for path in sorted(set(base_links) | set(head_links)):
        old = base_links.get(path)
        new = head_links.get(path)
        if old == new:
            continue
        if old is None:
            raise GateError(
                f"gitlink {path} was added; audit new submodule authority separately"
            )
        if new is None:
            base_module = base_modules.get(path)
            if base_module is None:
                raise GateError(f"removed gitlink {path} is missing from base .gitmodules")
            if path in head_modules:
                raise GateError(f"removed gitlink {path} remains in head .gitmodules")
            if path_exists_at(repo, head, path):
                raise GateError(f"removed gitlink {path} was replaced by another tracked object")
            removals.append({"path": path, "old": old, "url": base_module.get("url", "")})
            continue
        base_module = base_modules.get(path)
        head_module = head_modules.get(path)
        if base_module is None or head_module is None:
            raise GateError(f"gitlink {path} is missing from base or head .gitmodules")
        if base_module != head_module:
            raise GateError(
                f"gitlink {path} changed its .gitmodules URL or branch; audit that migration separately"
            )
        url = head_module.get("url", "")
        if not url:
            raise GateError(f"gitlink {path} has an empty .gitmodules URL")
        changes.append(GitlinkChange(path=path, old=old, new=new, url=url))
    return changes, removals


def validate_remote_url(url: str, allow_local_remotes: bool) -> None:
    if allow_local_remotes:
        return
    parsed = urlparse(url)
    if parsed.scheme != "https" or parsed.hostname != "github.com" or not parsed.path.strip("/"):
        raise GateError(f"submodule remote must be an https://github.com URL, got {url!r}")
    if parsed.username or parsed.password or parsed.port:
        raise GateError("submodule remote must not contain credentials or a custom port")


def fetch_history(change: GitlinkChange, destination: Path, allow_local_remotes: bool) -> None:
    validate_remote_url(change.url, allow_local_remotes)
    run_git(destination.parent, "init", "--bare", str(destination))
    run_git(destination, "remote", "add", "origin", change.url)
    filter_args = [] if allow_local_remotes else ["--filter=blob:none"]
    exact_args = [
        "fetch",
        "--no-tags",
        *filter_args,
        "--force",
        "origin",
        f"{change.old}:refs/carry/old",
        f"{change.new}:refs/carry/new",
    ]
    exact = run_git(destination, *exact_args, check=False)
    if exact.returncode == 0:
        return

    # Some servers reject exact object wants even when the commits are reachable.
    # Fetch advertised branches, then resolve the two immutable pins locally.
    advertised = run_git(
        destination,
        "fetch",
        "--no-tags",
        *filter_args,
        "--force",
        "origin",
        "+refs/heads/*:refs/remotes/origin/*",
        check=False,
    )
    if advertised.returncode != 0:
        raise GateError(
            f"failed to fetch {change.path} from {change.url}: "
            f"exact={exact.stderr.strip()}; advertised={advertised.stderr.strip()}"
        )
    for label, commit in (("old", change.old), ("new", change.new)):
        if run_git(destination, "cat-file", "-e", f"{commit}^{{commit}}", check=False).returncode != 0:
            raise GateError(
                f"{change.path}: {label} pin {commit} is not reachable from an advertised branch"
            )
        run_git(destination, "update-ref", f"refs/carry/{label}", commit)


def split_nul(value: bytes) -> list[str]:
    return [part.decode("utf-8", "surrogateescape") for part in value.split(b"\0") if part]


def endpoint_changes(repo: Path, old: str, new: str) -> list[dict[str, str]]:
    raw = run_git(
        repo,
        "diff",
        "--raw",
        "--no-abbrev",
        "--no-renames",
        "-z",
        old,
        new,
        "--",
        text=False,
    ).stdout
    records = raw.split(b"\0")
    changes: list[dict[str, str]] = []
    index = 0
    while index < len(records):
        if not records[index]:
            index += 1
            continue
        metadata = records[index].decode("ascii")
        if index + 1 >= len(records) or not records[index + 1]:
            raise GateError("malformed NUL-delimited endpoint diff")
        path = records[index + 1].decode("utf-8", "surrogateescape")
        match = RAW_DIFF_PATTERN.fullmatch(metadata)
        if match is None:
            raise GateError(f"unexpected endpoint diff metadata: {metadata!r}")
        changes.append({"path": path, **match.groupdict()})
        index += 2
    return changes


def audit_submodule(
    change: GitlinkChange,
    temp_root: Path,
    allow_local_remotes: bool,
) -> dict[str, object]:
    repo = temp_root / re.sub(r"[^A-Za-z0-9._-]+", "-", change.path).strip("-")
    fetch_history(change, repo, allow_local_remotes)
    old = run_git(repo, "rev-parse", "refs/carry/old^{commit}").stdout.strip()
    new = run_git(repo, "rev-parse", "refs/carry/new^{commit}").stdout.strip()
    merge_base_result = run_git(repo, "merge-base", old, new, check=False)
    merge_base = merge_base_result.stdout.strip() if merge_base_result.returncode == 0 else ""
    behind = int(run_git(repo, "rev-list", "--count", f"{new}..{old}").stdout.strip())
    if merge_base != old or behind != 0:
        raise GateError(
            f"{change.path}: old pin is not a true ancestor of new pin "
            f"(old={old}, new={new}, merge_base={merge_base or 'missing'}, behind={behind})"
        )

    commits = run_git(repo, "rev-list", "--reverse", "--topo-order", f"{old}..{new}").stdout.splitlines()
    commit_records: list[dict[str, str]] = []
    touched_by_path: defaultdict[str, list[str]] = defaultdict(list)
    for commit in commits:
        subject = run_git(repo, "show", "-s", "--format=%s", commit).stdout.rstrip("\n")
        commit_records.append({"sha": commit, "subject": subject})
        lineage = run_git(repo, "rev-list", "--parents", "-n", "1", commit).stdout.split()
        diff_range = [lineage[1], commit] if len(lineage) > 1 else ["--root", commit]
        touched = split_nul(
            run_git(
                repo,
                "diff-tree",
                "--no-commit-id",
                "--name-only",
                "--no-renames",
                "-r",
                "-z",
                *diff_range,
                text=False,
            ).stdout
        )
        for path in sorted(set(touched)):
            if commit not in touched_by_path[path]:
                touched_by_path[path].append(commit)

    process_touched = [
        {"path": path, "commits": shas}
        for path, shas in sorted(touched_by_path.items())
    ]
    endpoint = endpoint_changes(repo, old, new)
    endpoint_paths = {entry["path"] for entry in endpoint}
    process_only = sorted(set(touched_by_path) - endpoint_paths)
    return {
        "path": change.path,
        "url": change.url,
        "old": old,
        "new": new,
        "merge_base": merge_base,
        "behind": behind,
        "commit_count": len(commit_records),
        "commits": commit_records,
        "process_touched_count": len(process_touched),
        "process_touched": process_touched,
        "endpoint_changed_count": len(endpoint),
        "endpoint_changed": endpoint,
        "process_only_count": len(process_only),
        "process_only": process_only,
    }


def markdown_code(value: str) -> str:
    value = value.replace("\r", " ").replace("\n", " ")
    longest = max((len(match.group(0)) for match in re.finditer(r"`+", value)), default=0)
    fence = "`" * max(1, longest + 1)
    return f"{fence}{value}{fence}"


def markdown_list(values: Iterable[str], empty: str) -> list[str]:
    values = list(values)
    if not values:
        return [f"- {empty}"]
    return [f"- {markdown_code(value)}" for value in values]


def render_markdown(manifest: dict[str, object]) -> str:
    lines = [
        COMMENT_MARKER,
        "## Gitlink carry-in audit",
        "",
        f"Exact range: {markdown_code(str(manifest.get('base', '')))} → {markdown_code(str(manifest.get('head', '')))}",
        "",
        "List **A** is process history. List **B** is the sole carry-in conclusion because it is computed from endpoint object/mode differences.",
        "",
    ]
    errors = manifest.get("errors", [])
    if errors:
        lines.extend(["### Gate result: RED", ""])
        lines.extend(markdown_list((str(error) for error in errors), "No error recorded"))
        lines.append("")
    submodules = manifest.get("submodules", [])
    removals = manifest.get("removed_submodules", [])
    if removals:
        lines.extend(["### Removed gitlinks", ""])
        for removal in removals:
            lines.append(
                f"- {markdown_code(str(removal['path']))} removed from "
                f"{markdown_code(str(removal['old']))}; `.gitmodules` entry and tracked path absent"
            )
        lines.append("")
    if not submodules and not removals:
        lines.extend(["No gitlink changes in this exact range.", ""])
        return "\n".join(lines)

    for submodule in submodules:
        assert isinstance(submodule, dict)
        lines.extend(
            [
                f"### {markdown_code(str(submodule['path']))}",
                "",
                f"Pins: {markdown_code(str(submodule['old']))} → {markdown_code(str(submodule['new']))}",
                f"Ancestor proof: merge base = {markdown_code(str(submodule['merge_base']))}; behind = {markdown_code(str(submodule['behind']))}",
                "",
                f"#### Commits ({submodule['commit_count']})",
                "",
            ]
        )
        commits = submodule["commits"]
        if commits:
            for commit in commits:
                lines.append(
                    f"- {markdown_code(str(commit['sha']))} {markdown_code(str(commit['subject']))}"
                )
        else:
            lines.append("- No commits")

        lines.extend(
            [
                "",
                f"#### A. Files touched by interval commits ({submodule['process_touched_count']})",
                "",
            ]
        )
        process_touched = submodule["process_touched"]
        if process_touched:
            for item in process_touched:
                short_commits = ", ".join(str(sha)[:12] for sha in item["commits"])
                lines.append(f"- {markdown_code(str(item['path']))} (commits {markdown_code(short_commits)})")
        else:
            lines.append("- No files")

        lines.extend(
            [
                "",
                f"#### B. Files different at the two endpoint trees ({submodule['endpoint_changed_count']})",
                "",
            ]
        )
        endpoint = submodule["endpoint_changed"]
        if endpoint:
            for item in endpoint:
                proof = (
                    f"{item['status']} {item['old_mode']}:{item['old_object']} "
                    f"→ {item['new_mode']}:{item['new_object']}"
                )
                lines.append(f"- {markdown_code(str(item['path']))} {markdown_code(proof)}")
        else:
            lines.append("- No endpoint differences")

        lines.extend(
            [
                "",
                f"A-only (changed during the interval, restored by the new endpoint): {submodule['process_only_count']}",
                "",
            ]
        )
        lines.extend(markdown_list((str(path) for path in submodule["process_only"]), "None"))
        lines.append("")
    return "\n".join(lines)


def audit(
    repo: Path,
    base: str,
    head: str,
    allow_local_remotes: bool = False,
) -> dict[str, object]:
    base_commit = require_commit(repo, base, "base")
    head_commit = require_commit(repo, head, "head")

    # Compare against the merge base, not the base tip.
    #
    # The question this gate answers is "what pin movement does THIS branch
    # introduce", and only the merge base isolates that. Diffing the base tip
    # against head also reports every pin the base advanced on its own, which a
    # branch that never touched the gitlink would then be blamed for: the merge
    # keeps the base's newer pin, so nothing is carried backward, yet the gate
    # hard-reds on the apparent reversal and forces a rebase. That fires on
    # every open branch each time the base advances a submodule, which is a
    # rebase treadmill rather than a safety property.
    #
    # Detection is unchanged: a branch that really does move a pin backward has
    # that move in its own diff, so it is still caught here.
    merge_base_result = run_git(repo, "merge-base", base_commit, head_commit, check=False)
    comparison_base = merge_base_result.stdout.strip() if merge_base_result.returncode == 0 else ""
    if not comparison_base:
        # Unrelated histories are refused rather than silently falling back to
        # the base tip, which would reinstate the false positive under the one
        # condition where it is least likely to be noticed.
        raise GateError(
            f"no merge base between base {base_commit} and head {head_commit}; "
            "cannot determine what this branch introduces"
        )

    changes, removals = changed_gitlinks(repo, comparison_base, head_commit)
    manifest: dict[str, object] = {
        "schema": SCHEMA,
        "base": base_commit,
        # Recorded separately so a reader can tell which commit the comparison
        # actually used; when the branch is current these are the same commit.
        "comparison_base": comparison_base,
        "head": head_commit,
        "status": "no_changes" if not changes and not removals else "pass",
        "submodules": [],
        "removed_submodules": removals,
        "errors": [],
    }
    if not changes:
        return manifest
    with tempfile.TemporaryDirectory(prefix="gitlink-carry-in-") as temp_dir:
        for change in changes:
            manifest["submodules"].append(
                audit_submodule(change, Path(temp_dir), allow_local_remotes)
            )
    return manifest


def write_outputs(
    manifest: dict[str, object],
    manifest_path: Path,
    markdown_path: Path,
    markdown_override: str | None = None,
) -> None:
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    markdown_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    markdown_path.write_text(
        markdown_override if markdown_override is not None else render_markdown(manifest),
        encoding="utf-8",
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--markdown", type=Path, required=True)
    parser.add_argument("--allow-local-remotes", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--max-comment-bytes", type=int, default=60_000)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repo = args.repo.resolve()
    manifest: dict[str, object]
    try:
        manifest = audit(repo, args.base, args.head, args.allow_local_remotes)
    except GateError as error:
        manifest = {
            "schema": SCHEMA,
            "base": args.base,
            "head": args.head,
            "status": "error",
            "submodules": [],
            "removed_submodules": [],
            "errors": [str(error)],
        }
        write_outputs(manifest, args.manifest, args.markdown)
        print(f"gitlink carry-in gate: RED: {error}", file=sys.stderr)
        return 1

    markdown = render_markdown(manifest)
    if len(markdown.encode("utf-8")) > args.max_comment_bytes:
        manifest["status"] = "error"
        manifest["errors"] = [
            f"complete audit is too large for one PR comment ({len(markdown.encode('utf-8'))} bytes); split the pin interval"
        ]
        compact_report = render_markdown(
            {
                **manifest,
                "submodules": [],
            }
        )
        write_outputs(manifest, args.manifest, args.markdown, compact_report)
        print("gitlink carry-in gate: RED: report exceeds PR comment limit", file=sys.stderr)
        return 1

    write_outputs(manifest, args.manifest, args.markdown)
    print(
        f"gitlink carry-in gate: {manifest['status']} "
        f"submodules={len(manifest['submodules'])} "
        f"removed={len(manifest['removed_submodules'])}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
