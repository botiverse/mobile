#!/usr/bin/env python3
"""Embed slock's shared runtime form v2 fixtures in mobile commonTest.

slock `packages/runtime-form/fixtures/*.json` are the exact v2 bodies the server
sends (kept equal to server output by slock's runtimeFormV2Fixtures.test.ts).
commonTest runs on every target and cannot read files outside the module, so the
bodies are embedded verbatim in RuntimeFormV2Fixtures.kt with each file's sha256.

  sync:  sync_v2_fixtures.py --slock <slock checkout>
  check: sync_v2_fixtures.py --slock <slock checkout> --check   (non-zero on drift)

The same run copies the generated Kotlin contract
(`packages/runtime-form/generated/kotlin/RuntimeFormV2.kt`).

Protocol v3 (`*.v3.json` and `v3.conditions.conformance.json`) goes to
RuntimeFormV3Fixtures.kt. `--part v3` syncs only those and the contract, so a
v3 change does not have to re-pin the v2 fixtures (and the reverse with
`--part v2`). Bodies containing `$` are embedded as `${'$'}` in a plain `val`.
"""
import argparse
import hashlib
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
FIXTURES_KT = ROOT / "compose/shared/src/commonTest/kotlin/build/raft/app/data/runtimeform/v2/RuntimeFormV2Fixtures.kt"
FIXTURES_V3_KT = ROOT / "compose/shared/src/commonTest/kotlin/build/raft/app/data/runtimeform/v2/RuntimeFormV3Fixtures.kt"
CONTRACT_KT = ROOT / "compose/shared/src/commonMain/kotlin/build/raft/app/data/runtimeform/v2/RuntimeFormV2.kt"
FIXTURES = [
    ("BUILTIN_V2_FORM_JSON", "builtin.form.json"),
    ("BUILTIN_V2_EDIT_JSON", "builtin.edit.json"),
    ("KIMI_SDK_V2_FORM_JSON", "kimi-sdk.form.json"),
    ("KIMI_SDK_V2_EDIT_JSON", "kimi-sdk.edit.json"),
    ("CAPABILITIES_V2_FORM_JSON", "capabilities.form.json"),
    # Batch 2 (slock #8612): OpenCode create/edit; retired Kimi CLI / Gemini / Antigravity edit only;
    # option sources incl. the built-in list the server returns when probing did not succeed.
    ("OPENCODE_V2_FORM_JSON", "opencode.form.json"),
    ("OPENCODE_V2_EDIT_JSON", "opencode.edit.json"),
    ("OPENCODE_V2_FALLBACK_SOURCE_JSON", "opencode.option-source.fallback.json"),
    ("KIMI_CLI_V2_EDIT_JSON", "kimi.edit.json"),
    ("KIMI_CLI_V2_FALLBACK_SOURCE_JSON", "kimi.option-source.fallback.json"),
    ("GEMINI_V2_EDIT_JSON", "gemini.edit.json"),
    ("GEMINI_V2_SOURCE_JSON", "gemini.option-source.json"),
    ("ANTIGRAVITY_V2_EDIT_JSON", "antigravity.edit.json"),
    # Capability `choice.labels` (slock #8625): label priority, description, ignored keys.
    ("CHOICES_V2_FORM_JSON", "choices.form.json"),
    ("CHOICES_V2_SOURCE_JSON", "choices.option-source.json"),
    # Batch 3a (slock #8625): Codex (custom value + choice labels + source status) and Grok.
    ("CODEX_V2_FORM_JSON", "codex.form.json"),
    ("CODEX_V2_EDIT_JSON", "codex.edit.json"),
    ("CODEX_V2_LIVE_SOURCE_JSON", "codex.option-source.live.json"),
    ("CODEX_V2_FALLBACK_SOURCE_JSON", "codex.option-source.fallback.json"),
    ("CODEX_V2_UNAVAILABLE_SOURCE_JSON", "codex.option-source.unavailable.json"),
    ("GROK_V2_FORM_JSON", "grok.form.json"),
    # Batch 3b (slock #8637): Claude (provider, custom API url/key, write-only key), Cursor, Copilot.
    ("CLAUDE_V2_FORM_JSON", "claude.form.json"),
    ("CLAUDE_V2_EDIT_JSON", "claude.edit.json"),
    ("CLAUDE_V2_PROVIDER_SOURCE_JSON", "claude.option-source.provider.json"),
    ("CLAUDE_V2_FALLBACK_SOURCE_JSON", "claude.option-source.fallback.json"),
    ("CURSOR_V2_FORM_JSON", "cursor.form.json"),
    ("CURSOR_V2_EDIT_JSON", "cursor.edit.json"),
    ("CURSOR_V2_FALLBACK_SOURCE_JSON", "cursor.option-source.fallback.json"),
    # slock #9013 deprecated Copilot for new agents: no create form, edit only.
    ("COPILOT_V2_EDIT_JSON", "copilot.edit.json"),
    ("COPILOT_V2_LIVE_SOURCE_JSON", "copilot.option-source.live.json"),
    # Batch 4 (slock #8647): Pi, "configured" (Computer's own models, typed allowed) or DeepSeek (per-provider list).
    ("PI_V2_FORM_JSON", "pi.form.json"),
    ("PI_V2_EDIT_JSON", "pi.edit.json"),
    ("PI_V2_EDIT_CONFIGURED_JSON", "pi.edit.configured.json"),
    ("PI_V2_PROVIDER_SOURCE_JSON", "pi.option-source.provider.json"),
    ("PI_V2_PROVIDER_MODEL_SOURCE_JSON", "pi.option-source.provider-model.json"),
    ("PI_V2_FALLBACK_SOURCE_JSON", "pi.option-source.fallback.json"),
]
# Protocol v3 (slock #14471, README "Protocol v3"): server-produced v3 bodies and the shared
# condition conformance table every client runs.
FIXTURES_V3 = [
    ("V3_CONDITIONS_CONFORMANCE_JSON", "v3.conditions.conformance.json"),
    ("BUILTIN_V3_FORM_JSON", "builtin.form.v3.json"),
    ("BUILTIN_V3_FORM_SAVED_CONNECTIONS_JSON", "builtin.form.saved-connections.v3.json"),
    ("BUILTIN_V3_EDIT_CONNECTION_UNAVAILABLE_JSON", "builtin.edit.connection-unavailable.v3.json"),
    ("BUILTIN_V3_PROVIDER_SAVED_CONNECTIONS_SOURCE_JSON", "builtin.option-source.provider.saved-connections.v3.json"),
    ("CODEX_V3_FORM_JSON", "codex.form.v3.json"),
    ("CODEX_V3_EDIT_JSON", "codex.edit.v3.json"),
    ("CODEX_V3_REASONING_EFFORT_SOURCE_JSON", "codex.option-source.reasoning-effort.v3.json"),
    ("GROK_V3_FORM_JSON", "grok.form.v3.json"),
    ("CLAUDE_V3_FORM_JSON", "claude.form.v3.json"),
    ("CLAUDE_V3_EDIT_JSON", "claude.edit.v3.json"),
    ("CLAUDE_V3_REASONING_EFFORT_SOURCE_JSON", "claude.option-source.reasoning-effort.v3.json"),
    ("COPILOT_V3_EDIT_JSON", "copilot.edit.v3.json"),
    ("KIMI_SDK_V3_FORM_JSON", "kimi-sdk.form.v3.json"),
    ("KIMI_SDK_V3_EDIT_JSON", "kimi-sdk.edit.v3.json"),
    ("KIMI_SDK_V3_REASONING_EFFORT_SOURCE_JSON", "kimi-sdk.option-source.reasoning-effort.v3.json"),
    ("PI_V3_FORM_JSON", "pi.form.v3.json"),
    ("PI_V3_EDIT_JSON", "pi.edit.v3.json"),
    ("PI_V3_REASONING_EFFORT_SOURCE_JSON", "pi.option-source.reasoning-effort.v3.json"),
    ("PI_V3_PROVIDER_REASONING_EFFORT_SOURCE_JSON", "pi.option-source.provider-reasoning-effort.v3.json"),
    # Model sources (same body on v2 and v3) the v3 effort sources are keyed by; Claude's carries a
    # disabled model in unavailableOptions on a source that also takes typed values.
    ("CODEX_MODEL_LIVE_SOURCE_JSON", "codex.option-source.live.json"),
    ("CLAUDE_MODEL_LIVE_SOURCE_JSON", "claude.option-source.live.json"),
    ("CLAUDE_PROVIDER_SOURCE_JSON", "claude.option-source.provider.json"),
    ("CLAUDE_AUTH_TYPE_SOURCE_JSON", "claude.option-source.auth-type.json"),
]


