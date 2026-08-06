import UIKit
import UniformTypeIdentifiers

final class ShareViewController: UIViewController {
    private let statusLabel = UILabel()
    private let spinner = UIActivityIndicatorView(style: .large)
    private let fence = SlockExternalSharePublicationFence()
    private let temporaryFileLock = NSLock()
    private var started = false
    private var temporaryFiles = Set<URL>()
    private var acceptingTemporaryFiles = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        statusLabel.text = "Preparing share…"
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        spinner.startAnimating()
        let stack = UIStackView(arrangedSubviews: [spinner, statusLabel])
        stack.axis = .vertical
        stack.spacing = 18
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
        temporaryFileLock.lock()
        acceptingTemporaryFiles = true
        temporaryFileLock.unlock()
        collectInput(generation: fence.begin())
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        fence.invalidate()
        removeTemporaryFiles()
    }

    private func collectInput(generation: UUID) {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { item in
            item.attachments ?? []
        }
        guard providers.count <= SlockExternalShareContract.maximumItems else {
            finish(.failure(SlockExternalShareStorageError.rejected("external_share_too_many_items")), generation: generation)
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
        candidates: [SlockExternalShareCandidate],
        totalBytes: Int64,
        generation: UUID,
        completion: @escaping (Result<(String, [SlockExternalShareCandidate]), Error>) -> Void
    ) {
        guard index < providers.count else {
            completion(.success((textParts.joined(separator: "\n"), candidates)))
            return
        }
        let provider = providers[index]
        if let fileType = provider.registeredTypeIdentifiers.first(where: { identifier in
            guard let type = UTType(identifier) else { return false }
            return type.conforms(to: .data) && !type.conforms(to: .text) && !type.conforms(to: .url)
        }) {
            provider.loadFileRepresentation(forTypeIdentifier: fileType) { [weak self] url, error in
                guard let self, self.fence.acceptProvider(index: index, generation: generation) else { return }
                guard let url, error == nil else {
                    completion(.failure(error ?? SlockExternalShareStorageError.rejected("external_share_item_unreadable")))
                    return
                }
                do {
                    let temporaryRoot = FileManager.default.temporaryDirectory
                        .appendingPathComponent("raft-external-share", isDirectory: true)
                    try FileManager.default.createDirectory(at: temporaryRoot, withIntermediateDirectories: true)
                    let temporary = temporaryRoot.appendingPathComponent(UUID().uuidString.lowercased())
                    let size = try SlockExternalShareEnvelopeStorage.copyBounded(
                        source: url,
                        destination: temporary,
                        maximumBytes: SlockExternalShareContract.maximumItemBytes,
                        bytesBefore: totalBytes
                    )
                    guard self.registerTemporaryFile(temporary) else {
                        try? FileManager.default.removeItem(at: temporary)
                        return
                    }
                    var next = candidates
                    let type = UTType(fileType)
                    next.append(
                        SlockExternalShareCandidate(
                            url: temporary,
                            displayName: provider.suggestedName ?? url.lastPathComponent,
                            mimeType: type?.preferredMIMEType ?? "application/octet-stream",
                            sizeBytes: size
                        )
                    )
                    self.process(
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
            return
        }

        let identifier: String
        if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
            identifier = UTType.url.identifier
        } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            identifier = UTType.plainText.identifier
        } else {
            completion(.failure(SlockExternalShareStorageError.rejected("external_share_item_unsupported")))
            return
        }
        provider.loadItem(forTypeIdentifier: identifier, options: nil) { [weak self] item, error in
            guard let self, self.fence.acceptProvider(index: index, generation: generation) else { return }
            guard error == nil else {
                completion(.failure(error!))
                return
            }
            let value: String
            if let url = item as? URL { value = url.absoluteString }
            else if let string = item as? String { value = string }
            else if let string = item as? NSString { value = string as String }
            else { value = "" }
            guard value.count <= SlockExternalShareContract.maximumTextCharacters else {
                completion(.failure(SlockExternalShareStorageError.rejected("external_share_text_too_large")))
                return
            }
            self.process(
                providers: providers,
                index: index + 1,
                textParts: value.isEmpty ? textParts : textParts + [value],
                candidates: candidates,
                totalBytes: totalBytes,
                generation: generation,
                completion: completion
            )
        }
    }

    private func finish(
        _ result: Result<(String, [SlockExternalShareCandidate]), Error>,
        generation: UUID
    ) {
        guard fence.finish(generation: generation) else { return }
        defer { removeTemporaryFiles() }
        switch result {
        case .success(let payload):
            do {
                let token = try SlockExternalShareEnvelopeStorage.shared.admit(
                    text: payload.0,
                    subject: "",
                    candidates: payload.1
                )
                var components = URLComponents()
                components.scheme = "raft"
                components.host = "external-share"
                components.queryItems = [URLQueryItem(name: "token", value: token)]
                guard let url = components.url else {
                    throw SlockExternalShareStorageError.rejected("external_share_launch_url_invalid")
                }
                extensionContext?.open(url) { [weak self] opened in
                    DispatchQueue.main.async {
                        if opened {
                            self?.extensionContext?.completeRequest(returningItems: nil)
                        } else {
                            self?.fail(code: "external_share_host_open_failed")
                        }
                    }
                }
            } catch {
                fail(code: (error as? SlockExternalShareStorageError)?.code ?? "external_share_copy_failed")
            }
        case .failure(let error):
            fail(code: (error as? SlockExternalShareStorageError)?.code ?? "external_share_copy_failed")
        }
    }

    private func fail(code: String) {
        SlockExternalShareEnvelopeStorage.shared.recordFailure(code: code)
        spinner.stopAnimating()
        statusLabel.text = "Couldn’t prepare this share. Open Raft and try again."
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
            self?.extensionContext?.cancelRequest(withError: SlockExternalShareStorageError.rejected(code))
        }
    }

    private func removeTemporaryFiles() {
        temporaryFileLock.lock()
        acceptingTemporaryFiles = false
        let files = temporaryFiles
        temporaryFiles.removeAll(keepingCapacity: false)
        temporaryFileLock.unlock()
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
