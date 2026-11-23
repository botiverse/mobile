import Intents
import ObjectiveC
import UIKit
import UniformTypeIdentifiers

final class ShareViewController: UIViewController {
    private let statusLabel = UILabel()
    private let spinner = UIActivityIndicatorView(style: .large)
    private let actionStack = UIStackView()
    private let doneButton = UIButton(type: .system)
    private let cancelButton = UIButton(type: .system)
    private let fence = RaftExternalSharePublicationFence()
    private let temporaryFileLock = NSLock()
    private var started = false
    private var handoffStarted = false
    private var didDisappearBeforeHandoff = false
    private var stagedToken: String?
    private var preselectedTargetHint: String?
    private var temporaryFiles = Set<URL>()
    private var acceptingTemporaryFiles = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        statusLabel.text = localized("share_extension.preparing")
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        statusLabel.font = .preferredFont(forTextStyle: .body)
        statusLabel.adjustsFontForContentSizeCategory = true
        spinner.startAnimating()

        doneButton.setTitle(localized("share_extension.done"), for: .normal)
        doneButton.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        doneButton.titleLabel?.adjustsFontForContentSizeCategory = true
        doneButton.addTarget(self, action: #selector(handleDoneTap), for: .touchUpInside)

        cancelButton.setTitle(localized("share_extension.cancel"), for: .normal)
        cancelButton.titleLabel?.font = .preferredFont(forTextStyle: .body)
        cancelButton.titleLabel?.adjustsFontForContentSizeCategory = true
        cancelButton.tintColor = .secondaryLabel
        cancelButton.addTarget(self, action: #selector(handleCancelTap), for: .touchUpInside)

        actionStack.axis = .vertical
        actionStack.spacing = 12
        actionStack.alignment = .center
        actionStack.isHidden = true
        actionStack.addArrangedSubview(doneButton)
        actionStack.addArrangedSubview(cancelButton)

        let stack = UIStackView(arrangedSubviews: [spinner, statusLabel, actionStack])
        stack.axis = .vertical
        stack.spacing = 18
        stack.alignment = .center
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            stack.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -24),
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !started else { return }
        started = true
        let intentConversationId = (extensionContext?.intent as? INSendMessageIntent)?.conversationIdentifier
        preselectedTargetHint = RaftExternalShareContract.canonicalizeTargetHint(intentConversationId)
        temporaryFileLock.lock()
        acceptingTemporaryFiles = true
        temporaryFileLock.unlock()
        collectInput(generation: fence.begin())
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if !handoffStarted {
            didDisappearBeforeHandoff = true
        }
        fence.invalidate()
        removeTemporaryFiles()
    }

    private func collectInput(generation: UUID) {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { item in
            item.attachments ?? []
        }
        guard providers.count <= RaftExternalShareContract.maximumItems else {
            finish(.failure(RaftExternalShareStorageError.rejected("external_share_too_many_items")), generation: generation)
            return
        }
        process(
            providers: providers,
            index: 0,
            textParts: [],
            candidates: [],
            totalBytes: 0,
            generation: generation
        ) { [weak self] result in
            DispatchQueue.main.async { self?.finish(result, generation: generation) }
        }
    }

