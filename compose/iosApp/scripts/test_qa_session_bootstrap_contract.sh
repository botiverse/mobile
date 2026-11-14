#!/usr/bin/env bash
# Unit tests for the iOS QA session bootstrap contract (RaftIosQaSessionBootstrapContract.swift):
# launch arguments, the one-shot private inbox, and envelope digest/shape/binding validation.
# Also proves the #if gate: the same file compiled WITHOUT RAFT_QA_SESSION_BOOTSTRAP (the Release
# configuration) yields an object that carries none of the QA bootstrap markers. macOS host, swiftc.
set -euo pipefail

IOS_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$IOS_ROOT/iosApp/RaftIosQaSessionBootstrapContract.swift"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

cat > "$TMP_DIR/main.swift" <<'SWIFT'
import Darwin
import Foundation

typealias C = RaftIosQaSessionBootstrapContract

var failures = 0
func check(_ condition: Bool, _ name: String, line: Int = #line) {
    if !condition {
        failures += 1
        print("FAIL line \(line): \(name)")
    }
}

// MARK: launch arguments
let digest64 = "sha256:" + String(repeating: "a", count: 64)
check(C.parseLaunchArguments(["iosApp"]) == .absent, "no flags is absent")
check(
    C.parseLaunchArguments(["iosApp", "-qaBootstrapRequestId", "req-1.a_b", "-qaBootstrapEnvelopeDigest", digest64])
        == .request(C.LaunchRequest(requestId: "req-1.a_b", envelopeDigest: digest64)),
    "valid flags parse"
)
check(C.parseLaunchArguments(["iosApp", "-qaBootstrapRequestId", "req"]) == .rejected("arguments"), "missing digest flag")
check(C.parseLaunchArguments(["iosApp", "-qaBootstrapRequestId"]) == .rejected("arguments"), "flag without value and no digest")
check(
    C.parseLaunchArguments(["iosApp", "-qaBootstrapEnvelopeDigest", digest64, "-qaBootstrapRequestId"]) == .rejected("request_id"),
    "request id flag without value"
)
check(
    C.parseLaunchArguments(["iosApp", "-qaBootstrapRequestId", "a", "-qaBootstrapRequestId", "b", "-qaBootstrapEnvelopeDigest", digest64])
        == .rejected("arguments"),
    "duplicate request id flag"
)
check(
    C.parseLaunchArguments(["iosApp", "-qaBootstrapRequestId", "../x", "-qaBootstrapEnvelopeDigest", digest64]) == .rejected("request_id"),
    "path traversal request id"
)
check(
    C.parseLaunchArguments(["iosApp", "-qaBootstrapRequestId", "r", "-qaBootstrapEnvelopeDigest", "sha256:" + String(repeating: "A", count: 64)])
        == .rejected("envelope_digest"),
    "uppercase digest rejected"
)
check(C.isValidRequestId(String(repeating: "x", count: 80)), "80-char request id")
check(!C.isValidRequestId(String(repeating: "x", count: 81)), "81-char request id")
check(!C.isValidRequestId(""), "empty request id")
check(!C.isValidRequestId("a/b"), "slash request id")
check(!C.isValidRequestId("a b"), "space request id")
check(!C.isValidDigest("sha256:" + String(repeating: "a", count: 63)), "short digest")
check(!C.isValidDigest("sha1:" + String(repeating: "a", count: 64)), "wrong algorithm")
check(C.sha256Digest(Data("abc".utf8)) == "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "sha256 vector")

// MARK: binding
check(
    C.bootGeneration(simulatorUdid: "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0", launchdSimStartSeconds: 1_731_500_000)
        == C.sha256Digest(Data("ios-simulator-boot/v2|0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0|1731500000".utf8)),
    "boot generation formula (uppercased udid, launchd_sim start)"
)
check(
    C.bootGeneration(simulatorUdid: "0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0", launchdSimStartSeconds: 1_731_500_001)
        != C.bootGeneration(simulatorUdid: "0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0", launchdSimStartSeconds: 1_731_500_000),
    "a new launchd_sim start is a new boot generation"
)
let udid = "0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0"
check(C.simulatorUdid(environment: ["SIMULATOR_UDID": udid.lowercased()], homeDirectory: "/") == udid, "udid from env")
check(
    C.simulatorUdid(
        environment: [:],
        homeDirectory: "/Users/runner/Library/Developer/CoreSimulator/Devices/\(udid)/data/Containers/Data/Application/X"
    ) == udid,
    "udid from home path"
)
check(C.simulatorUdid(environment: ["SIMULATOR_UDID": "nope"], homeDirectory: "/var/mobile") == nil, "no udid on device")
let me = C.processRecord(pid: getpid())
check(me != nil && me!.startSeconds > 0 && me!.parentPid == getppid(), "own process record readable")
check(me?.name == "contract-tests", "own process name from p_comm")
check((me?.startSeconds ?? 0) >= (C.processRecord(pid: getppid())?.startSeconds ?? Int.max), "child starts no earlier than its parent")
check(C.processRecord(pid: -1) == nil, "invalid pid has no record")
check(C.launchdSimStartSeconds() == nil, "no launchd_sim ancestor on the macOS host")

let exeDir = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true).appendingPathComponent("bundle", isDirectory: true)
try! FileManager.default.createDirectory(at: exeDir, withIntermediateDirectories: true)
let exe = exeDir.appendingPathComponent("iosApp")
FileManager.default.createFile(atPath: exe.path, contents: Data("stub".utf8))
check(C.codeIdentityDigest(executableURL: exe) == C.sha256Digest(Data("stub".utf8)), "code identity without debug dylib")
FileManager.default.createFile(atPath: exe.path + ".debug.dylib", contents: Data("code".utf8))
check(C.codeIdentityDigest(executableURL: exe) == C.sha256Digest(Data("stubcode".utf8)), "code identity covers the debug dylib")
check(C.codeIdentityDigest(executableURL: exeDir.appendingPathComponent("missing")) == nil, "missing executable")

