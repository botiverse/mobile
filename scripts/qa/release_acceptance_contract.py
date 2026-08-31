#!/usr/bin/env python3
"""Strict source and plan contract for the Android 1.7.0 release cases."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml


DEFAULT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CASE_DIR = DEFAULT_ROOT / "docs" / "qa-acceptance" / "cases"
MANIFEST_NAME = "release-1.7.0-android.json"

# "Raft Android" is the production Raft SERVER (artin, 2026-08-30: QA runs may
# only use test/qa servers) — the name collides with the Android app's own
# title, which is why this ban applies to case sources, not to prose docs.
# Case text never needs to mention production: the runtime prohibition is
# injected by scripts/qa/server_guard.mjs at the agent-preamble choke point,
# so ANY occurrence here is either a leftover instruction from the July
# incident era or a future edit that should be a conscious decision, not a
# silent one (task #171).
PRODUCTION_SERVER_NAME = "Raft Android"
# The login case is the entry gate every automated run passes through; its
# server-picker branch must keep naming the QA server explicitly so a picker
# never leaves the choice to the model.
LOGIN_CASE_ID = "login-email-password"
QA_SERVER_NAME = "OranixSlockTest"


class ContractError(ValueError):
    pass


class UniqueKeyLoader(yaml.SafeLoader):
    """Safe YAML loader that rejects duplicate keys at every mapping level."""


def _construct_unique_mapping(
    loader: UniqueKeyLoader,
    node: yaml.nodes.MappingNode,
    deep: bool = False,
) -> dict[Any, Any]:
    loader.flatten_mapping(node)
    result: dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        try:
            duplicate = key in result
        except TypeError as exc:
            raise ContractError(f"unhashable YAML mapping key at line {key_node.start_mark.line + 1}") from exc
        if duplicate:
            raise ContractError(
                f"duplicate YAML key {key!r} at line {key_node.start_mark.line + 1}",
            )
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    _construct_unique_mapping,
)


def load_yaml(path: Path) -> dict[str, Any]:
    try:
        value = yaml.load(path.read_text(encoding="utf-8"), Loader=UniqueKeyLoader)
    except (yaml.YAMLError, ContractError) as exc:
        raise ContractError(f"{path.name}: {exc}") from exc
    if not isinstance(value, dict):
        raise ContractError(f"{path.name}: root must be a mapping")
    return value


def _unique_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ContractError(f"duplicate JSON key {key!r}")
        result[key] = value
    return result


def load_manifest(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_json_object)
    except (json.JSONDecodeError, ContractError) as exc:
        raise ContractError(f"{path.name}: {exc}") from exc
    if not isinstance(value, dict):
        raise ContractError(f"{path.name}: root must be an object")
    return value


def _id_list(manifest: dict[str, Any], key: str, count: int) -> list[str]:
    value = manifest.get(key)
    if not isinstance(value, list) or any(not isinstance(item, str) or not item for item in value):
        raise ContractError(f"{key} must be a list of non-empty strings")
    if len(value) != count or len(set(value)) != count:
        raise ContractError(f"{key} must contain exactly {count} unique IDs")
    return value


@dataclass(frozen=True)
class ReleaseAcceptanceContract:
    canonical_ids: tuple[str, ...]
    automated_ids: tuple[str, ...]
    manual_only_ids: tuple[str, ...]
    sources: dict[str, tuple[Path, dict[str, Any]]]

    def plan(self) -> dict[str, Any]:
        """The only supported generated input for an automated release run."""
        return {
            "release": "1.7.0",
            "case_ids": list(self.automated_ids),
            "excluded_manual_only_case_ids": list(self.manual_only_ids),
        }


def load_contract(case_dir: Path = DEFAULT_CASE_DIR) -> ReleaseAcceptanceContract:
    manifest = load_manifest(case_dir / MANIFEST_NAME)
    canonical = _id_list(manifest, "canonical_case_ids", 26)
    automated = _id_list(manifest, "automated_case_ids", 25)
    manual_only = _id_list(manifest, "manual_only_case_ids", 1)
    canonical_set = set(canonical)
    automated_set = set(automated)
    manual_set = set(manual_only)

    if manual_set != {"server-create"}:
        raise ContractError("server-create must be the sole manual-only case")
    if automated_set & manual_set or automated_set | manual_set != canonical_set:
        raise ContractError("automated and manual-only sets must partition the canonical set")

    sources: dict[str, tuple[Path, dict[str, Any]]] = {}
    for path in sorted(case_dir.glob("*.yaml")):
        source = load_yaml(path)
        case_id = source.get("id")
        if not isinstance(case_id, str) or not case_id:
            raise ContractError(f"{path.name}: missing non-empty top-level id")
        if case_id in sources:
            raise ContractError(f"duplicate case id {case_id}: {sources[case_id][0].name}, {path.name}")
        # Polarity pair, absence half: no case source may reference the
        # production server at all — raw text, so goals, hints, notes, and
        # any future field are all covered.
        raw_text = path.read_text(encoding="utf-8")
        if PRODUCTION_SERVER_NAME.lower() in raw_text.lower():
            raise ContractError(
                f"{path.name}: references the production server "
                f"{PRODUCTION_SERVER_NAME!r}; QA case sources may only name "
                f"test/qa servers (the runtime ban lives in server_guard.mjs)",
            )
        sources[case_id] = (path, source)

    actual = set(sources)
    missing = sorted(canonical_set - actual)
    extra = sorted(actual - canonical_set)
    if missing or extra:
        raise ContractError(f"case source set drift: missing={missing}, extra={extra}")

    for case_id in automated:
        path, source = sources[case_id]
        if source.get("auth_fixture") != "alpha-qa":
            raise ContractError(f"{path.name}: automated case must use auth_fixture alpha-qa")
        if source.get("launch_route") != "compose_slock_app_shell":
            raise ContractError(f"{path.name}: automated case must launch compose_slock_app_shell")
        if "manual-only" in (source.get("tags") or []):
            raise ContractError(f"{path.name}: manual-only case cannot enter the automated set")
        ios_variant = source.get("ios")
        declared_platforms = source.get("platforms")
        has_ios_variant = isinstance(ios_variant, dict)
        has_unified_platform_matrix = (
            isinstance(declared_platforms, list)
            and "android" in declared_platforms
            and "ios" in declared_platforms
        )
        if not has_ios_variant and not has_unified_platform_matrix:
            raise ContractError(
                f"{path.name}: automated case must preserve the unified Android/iOS contract",
            )

    # Polarity pair, presence half: the login case's picker branch must keep
    # naming the QA server. Deleting that line would leave picker choice to
    # the model — exactly what the July incident runs did.
    login_path, _login = sources[LOGIN_CASE_ID]
    if QA_SERVER_NAME not in login_path.read_text(encoding="utf-8"):
        raise ContractError(
            f"{login_path.name}: the login case must name {QA_SERVER_NAME!r} "
            f"for its server-picker branch",
        )

    path, server_create = sources["server-create"]
    if server_create.get("auth_fixture") != "none":
        raise ContractError(f"{path.name}: manual provisioning must keep auth_fixture none")
    if server_create.get("launch_route") != "compose_slock_app_shell":
        raise ContractError(f"{path.name}: manual provisioning launch route drifted")
    if "manual-only" not in (server_create.get("tags") or []):
        raise ContractError(f"{path.name}: missing manual-only tag")
    execution_policy = server_create.get("execution_policy")
    if not isinstance(execution_policy, dict) or execution_policy.get("scheduled") is not False:
        raise ContractError(f"{path.name}: execution_policy.scheduled must be false")

    contract = ReleaseAcceptanceContract(
        canonical_ids=tuple(canonical),
        automated_ids=tuple(automated),
        manual_only_ids=tuple(manual_only),
        sources=sources,
    )
    plan_ids = contract.plan()["case_ids"]
    if len(plan_ids) != 25 or len(set(plan_ids)) != 25 or "server-create" in plan_ids:
        raise ContractError("generated automated plan must contain 25 unique IDs and exclude server-create")
    return contract