    private func process(
        providers: [NSItemProvider],
        index: Int,
        textParts: [String],
        candidates: [RaftExternalShareCandidate],
        totalBytes: Int64,
        generation: UUID,
        completion: @escaping (Result<(String, [RaftExternalShareCandidate]), Error>) -> Void
    ) {
        guard index < providers.count else {
            completion(.success((textParts.joined(separator: "\n"), candidates)))
            return
        }
        let provider = providers[index]
        switch RaftExternalShareProviderRouting.classify(registeredTypeIdentifiers: provider.registeredTypeIdentifiers) {
        case .fileRepresentation(let typeIdentifier, let fallbackToFileURL):
            provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { [weak self] url, error in
                guard let self, self.fence.acceptProvider(index: index, generation: generation) else { return }
                if let url, error == nil {
                    self.stageFileAndContinue(
                        sourceURL: url,
                        suggestedName: provider.suggestedName,
                        typeIdentifier: typeIdentifier,
                        providers: providers,
                        index: index,
                        textParts: textParts,
                        candidates: candidates,
                        totalBytes: totalBytes,
                        generation: generation,
                        completion: completion
                    )
                    return
                }
                if fallbackToFileURL {
                    self.loadFileURLItem(
                        provider: provider,
                        typeIdentifier: UTType.fileURL.identifier,
                        useFallbackFence: true,
                        providers: providers,
                        index: index,
                        textParts: textParts,
                        candidates: candidates,
                        totalBytes: totalBytes,
                        generation: generation,
                        completion: completion
                    )
                    return
                }
                completion(.failure(error ?? RaftExternalShareStorageError.rejected("external_share_item_unreadable")))
            }

        case .fileURL:
            loadFileURLItem(
                provider: provider,
                typeIdentifier: UTType.fileURL.identifier,
                useFallbackFence: false,
                providers: providers,
                index: index,
                textParts: textParts,
                candidates: candidates,
                totalBytes: totalBytes,
                generation: generation,
                completion: completion
            )

        case .url:
            provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { [weak self] item, error in
                guard let self, self.fence.acceptProvider(index: index, generation: generation) else { return }
                guard error == nil else {
                    completion(.failure(error!))
                    return
                }
                if let fileURL = Self.extractFileURL(from: item) {
                    self.stageFileAndContinue(
                        sourceURL: fileURL,
                        suggestedName: provider.suggestedName,
                        typeIdentifier: UTType.fileURL.identifier,
                        providers: providers,
                        index: index,
                        textParts: textParts,
                        candidates: candidates,
                        totalBytes: totalBytes,
                        generation: generation,
                        completion: completion
                    )
                    return
                }
                let value = Self.extractText(from: item)
                self.appendTextAndContinue(
                    value: value,
                    providers: providers,
                    index: index,
                    textParts: textParts,
                    candidates: candidates,
                    totalBytes: totalBytes,
                    generation: generation,
                    completion: completion
                )
            }

        case .text(let typeIdentifier):
            provider.loadItem(forTypeIdentifier: typeIdentifier, options: nil) { [weak self] item, error in
                guard let self, self.fence.acceptProvider(index: index, generation: generation) else { return }
                guard error == nil else {
                    completion(.failure(error!))
                    return
                }
                if let fileURL = Self.extractFileURL(from: item) {
                    self.stageFileAndContinue(
                        sourceURL: fileURL,
                        suggestedName: provider.suggestedName,
                        typeIdentifier: typeIdentifier,
                        providers: providers,
                        index: index,
                        textParts: textParts,
                        candidates: candidates,
                        totalBytes: totalBytes,
                        generation: generation,
                        completion: completion
                    )
                    return
                }
                let value = Self.extractText(from: item)
                self.appendTextAndContinue(
                    value: value,
                    providers: providers,
                    index: index,
                    textParts: textParts,
                    candidates: candidates,
                    totalBytes: totalBytes,
                    generation: generation,
                    completion: completion
                )
            }

        case .unsupported:
            completion(.failure(RaftExternalShareStorageError.rejected("external_share_item_unsupported")))
        }
    }

    private func loadFileURLItem(
        provider: NSItemProvider,
        typeIdentifier: String,
        useFallbackFence: Bool,
        providers: [NSItemProvider],
        index: Int,
        textParts: [String],
        candidates: [RaftExternalShareCandidate],
        totalBytes: Int64,
        generation: UUID,
        completion: @escaping (Result<(String, [RaftExternalShareCandidate]), Error>) -> Void
    ) {
        provider.loadItem(forTypeIdentifier: typeIdentifier, options: nil) { [weak self] item, error in
            guard let self else { return }
            let accepted = useFallbackFence
                ? self.fence.acceptProviderFallback(index: index, generation: generation)
                : self.fence.acceptProvider(index: index, generation: generation)
            guard accepted else { return }
            guard error == nil, let fileURL = Self.extractFileURL(from: item) else {
                completion(.failure(error ?? RaftExternalShareStorageError.rejected("external_share_item_unreadable")))
                return
            }
            self.stageFileAndContinue(
                sourceURL: fileURL,
                suggestedName: provider.suggestedName,
                typeIdentifier: typeIdentifier,
                providers: providers,
                index: index,
                textParts: textParts,
                candidates: candidates,
                totalBytes: totalBytes,
                generation: generation,
                completion: completion
            )
        }
    }

