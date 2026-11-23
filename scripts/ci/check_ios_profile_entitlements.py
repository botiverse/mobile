#!/usr/bin/env python3
"""Fail early when the iOS release provisioning profile can't sign the app's entitlements.

The app declares capabilities in compose/iosApp/iosApp/iosApp.entitlements (Associated
Domains `applinks:app.raft.build` / `applinks:open.raft.build` since 1.24, and
`webcredentials:app.raft.build` for native passkeys since 1.27, Sign in with
Apple, Push). A profile generated before a capability was enabled on the App ID lacks the
matching entitlement. Before this check, ios-release.yml found out ~10 minutes later in
"Archive app" (run #137, 1.16.0: a late, cryptic failure) and then silently stripped
Associated Domains so 1.16.0 shipped without Universal Links (run #138). Now the preflight
fails in the first minute with the profile, the missing capability and how to fix it.

usage:
  check_ios_profile_entitlements.py profile PROFILE_PLIST ENTITLEMENTS
      PROFILE_PLIST: `security cms -D -i X.mobileprovision` output.
  check_ios_profile_entitlements.py signed SIGNED_ENTITLEMENTS_PLIST ENTITLEMENTS
      SIGNED_ENTITLEMENTS_PLIST: `codesign -d --entitlements - --xml X.app` output;
      checks the exported app really carries every list-valued entitlement (domains).
  check_ios_profile_entitlements.py --self-test

Prints no secrets: only the profile name/UUID, bundle id (team prefix dropped) and
entitlement keys/values, which are all public in the shipped app.
"""
import contextlib
import fnmatch
import io
import plistlib
import sys
import tempfile
from pathlib import Path

# Entitlement key -> Apple Developer portal capability name.
CAPABILITIES = {
    "com.apple.developer.associated-domains": "Associated Domains",
    "com.apple.developer.applesignin": "Sign In with Apple",
    "aps-environment": "Push Notifications",
    "com.apple.security.application-groups": "App Groups",
}

SECRET_NAME = "SLOCK_IOS_PROVISIONING_PROFILE_BASE64"


def _as_list(value) -> list:
    if isinstance(value, list):
        return value
    return [value]


def _covered(requested: str, granted: list) -> bool:
    # Profiles grant either the exact value, a glob (`applinks:*.raft.build`) or `*`.
    return any(isinstance(g, str) and (g == requested or fnmatch.fnmatchcase(requested, g)) for g in granted)


def missing_entitlements(granted: dict, requested: dict) -> list[tuple[str, list]]:
    """(key, missing values) for every requested entitlement the grant does not cover.

    An empty value list means the key itself is absent. Scalar values that are build
    settings (`$(APS_ENVIRONMENT)`) are only checked for presence.
    """
    problems: list[tuple[str, list]] = []
    for key, value in requested.items():
        if key not in granted:
            problems.append((key, []))
            continue
        if not isinstance(value, list):
            continue
        allowed = _as_list(granted[key])
        missing = [v for v in value if isinstance(v, str) and not _covered(v, allowed)]
        if missing:
            problems.append((key, missing))
    return problems


def _bundle_id(profile: dict) -> str:
    app_id = str(profile.get("Entitlements", {}).get("application-identifier", ""))
    return app_id.split(".", 1)[1] if "." in app_id else app_id


def describe_profile_problems(
    profile: dict,
    problems: list[tuple[str, list]],
    entitlements_path: str,
    secret_name: str = SECRET_NAME,
) -> list[str]:
    name = profile.get("Name", "?")
    uuid = profile.get("UUID", "?")
    bundle_id = _bundle_id(profile) or "build.raft.app"
    lines = []
    for key, values in problems:
        capability = CAPABILITIES.get(key, key)
        if values:
            what = f"covers {key} but not {', '.join(values)}"
        else:
            what = f"has no {key} entitlement"
        lines.append(
            f"Provisioning profile '{name}' (UUID {uuid}, App ID {bundle_id}) {what}, "
            f"which {entitlements_path} requires ({capability} capability)."
        )
    capabilities = sorted({CAPABILITIES.get(k, k) for k, _ in problems})
    lines.append(
        "Fix: Apple Developer portal > Certificates, Identifiers & Profiles > Identifiers > "
        f"{bundle_id} > enable {', '.join(capabilities)} > Save; then Profiles > '{name}' > "
        "Edit > Save (the old profile is invalidated by the capability change) > Download; then "
        f"`base64 -i <profile>.mobileprovision | gh secret set {secret_name} -R botiverse/mobile` "
        "and re-run ios-release.yml. Do not remove the entitlement from the app to get past this."
    )
    return lines


def _load(path: str) -> dict:
    with open(path, "rb") as f:
        data = plistlib.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"{path}: not a plist dictionary")
    return data


def run(mode: str, granted_path: str, entitlements_path: str, secret_name: str = SECRET_NAME) -> int:
    requested = _load(entitlements_path)
    granted_doc = _load(granted_path)
    if mode == "profile":
        granted = granted_doc.get("Entitlements")
        if not isinstance(granted, dict):
            print(f"::error::{granted_path} has no Entitlements dictionary; is it `security cms -D` output?")
            return 1
        problems = missing_entitlements(granted, requested)
        if problems:
            for line in describe_profile_problems(granted_doc, problems, entitlements_path, secret_name=secret_name):
                print(f"::error::{line}")
            return 1
        print(
            f"Provisioning profile '{granted_doc.get('Name', '?')}' covers every entitlement in "
            f"{entitlements_path}: {', '.join(sorted(requested))}"
        )
        return 0
    if mode == "signed":
        problems = missing_entitlements(granted_doc, requested)
        if problems:
            for key, values in problems:
                detail = ", ".join(values) if values else "key absent"
                print(f"::error::Signed app is missing {key} from {entitlements_path} ({detail}).")
            return 1
        print(f"Signed app carries every entitlement in {entitlements_path}.")
        return 0
    print(__doc__, file=sys.stderr)
    return 2


