#if RAFT_QA_SESSION_BOOTSTRAP
// iOS Simulator QA session bootstrap host: the iOS counterpart of Android's QaSessionBootstrapActivity.
// scripts/qa/ios_qa_bootstrap.mjs installs the envelope in the app's private inbox and launches the app
// with `-qaBootstrapRequestId <id> -qaBootstrapEnvelopeDigest sha256:<hex>`. iOSApp.init starts the
// gate; RaftIosRootView holds the shell back until run(_:completion:) has consumed, validated and
// persisted the session.
// Debug configuration only (RAFT_QA_SESSION_BOOTSTRAP) and Simulator only. Log lines carry
// stage/code/digest tokens and paths, never envelope contents or tokens.
// Contract: docs/qa-acceptance/session-bootstrap.md.

import Foundation
import shared
import SwiftUI
import UIKit

enum RaftIosQaSessionBootstrapHost {
    private typealias Contract = RaftIosQaSessionBootstrapContract

    private static var claimed = false

    /// The launch's bootstrap request, at most once per process (the first scene takes it).
    static func takeLaunchRequest(
        arguments: [String] = ProcessInfo.processInfo.arguments
    ) -> RaftIosQaSessionBootstrapContract.LaunchRequest? {
        guard !claimed else { return nil }
        // Every Debug launch: which bootstrap flags (names only) this process received. Not a
        // QA_BOOTSTRAP_ line, so ordinary runs add nothing to the harness's stage summary.
        let flags = arguments.filter { $0.hasPrefix("-qaBootstrap") }
        NSLog("%@", "[SlockQaBootstrap][debug] launch_check argc=\(arguments.count) flags=\(flags.joined(separator: ","))")
        let parsed = Contract.parseLaunchArguments(arguments)
        if parsed == .absent { return nil }
        claimed = true
        #if targetEnvironment(simulator)
        switch parsed {
        case .absent:
            return nil
        case .rejected(let code):
            failure("launch_arguments", code)
            return nil
        case .request(let request):
            return request
        }
        #else
        failure("runtime_gate", "not_simulator")
        return nil
        #endif
    }

    /// Consumes the inbox file, validates it and persists the session; [completion] runs on the main
    /// thread afterwards whatever the outcome (on failure the app simply starts signed out).
    static func run(_ request: RaftIosQaSessionBootstrapContract.LaunchRequest, completion: @escaping () -> Void) {
        stage("bootstrap_started")
        DispatchQueue.global(qos: .userInitiated).async {
            let prepared = prepare(request)
            DispatchQueue.main.async {
                guard let prepared else {
                    completion()
                    return
                }
                RaftIosQaSessionBootstrapBridge.shared.consumeLoginEnvelope(
                    envelopeJson: prepared.json,
                    envelopeDigest: request.envelopeDigest,
                    applicationId: prepared.binding.applicationId,
                    signingCertSha256: prepared.binding.codeIdentitySha256,
                    deviceBootGeneration: prepared.binding.bootGeneration
                ) { code in
                    if let code {
                        failure("login_consume", code)
                    } else {
                        stage("session_persisted")
                        stage("shell_launch_requested")
                    }
                    completion()
                }
            }
        }
    }

    private struct Prepared {
        let json: String
        let binding: RaftIosQaSessionBootstrapContract.Binding
    }