    private func stageFileAndContinue(
        sourceURL: URL,
        suggestedName: String?,
        typeIdentifier: String?,
        providers: [NSItemProvider],
        index: Int,
        textParts: [String],
        candidates: [RaftExternalShareCandidate],
        totalBytes: Int64,
        generation: UUID,
        completion: @escaping (Result<(String, [RaftExternalShareCandidate]), Error>) -> Void
    ) {
        let accessed = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if accessed {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }
        do {
            let temporaryRoot = FileManager.default.temporaryDirectory
                .appendingPathComponent("raft-external-share", isDirectory: true)
            try FileManager.default.createDirectory(at: temporaryRoot, withIntermediateDirectories: true)
            let temporary = temporaryRoot.appendingPathComponent(UUID().uuidString.lowercased())
            let size = try RaftExternalShareEnvelopeStorage.copyBounded(
                source: sourceURL,
                destination: temporary,
                maximumBytes: RaftExternalShareContract.maximumItemBytes,
                bytesBefore: totalBytes
            )
            guard registerTemporaryFile(temporary) else {
                try? FileManager.default.removeItem(at: temporary)
                return
            }
            let displayName = RaftExternalShareEnvelopeStorage.resolvedDisplayName(
                suggestedName: suggestedName,
                sourceURL: sourceURL,
                typeIdentifier: typeIdentifier,
                index: candidates.count
            )
            let mimeType = RaftExternalShareEnvelopeStorage.resolvedMimeType(
                typeIdentifier: typeIdentifier,
                displayName: displayName
            )
            var next = candidates
            next.append(
                RaftExternalShareCandidate(
                    url: temporary,
                    displayName: displayName,
                    mimeType: mimeType,
                    sizeBytes: size
                )
            )
            process(
                providers: providers,
                index: index + 1,
                textParts: textParts,
                candidates: next,
                totalBytes: totalBytes + size,
                generation: generation,
                completion: completion
            )
        } catch {
            completion(.failure(error))
        }
    }

    private func appendTextAndContinue(
        value: String,
        providers: [NSItemProvider],
        index: Int,
        textParts: [String],
        candidates: [RaftExternalShareCandidate],
        totalBytes: Int64,
        generation: UUID,
        completion: @escaping (Result<(String, [RaftExternalShareCandidate]), Error>) -> Void
    ) {
        let nextTextParts = value.isEmpty ? textParts : textParts + [value]
        let combinedLength = nextTextParts.joined(separator: "\n").count
        guard combinedLength <= RaftExternalShareContract.maximumTextCharacters else {
            completion(.failure(RaftExternalShareStorageError.rejected("external_share_text_too_large")))
            return
        }
        process(
            providers: providers,
            index: index + 1,
            textParts: nextTextParts,
            candidates: candidates,
            totalBytes: totalBytes,
            generation: generation,
            completion: completion
        )
    }

