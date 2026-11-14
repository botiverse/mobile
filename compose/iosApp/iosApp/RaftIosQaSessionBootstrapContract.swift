#if RAFT_QA_SESSION_BOOTSTRAP
// QA session bootstrap (qa-bootstrap-login/v1) on the iOS Simulator: the iOS twin of Android's
// QaSessionBootstrapInbox and the checks in QaSessionBootstrapActivity. Compiled only into the Debug
// configuration (SWIFT_ACTIVE_COMPILATION_CONDITIONS gains RAFT_QA_SESSION_BOOTSTRAP there and nowhere
// else); Release, TestFlight and App Store builds contain none of this file
// (scripts/ci/check_ios_qa_bootstrap_exclusion.sh). Foundation/CryptoKit/Darwin only, so
// compose/iosApp/scripts/test_qa_session_bootstrap_contract.sh compiles and tests it with swiftc.
// Contract: docs/qa-acceptance/session-bootstrap.md.

import CryptoKit
import Darwin
import Foundation

enum RaftIosQaSessionBootstrapContract {
    /// Marker for the release-exclusion binary check; appears in the QA_BOOTSTRAP_DIAG line.
    static let protocolLabel = "raft-ios-qa-session-bootstrap/v1"
    static let envelopeVersion = "qa-bootstrap-login/v1"
    static let targetRoute = "compose_slock_app_shell"
    static let requestIdArgument = "-qaBootstrapRequestId"
    static let envelopeDigestArgument = "-qaBootstrapEnvelopeDigest"
    /// Under the app data container's Library/Application Support.
    static let inboxRelativePath = "qa-bootstrap/inbox"
    static let envelopeSuffix = "session"
    static let maxEnvelopeBytes = 16 * 1024
    static let envelopeKeys: Set<String> = [
        "version", "run_case_id", "attempt", "ci_job_ref", "principal_id", "server_id", "target_route",
        "application_id", "signing_cert_sha256", "boot_generation", "user", "access_token", "refresh_token",
    ]

    struct Failure: Error, Equatable {
        let stage: String
        let code: String
    }

    // MARK: Launch arguments

    struct LaunchRequest: Equatable {
        let requestId: String
        let envelopeDigest: String
    }

    enum LaunchParse: Equatable {
        case absent
        case request(LaunchRequest)
        case rejected(String)
    }

    /// `simctl launch <udid> <bundle> -qaBootstrapRequestId <id> -qaBootstrapEnvelopeDigest sha256:<hex>`.
    static func parseLaunchArguments(_ arguments: [String]) -> LaunchParse {
        let requestIds = values(after: requestIdArgument, in: arguments)
        let digests = values(after: envelopeDigestArgument, in: arguments)
        if requestIds.isEmpty && digests.isEmpty { return .absent }
        guard requestIds.count == 1, digests.count == 1 else { return .rejected("arguments") }
        guard let requestId = requestIds[0], isValidRequestId(requestId) else { return .rejected("request_id") }
        guard let digest = digests[0], isValidDigest(digest) else { return .rejected("envelope_digest") }
        return .request(LaunchRequest(requestId: requestId, envelopeDigest: digest))
    }

    /// One entry per occurrence of [flag]: the following argument, or nil when the flag is last.
    private static func values(after flag: String, in arguments: [String]) -> [String?] {
        var found: [String?] = []
        for index in arguments.indices where arguments[index] == flag {
            let next = index + 1
            found.append(next < arguments.count ? arguments[next] : nil)
        }
        return found
    }

    /// Same as Android's QaSessionBootstrapInbox: `[A-Za-z0-9._-]{1,80}`.
    static func isValidRequestId(_ value: String) -> Bool {
        guard (1...80).contains(value.utf8.count) else { return false }
        return value.utf8.allSatisfy { byte in
            (byte >= 0x30 && byte <= 0x39) || (byte >= 0x41 && byte <= 0x5A) || (byte >= 0x61 && byte <= 0x7A) ||
                byte == 0x2E || byte == 0x5F || byte == 0x2D
        }
    }