    private static func prepare(_ request: RaftIosQaSessionBootstrapContract.LaunchRequest) -> Prepared? {
        guard let applicationSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            failure("inbox_consume", "directory")
            return nil
        }
        let inbox = Contract.inboxDirectory(applicationSupport: applicationSupport)
        let binding = currentBinding()
        // Reader-side coordinates (paths, request ids and binding digests only), so a failed run can
        // compare them with what the harness wrote and requested.
        let entries = (try? FileManager.default.contentsOfDirectory(atPath: inbox.path))?.sorted().joined(separator: ",") ?? "null"
        log(
            "QA_BOOTSTRAP_DIAG protocol=\(Contract.protocolLabel) home=\(NSHomeDirectory())" +
                " path=\(Contract.inboxFile(directory: inbox, requestId: request.requestId).path)" +
                " inboxEntries=\(entries) pid=\(getpid())" +
                " applicationId=\(binding?.applicationId ?? "null")" +
                " codeIdentity=\(binding?.codeIdentitySha256 ?? "null")" +
                " bootGeneration=\(binding?.bootGeneration ?? "null")"
        )
        let consumed = Contract.consumeInbox(directory: inbox, requestId: request.requestId)
        guard case .consumed(var bytes) = consumed else {
            if case .rejected(let rejection) = consumed { failure("inbox_consume", rejection.rawValue) }
            return nil
        }
        defer { bytes.resetBytes(in: 0..<bytes.count) }
        stage("inbox_consumed")
        guard let binding else {
            failure("envelope_binding", "unavailable")
            return nil
        }
        if let rejected = Contract.validateEnvelope(bytes, expectedDigest: request.envelopeDigest, binding: binding) {
            failure(rejected.stage, rejected.code)
            return nil
        }
        stage("envelope_validated")
        guard let json = String(data: bytes, encoding: .utf8) else {
            failure("envelope_shape", "utf8")
            return nil
        }
        return Prepared(json: json, binding: binding)
    }

    private static func currentBinding() -> RaftIosQaSessionBootstrapContract.Binding? {
        guard let applicationId = Bundle.main.bundleIdentifier,
              let executable = Bundle.main.executableURL,
              let codeIdentity = Contract.codeIdentityDigest(executableURL: executable),
              let udid = Contract.simulatorUdid(
                  environment: ProcessInfo.processInfo.environment,
                  homeDirectory: NSHomeDirectory()
              ),
              let bootTime = Contract.hostBootTimeSeconds()
        else { return nil }
        return Contract.Binding(
            applicationId: applicationId,
            codeIdentitySha256: codeIdentity,
            bootGeneration: Contract.bootGeneration(simulatorUdid: udid, hostBootTimeSeconds: bootTime)
        )
    }

    private static func stage(_ name: String) {
        log("QA_BOOTSTRAP_STAGE stage=\(name)")
    }

    private static func failure(_ stage: String, _ code: String) {
        log("QA_BOOTSTRAP_FAILURE stage=\(stage) code=\(code)")
    }

    /// Same record shape as the Kotlin side (`[SlockQaBootstrap][info] <line>` via NSLog), read by
    /// the harness from the Simulator's unified log.
    private static func log(_ line: String) {
        NSLog("%@", "[SlockQaBootstrap][info] \(line)")
    }
}
/// Process-wide bootstrap state the root view observes: holds the shell while a launch request runs.
final class RaftIosQaSessionBootstrapGate: ObservableObject {
    static let shared = RaftIosQaSessionBootstrapGate()

    @Published private(set) var holdsShell = false

    /// Called once from iOSApp.init, before any scene connects.
    func start() {
        guard let request = RaftIosQaSessionBootstrapHost.takeLaunchRequest() else { return }
        holdsShell = true
        RaftIosQaSessionBootstrapHost.run(request) { [weak self] in
            self?.holdsShell = false
        }
    }
}

/// Shows [content] (the app shell) only once no QA session bootstrap is pending.
struct RaftIosQaSessionBootstrapGateView<Content: View>: View {
    @ObservedObject private var gate = RaftIosQaSessionBootstrapGate.shared
    private let content: () -> Content

    init(@ViewBuilder content: @escaping () -> Content) {
        self.content = content
    }

    var body: some View {
        if gate.holdsShell {
            Color(UIColor(raftArgb: RaftAppAppearanceRuntime.shared.canvasArgb)).ignoresSafeArea()
        } else {
            content()
        }
    }
}
#endif