def self_test() -> int:
    source = {
        "aps-environment": "$(APS_ENVIRONMENT)",
        "com.apple.developer.associated-domains": ["applinks:app.raft.build", "applinks:open.raft.build"],
        "com.apple.developer.applesignin": ["Default"],
        "com.apple.security.application-groups": ["group.build.raft.app.external-share"],
    }
    full = {
        "application-identifier": "TEAMID.build.raft.app",
        "aps-environment": "production",
        "com.apple.developer.associated-domains": "*",
        "com.apple.developer.applesignin": ["Default"],
        "com.apple.security.application-groups": ["group.build.raft.app.external-share"],
    }
    assert missing_entitlements(full, source) == []
    explicit = dict(full, **{"com.apple.developer.associated-domains": ["applinks:app.raft.build", "applinks:open.raft.build"]})
    assert missing_entitlements(explicit, source) == []
    glob = dict(full, **{"com.apple.developer.associated-domains": ["applinks:*.raft.build"]})
    assert missing_entitlements(glob, source) == []
    partial = dict(full, **{"com.apple.developer.associated-domains": ["applinks:app.raft.build"]})
    assert missing_entitlements(partial, source) == [("com.apple.developer.associated-domains", ["applinks:open.raft.build"])]
    without = {k: v for k, v in full.items() if k != "com.apple.developer.associated-domains"}
    assert missing_entitlements(without, source) == [("com.apple.developer.associated-domains", [])]
    no_push = {k: v for k, v in full.items() if k != "aps-environment"}
    assert missing_entitlements(no_push, source) == [("aps-environment", [])]
    no_group = {k: v for k, v in full.items() if k != "com.apple.security.application-groups"}
    assert missing_entitlements(no_group, source) == [("com.apple.security.application-groups", [])]

    profile = {"Name": "Raft App Store build.raft.app", "UUID": "u-1", "Entitlements": without}
    text = "\n".join(describe_profile_problems(profile, missing_entitlements(without, source), "iosApp.entitlements"))
    assert "Associated Domains" in text and "Raft App Store build.raft.app" in text and SECRET_NAME in text
    assert "TEAMID" not in text, text
    share_profile = {"Name": "Raft Share Extension", "UUID": "u-2", "Entitlements": no_group}
    share_text = "\n".join(
        describe_profile_problems(
            share_profile,
            missing_entitlements(no_group, {"com.apple.security.application-groups": ["group.build.raft.app.external-share"]}),
            "ShareExtension.entitlements",
            secret_name="SLOCK_IOS_SHARE_EXTENSION_PROFILE_BASE64",
        )
    )
    assert "App Groups" in share_text and "SLOCK_IOS_SHARE_EXTENSION_PROFILE_BASE64" in share_text

    with tempfile.TemporaryDirectory() as tmp:
        ent = Path(tmp, "app.entitlements")
        ent.write_bytes(plistlib.dumps(source))
        good = Path(tmp, "good.plist")
        good.write_bytes(plistlib.dumps({"Name": "ok", "UUID": "u", "Entitlements": full}))
        bad = Path(tmp, "bad.plist")
        bad.write_bytes(plistlib.dumps(profile))
        signed = Path(tmp, "signed.plist")
        signed.write_bytes(plistlib.dumps(explicit))
        signed_bad = Path(tmp, "signed-bad.plist")
        signed_bad.write_bytes(plistlib.dumps(without))
        # Keep the expected ::error:: lines out of the CI log (they would become annotations).
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            assert run("profile", str(good), str(ent)) == 0
            assert run("profile", str(bad), str(ent)) == 1
            assert run("signed", str(signed), str(ent)) == 0
            assert run("signed", str(signed_bad), str(ent)) == 1
        assert "::error::Provisioning profile 'Raft App Store build.raft.app'" in out.getvalue()

    # The real app & share extension entitlements declare the domains and App Group this check exists for.
    repo_root = Path(__file__).resolve().parents[2]
    repo_ent = _load(str(repo_root / "compose/iosApp/iosApp/iosApp.entitlements"))
    domains = repo_ent.get("com.apple.developer.associated-domains", [])
    assert "applinks:app.raft.build" in domains and "applinks:open.raft.build" in domains, domains
    # Native passkeys (RaftNativePasskeyAssertion.ios.kt, the current RP ID app.raft.build).
    assert "webcredentials:app.raft.build" in domains, domains
    groups = repo_ent.get("com.apple.security.application-groups", [])
    assert "group.build.raft.app.external-share" in groups, groups
    share_ent = _load(str(repo_root / "compose/iosApp/ShareExtension/ShareExtension.entitlements"))
    share_groups = share_ent.get("com.apple.security.application-groups", [])
    assert "group.build.raft.app.external-share" in share_groups, share_groups
    print("check_ios_profile_entitlements self-test: OK")
    return 0


def main(argv: list[str]) -> int:
    if argv[1:] == ["--self-test"]:
        return self_test()
    secret_name = SECRET_NAME
    args = argv[1:]
    if len(args) == 5 and args[1] == "--secret-name":
        mode, _, secret_name, granted_path, entitlements_path = args
        return run(mode, granted_path, entitlements_path, secret_name=secret_name)
    if len(args) == 3:
        return run(args[0], args[1], args[2], secret_name=secret_name)
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