def render(slock: pathlib.Path, fixtures, what: str, slock_sha: str | None = None) -> str:
    out = [
        "package build.raft.app.data.runtimeform.v2",
        "",
        "// Generated by scripts/runtime_form/sync_v2_fixtures.py from slock",
        f"// packages/runtime-form/fixtures/ ({what}). Do not edit;",
        "// re-run the script when slock regenerates its fixtures.",
    ]
    if slock_sha:
        out.append(f"// Pinned to botiverse/slock {slock_sha}.")
    for name, file in fixtures:
        body = (slock / "packages/runtime-form/fixtures" / file).read_text(encoding="utf-8")
        if '"""' in body:
            sys.exit(f"{file}: cannot embed in a Kotlin raw string")
        digest = hashlib.sha256(body.encode("utf-8")).hexdigest()
        out += ["", f"/** fixtures/{file}, sha256 {digest} */"]
        if "$" in body:
            escaped = body.replace("$", "${'$'}")
            out.append(f'internal val {name}: String = """{escaped}"""')
        else:
            out.append(f'internal const val {name}: String = """{body}"""')
    return "\n".join(out) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--slock", required=True, type=pathlib.Path)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--part", choices=["all", "v2", "v3"], default="all")
    parser.add_argument("--slock-sha", help="botiverse/slock commit the fixtures come from (recorded in the v3 file)")
    args = parser.parse_args()
    wanted = {CONTRACT_KT: (args.slock / "packages/runtime-form/generated/kotlin/RuntimeFormV2.kt").read_text(encoding="utf-8")}
    if args.part in ("all", "v2"):
        wanted[FIXTURES_KT] = render(args.slock, FIXTURES, "the server's exact v2 bodies")
    if args.part in ("all", "v3"):
        wanted[FIXTURES_V3_KT] = render(args.slock, FIXTURES_V3, "the server's exact v3 bodies and the v3 condition conformance table", args.slock_sha)
    stale = [path for path, text in wanted.items() if not path.exists() or path.read_text(encoding="utf-8") != text]
    if args.check:
        for path in stale:
            print(f"stale: {path.relative_to(ROOT)}")
        return 1 if stale else 0
    for path in stale:
        path.write_text(wanted[path], encoding="utf-8")
        print(f"updated: {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