    private func finish(
        _ result: Result<(String, [RaftExternalShareCandidate]), Error>,
        generation: UUID
    ) {
        guard fence.finish(generation: generation) else { return }
        switch result {
        case .success(let payload):
            let ownedTemporaryFiles = detachTemporaryFiles()
            let capturedHint = preselectedTargetHint
            let scheme = Bundle(for: ShareViewController.self)
                .object(forInfoDictionaryKey: "RaftHostLaunchScheme") as? String
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                defer {
                    ownedTemporaryFiles.forEach { try? FileManager.default.removeItem(at: $0) }
                }
                guard let self else { return }
                var admittedToken: String?
                let outcome: Result<(String, URL), Error>
                do {
                    let token = try RaftExternalShareEnvelopeStorage.shared.admit(
                        text: payload.0,
                        subject: "",
                        candidates: payload.1,
                        preselectedTargetHint: capturedHint
                    )
                    admittedToken = token
                    let url = try RaftExternalShareContract.hostLaunchURL(
                        token: token,
                        configuredScheme: scheme
                    )
                    outcome = .success((token, url))
                } catch {
                    if let token = admittedToken {
                        RaftExternalShareEnvelopeStorage.shared.delete(token: token)
                    }
                    outcome = .failure(error)
                }
                DispatchQueue.main.async { [weak self] in
                    guard let self, !self.didDisappearBeforeHandoff else {
                        if case .success(let (token, _)) = outcome {
                            RaftExternalShareEnvelopeStorage.shared.delete(token: token)
                        }
                        return
                    }
                    switch outcome {
                    case .success(let (token, url)):
                        self.stagedToken = token
                        self.handoffStarted = true
                        self.openHostApp(url) { [weak self] opened in
                            DispatchQueue.main.async {
                                guard let self else { return }
                                if opened {
                                    self.extensionContext?.completeRequest(returningItems: nil)
                                } else {
                                    self.showManualOpenHandoff()
                                }
                            }
                        }
                    case .failure(let error):
                        self.fail(code: (error as? RaftExternalShareStorageError)?.code ?? "external_share_copy_failed")
                    }
                }
            }
        case .failure(let error):
            removeTemporaryFiles()
            if !didDisappearBeforeHandoff {
                fail(code: (error as? RaftExternalShareStorageError)?.code ?? "external_share_copy_failed")
            }
        }
    }

    /// Hands off to the containing Raft app using the non-deprecated iOS 10+ 3-argument selector
    /// (`openURL:options:completionHandler:`) so iOS 18+ UIKit does not reject the legacy 1-arg
    /// `openURL:`, falling back to `extensionContext?.open(url)` and finally a manual-open handoff
    /// screen (since the envelope + `pending.txt` are already durably staged in the App Group).
    private func openHostApp(_ url: URL, completion: @escaping (Bool) -> Void) {
        let selector = NSSelectorFromString("openURL:options:completionHandler:")
        var responder: UIResponder? = self
        while let current = responder {
            if current is UIApplication, current.responds(to: selector) {
                typealias OpenURLBlock = @convention(block) (Bool) -> Void
                typealias OpenURLIMP = @convention(c) (
                    AnyObject,
                    Selector,
                    NSURL,
                    NSDictionary,
                    OpenURLBlock?
                ) -> Void
                if let method = class_getMethodImplementation(type(of: current), selector) {
                    let open = unsafeBitCast(method, to: OpenURLIMP.self)
                    let block: OpenURLBlock = { opened in
                        completion(opened)
                    }
                    open(current, selector, url as NSURL, [:] as NSDictionary, block)
                    return
                }
            }
            responder = current.next
        }
        if let context = extensionContext {
            context.open(url, completionHandler: completion)
        } else {
            completion(false)
        }
    }

    private func showManualOpenHandoff() {
        spinner.stopAnimating()
        spinner.isHidden = true
        statusLabel.text = localized("share_extension.saved_open_raft")
        actionStack.isHidden = false
    }

    @objc private func handleDoneTap() {
        extensionContext?.completeRequest(returningItems: nil)
    }

    @objc private func handleCancelTap() {
        if let token = stagedToken {
            RaftExternalShareEnvelopeStorage.shared.delete(token: token)
            stagedToken = nil
        }
        extensionContext?.cancelRequest(
            withError: RaftExternalShareStorageError.rejected("external_share_cancelled")
        )
    }

    private func fail(code: String) {
        RaftExternalShareEnvelopeStorage.shared.recordFailure(code: code)
        spinner.stopAnimating()
        spinner.isHidden = true
        statusLabel.text = localizedErrorMessage(for: code)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) { [weak self] in
            self?.extensionContext?.cancelRequest(withError: RaftExternalShareStorageError.rejected(code))
        }
    }

    private func localizedErrorMessage(for code: String) -> String {
        switch code {
        case "external_share_too_many_items":
            return localized("share_extension.too_many_items")
        case "external_share_item_too_large", "external_share_total_too_large":
            return localized("share_extension.file_too_large")
        case "external_share_item_unsupported":
            return localized("share_extension.unsupported")
        default:
            return localized("share_extension.failed")
        }
    }

    private func localized(_ key: String) -> String {
        NSLocalizedString(key, tableName: "Localizable", bundle: Bundle(for: ShareViewController.self), comment: "")
    }

    private static func extractFileURL(from item: NSSecureCoding?) -> URL? {
        if let url = item as? URL, url.isFileURL {
            return url
        }
        if let url = item as? NSURL, url.isFileURL {
            return url as URL
        }
        if let string = item as? String,
            let url = URL(string: string),
            url.isFileURL {
            return url
        }
        if let data = item as? Data,
            let url = URL(dataRepresentation: data, relativeTo: nil),
            url.isFileURL {
            return url
        }
        return nil
    }

    private static func extractText(from item: NSSecureCoding?) -> String {
        if let url = item as? URL { return url.absoluteString }
        if let url = item as? NSURL { return url.absoluteString ?? "" }
        if let string = item as? String { return string }
        if let string = item as? NSString { return string as String }
        if let data = item as? Data, let decoded = String(data: data, encoding: .utf8) {
            return decoded
        }
        return ""
    }

    private func detachTemporaryFiles() -> Set<URL> {
        temporaryFileLock.lock()
        acceptingTemporaryFiles = false
        let files = temporaryFiles
        temporaryFiles.removeAll(keepingCapacity: false)
        temporaryFileLock.unlock()
        return files
    }

    private func removeTemporaryFiles() {
        let files = detachTemporaryFiles()
        files.forEach { try? FileManager.default.removeItem(at: $0) }
    }

    private func registerTemporaryFile(_ url: URL) -> Bool {
        temporaryFileLock.lock()
        defer { temporaryFileLock.unlock() }
        guard acceptingTemporaryFiles else { return false }
        temporaryFiles.insert(url)
        return true
    }
}