// MARK: inbox
let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
let inbox = C.inboxDirectory(applicationSupport: root)
try! FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])

@discardableResult
func put(_ name: String, _ bytes: Data, mode: mode_t = 0o600) -> String {
    let path = inbox.appendingPathComponent(name).path
    unlink(path)
    FileManager.default.createFile(atPath: path, contents: bytes)
    chmod(path, mode)
    return path
}

let payload = Data(#"{"k":"v"}"#.utf8)
let okPath = put("ok.session", payload)
check(C.consumeInbox(directory: inbox, requestId: "ok") == .consumed(payload), "consume regular 0600 file")
check(access(okPath, F_OK) != 0, "consumed file is removed")
check(C.consumeInbox(directory: inbox, requestId: "ok") == .rejected(.missing), "second consume is missing (one-shot)")
check(C.consumeInbox(directory: inbox, requestId: "bad/../x") == .rejected(.requestId), "invalid request id")
check(C.consumeInbox(directory: inbox, requestId: "ok", suffix: "ticket") == .rejected(.suffix), "only the session suffix")
let widePath = put("wide.session", payload, mode: 0o644)
check(C.consumeInbox(directory: inbox, requestId: "wide") == .rejected(.mode), "0644 rejected")
check(access(widePath, F_OK) == 0, "rejected-before-open file stays (nothing consumed)")
put("exec.session", payload, mode: 0o700)
check(C.consumeInbox(directory: inbox, requestId: "exec") == .rejected(.mode), "0700 rejected")
put("empty.session", Data())
check(C.consumeInbox(directory: inbox, requestId: "empty") == .rejected(.size), "empty rejected")
put("big.session", Data(repeating: 0x20, count: C.maxEnvelopeBytes + 1))
check(C.consumeInbox(directory: inbox, requestId: "big") == .rejected(.size), "oversize rejected")
put("max.session", Data(repeating: 0x20, count: C.maxEnvelopeBytes))
check(C.consumeInbox(directory: inbox, requestId: "max") == .consumed(Data(repeating: 0x20, count: C.maxEnvelopeBytes)), "16 KiB accepted")
let target = put("target.json", payload)
symlink(target, inbox.appendingPathComponent("link.session").path)
check(C.consumeInbox(directory: inbox, requestId: "link") == .rejected(.notRegular), "symlink rejected")
mkdir(inbox.appendingPathComponent("dir.session").path, 0o700)
check(C.consumeInbox(directory: inbox, requestId: "dir") == .rejected(.notRegular), "directory rejected")
check(
    C.consumeInbox(directory: root.appendingPathComponent("absent", isDirectory: true), requestId: "ok") == .rejected(.missing),
    "missing inbox directory"
)

// MARK: envelope
let binding = C.Binding(
    applicationId: "build.raft.app",
    codeIdentitySha256: "sha256:" + String(repeating: "c", count: 64),
    bootGeneration: "sha256:" + String(repeating: "b", count: 64)
)
func envelope(_ edit: (inout [String: Any]) -> Void = { _ in }) -> Data {
    var value: [String: Any] = [
        "version": "qa-bootstrap-login/v1",
        "run_case_id": "run-1",
        "attempt": 1,
        "ci_job_ref": "https://github.com/botiverse/mobile/actions/runs/1",
        "principal_id": "user-1",
        "server_id": "server-1",
        "target_route": "compose_slock_app_shell",
        "application_id": binding.applicationId,
        "signing_cert_sha256": binding.codeIdentitySha256,
        "boot_generation": binding.bootGeneration,
        "user": ["id": "user-1", "name": "QA", "email": "qa@example.invalid"],
        "access_token": "access",
        "refresh_token": "refresh",
    ]
    edit(&value)
    return try! JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
}
func verdict(_ bytes: Data, digest: String? = nil) -> C.Failure? {
    C.validateEnvelope(bytes, expectedDigest: digest ?? C.sha256Digest(bytes), binding: binding)
}
func failure(_ stage: String, _ code: String) -> C.Failure { C.Failure(stage: stage, code: code) }

check(verdict(envelope()) == nil, "valid envelope")
check(verdict(envelope(), digest: digest64) == failure("envelope_digest", "mismatch"), "digest mismatch")
check(verdict(envelope(), digest: "sha256:XYZ") == failure("envelope_digest", "format"), "digest format")
check(verdict(Data("not json".utf8)) == failure("envelope_shape", "json"), "not json")
check(verdict(Data("[1]".utf8)) == failure("envelope_shape", "json"), "json array")
check(verdict(envelope { $0["extra"] = "x" }) == failure("envelope_shape", "keys"), "extra key")
check(verdict(envelope { $0.removeValue(forKey: "refresh_token") }) == failure("envelope_shape", "keys"), "missing key")
check(verdict(envelope { $0["version"] = "qa-bootstrap-login/v2" }) == failure("envelope_shape", "version"), "version")
check(verdict(envelope { $0["run_case_id"] = " " }) == failure("envelope_shape", "run_case_id"), "blank run case")
check(verdict(envelope { $0["attempt"] = 0 }) == failure("envelope_shape", "attempt"), "attempt 0")
check(verdict(envelope { $0["attempt"] = 1.5 }) == failure("envelope_shape", "attempt"), "fractional attempt")
check(verdict(envelope { $0["attempt"] = true }) == failure("envelope_shape", "attempt"), "boolean attempt")
check(verdict(envelope { $0["attempt"] = "1" }) == failure("envelope_shape", "attempt"), "string attempt")
check(verdict(envelope { $0["ci_job_ref"] = "" }) == failure("envelope_shape", "ci_job_ref"), "empty ci job ref")
check(verdict(envelope { $0["principal_id"] = "user-2" }) == failure("envelope_shape", "principal"), "principal != user.id")
check(verdict(envelope { $0["user"] = "user-1" }) == failure("envelope_shape", "principal"), "user not an object")
check(verdict(envelope { $0["server_id"] = "" }) == failure("envelope_shape", "server_id"), "empty server")
check(verdict(envelope { $0["target_route"] = "compose_login" }) == failure("envelope_shape", "target_route"), "route")
check(verdict(envelope { $0["access_token"] = "" }) == failure("envelope_shape", "access_token"), "empty access token")
check(verdict(envelope { $0["refresh_token"] = NSNull() }) == failure("envelope_shape", "refresh_token"), "null refresh token")
check(verdict(envelope { $0["application_id"] = "build.raft.app.other" }) == failure("envelope_binding", "application_id"), "app id")
check(
    verdict(envelope { $0["signing_cert_sha256"] = "sha256:" + String(repeating: "d", count: 64) }) == failure("envelope_binding", "code_identity"),
    "code identity"
)
check(
    verdict(envelope { $0["boot_generation"] = "sha256:" + String(repeating: "e", count: 64) }) == failure("envelope_binding", "boot_generation"),
    "boot generation"
)

if failures > 0 {
    print("qa session bootstrap contract: \(failures) failure(s)")
    exit(1)
}
print("qa session bootstrap contract: PASS")
SWIFT

SWIFTC="${SWIFTC:-$(xcrun --find swiftc 2>/dev/null || command -v swiftc)}"
SDK_ARGS=()
if command -v xcrun >/dev/null 2>&1; then
  SDK_ARGS=(-sdk "$(xcrun --sdk macosx --show-sdk-path)")
fi

"$SWIFTC" "${SDK_ARGS[@]}" -D RAFT_QA_SESSION_BOOTSTRAP -warnings-as-errors \
  "$SOURCE" "$TMP_DIR/main.swift" -o "$TMP_DIR/contract-tests"
mkdir -p "$TMP_DIR/app-support"
"$TMP_DIR/contract-tests" "$TMP_DIR/app-support"

# Release configuration: no RAFT_QA_SESSION_BOOTSTRAP, so the file compiles to nothing.
"$SWIFTC" "${SDK_ARGS[@]}" -parse-as-library -warnings-as-errors -c "$SOURCE" -o "$TMP_DIR/release.o"
if grep -aqE 'raft-ios-qa-session-bootstrap|qaBootstrapRequestId|qa-bootstrap/inbox|RaftIosQaSessionBootstrap' "$TMP_DIR/release.o"; then
  echo "qa session bootstrap contract: Release-configuration object still carries QA bootstrap code" >&2
  exit 1
fi
echo "qa session bootstrap contract: Release-configuration object is empty of QA bootstrap code"