    /// `sha256:` + 64 lowercase hex, the only digest form the harness and the shared consumer accept.
    static func isValidDigest(_ value: String) -> Bool {
        let prefix = "sha256:"
        guard value.hasPrefix(prefix) else { return false }
        let hex = value.utf8.dropFirst(prefix.utf8.count)
        return hex.count == 64 && hex.allSatisfy { ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x61 && $0 <= 0x66) }
    }

    static func sha256Digest(_ data: Data) -> String {
        "sha256:" + SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    // MARK: Private inbox (one-shot)

    enum InboxRejection: String {
        case requestId = "request_id"
        case suffix
        case directory
        case parent
        case missing
        case statDenied = "stat_denied"
        case statError = "stat_error"
        case notRegular = "not_regular"
        case size
        case mode
        case owner
        case changed
        case remove
        case read
        case open
    }

    enum InboxResult: Equatable {
        case consumed(Data)
        case rejected(InboxRejection)
    }

    static func inboxDirectory(applicationSupport: URL) -> URL {
        applicationSupport.appendingPathComponent(inboxRelativePath, isDirectory: true)
    }

    static func inboxFile(directory: URL, requestId: String) -> URL {
        directory.appendingPathComponent("\(requestId).\(envelopeSuffix)", isDirectory: false)
    }

    /// Port of QaSessionBootstrapInbox.consumeDetailed: lstat (no symlinks, regular, 1...16 KiB,
    /// mode exactly 0600, owned by this uid), open O_NOFOLLOW, fstat must match the lstat'd inode,
    /// unlink BEFORE reading (one-shot even when a later check fails), then read exactly st_size bytes.
    static func consumeInbox(directory: URL, requestId: String, suffix: String = envelopeSuffix) -> InboxResult {
        guard isValidRequestId(requestId) else { return .rejected(.requestId) }
        guard suffix == envelopeSuffix else { return .rejected(.suffix) }
        let directoryPath = directory.path
        guard let canonicalDirectory = realPath(directoryPath) else {
            return .rejected(errno == ENOENT ? .missing : .directory)
        }
        let filePath = (directoryPath as NSString).appendingPathComponent("\(requestId).\(suffix)")
        guard realPath((filePath as NSString).deletingLastPathComponent) == canonicalDirectory else {
            return .rejected(.parent)
        }
        var initial = stat()
        if lstat(filePath, &initial) != 0 {
            switch errno {
            case ENOENT: return .rejected(.missing)
            case EACCES, EPERM: return .rejected(.statDenied)
            default: return .rejected(.statError)
            }
        }
        if let rejection = rejection(for: initial) { return .rejected(rejection) }
        let descriptor = open(filePath, O_RDONLY | O_CLOEXEC | O_NOFOLLOW)
        guard descriptor >= 0 else { return .rejected(.open) }
        defer { close(descriptor) }
        var opened = stat()
        guard fstat(descriptor, &opened) == 0 else { return .rejected(.open) }
        if let rejection = rejection(for: opened) { return .rejected(rejection) }
        guard opened.st_dev == initial.st_dev, opened.st_ino == initial.st_ino else { return .rejected(.changed) }
        guard unlink(filePath) == 0 else { return .rejected(.remove) }
        guard let bytes = readExact(descriptor, count: Int(opened.st_size)) else { return .rejected(.read) }
        return .consumed(bytes)
    }

    private static func rejection(for info: stat) -> InboxRejection? {
        if (info.st_mode & S_IFMT) != S_IFREG { return .notRegular }
        if info.st_size < 1 || info.st_size > off_t(maxEnvelopeBytes) { return .size }
        if (info.st_mode & 0o7777) != 0o600 { return .mode }
        if info.st_uid != getuid() { return .owner }
        return nil
    }

    private static func realPath(_ path: String) -> String? {
        guard let resolved = realpath(path, nil) else { return nil }
        defer { free(resolved) }
        return String(cString: resolved)
    }

    private static func readExact(_ descriptor: Int32, count: Int) -> Data? {
        var bytes = [UInt8](repeating: 0, count: count)
        var offset = 0
        while offset < count {
            let read = bytes.withUnsafeMutableBytes { buffer in
                Darwin.read(descriptor, buffer.baseAddress! + offset, count - offset)
            }
            if read <= 0 { return nil }
            offset += read
        }
        var trailing: UInt8 = 0
        guard Darwin.read(descriptor, &trailing, 1) == 0 else { return nil }
        return Data(bytes)
    }

    // MARK: Device binding

    /// What the envelope is bound to on the Simulator. Field names follow the shared envelope:
    /// `signing_cert_sha256` carries the code identity because Simulator builds are not signed with a
    /// distribution certificate (see docs/qa-acceptance/session-bootstrap.md).
    struct Binding: Equatable {
        let applicationId: String
        /// `sha256:` of the installed main executable (CFBundleExecutable) bytes.
        let codeIdentitySha256: String
        /// `bootGeneration(simulatorUdid:hostBootTimeSeconds:)`.
        let bootGeneration: String
    }

    static func bootGeneration(simulatorUdid: String, hostBootTimeSeconds: Int) -> String {
        sha256Digest(Data("ios-simulator-boot/v1|\(simulatorUdid.uppercased())|\(hostBootTimeSeconds)".utf8))
    }

    /// SIMULATOR_UDID from the Simulator launch environment, else the CoreSimulator device directory
    /// in the app's home path (…/CoreSimulator/Devices/<UDID>/data/…).
    static func simulatorUdid(environment: [String: String], homeDirectory: String) -> String? {
        if let value = environment["SIMULATOR_UDID"], UUID(uuidString: value) != nil {
            return value.uppercased()
        }
        let components = (homeDirectory as NSString).pathComponents
        guard let devices = components.firstIndex(of: "Devices"), devices > 0,
              components[devices - 1] == "CoreSimulator", devices + 1 < components.count,
              UUID(uuidString: components[devices + 1]) != nil
        else { return nil }
        return components[devices + 1].uppercased()
    }

    /// kern.boottime seconds. The Simulator shares the host kernel, so the harness reads the same
    /// value with `sysctl -n kern.boottime` on the Mac.
    static func hostBootTimeSeconds() -> Int? {
        var bootTime = timeval()
        var size = MemoryLayout<timeval>.stride
        guard sysctlbyname("kern.boottime", &bootTime, &size, nil, 0) == 0, bootTime.tv_sec > 0 else { return nil }
        return Int(bootTime.tv_sec)
    }

    static func fileSha256Digest(_ url: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            let chunk = handle.readData(ofLength: 1 << 20)
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
        }
        return "sha256:" + hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    // MARK: Envelope

    /// Mirrors scripts/qa/mobile_login_bootstrap_lib.mjs `validateLoginResponse` and the binding checks
    /// in SharedAuthDataSource.consumeQaLoginBootstrap, which runs again after this on the same bytes.
    /// Returns nil when the envelope may be handed to the shared consumer.
    static func validateEnvelope(_ bytes: Data, expectedDigest: String, binding: Binding) -> Failure? {
        guard isValidDigest(expectedDigest) else { return Failure(stage: "envelope_digest", code: "format") }
        guard sha256Digest(bytes) == expectedDigest else { return Failure(stage: "envelope_digest", code: "mismatch") }
        guard let object = try? JSONSerialization.jsonObject(with: bytes),
              let envelope = object as? [String: Any]
        else { return Failure(stage: "envelope_shape", code: "json") }
        guard Set(envelope.keys) == envelopeKeys else { return Failure(stage: "envelope_shape", code: "keys") }
        func string(_ key: String) -> String? {
            guard let value = envelope[key] as? String, !value.isEmpty else { return nil }
            return value
        }
        guard string("version") == envelopeVersion else { return Failure(stage: "envelope_shape", code: "version") }
        guard let runCaseId = string("run_case_id"), !runCaseId.trimmingCharacters(in: .whitespaces).isEmpty else {
            return Failure(stage: "envelope_shape", code: "run_case_id")
        }
        guard let attempt = envelope["attempt"] as? NSNumber, CFGetTypeID(attempt) != CFBooleanGetTypeID(),
              attempt.doubleValue == attempt.doubleValue.rounded(), attempt.doubleValue >= 1,
              attempt.doubleValue <= Double(Int32.max)
        else { return Failure(stage: "envelope_shape", code: "attempt") }
        guard string("ci_job_ref") != nil else { return Failure(stage: "envelope_shape", code: "ci_job_ref") }
        guard let principalId = string("principal_id"),
              let user = envelope["user"] as? [String: Any],
              (user["id"] as? String) == principalId
        else { return Failure(stage: "envelope_shape", code: "principal") }
        guard string("server_id") != nil else { return Failure(stage: "envelope_shape", code: "server_id") }
        guard string("target_route") == targetRoute else { return Failure(stage: "envelope_shape", code: "target_route") }
        guard string("access_token") != nil else { return Failure(stage: "envelope_shape", code: "access_token") }
        guard string("refresh_token") != nil else { return Failure(stage: "envelope_shape", code: "refresh_token") }
        guard string("application_id") == binding.applicationId else {
            return Failure(stage: "envelope_binding", code: "application_id")
        }
        guard string("signing_cert_sha256") == binding.codeIdentitySha256 else {
            return Failure(stage: "envelope_binding", code: "code_identity")
        }
        guard string("boot_generation") == binding.bootGeneration else {
            return Failure(stage: "envelope_binding", code: "boot_generation")
        }
        return nil
    }
}
#endif
