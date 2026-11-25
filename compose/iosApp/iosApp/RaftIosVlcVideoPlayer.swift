import Foundation
import shared
import UIKit

/// In-app playback of the video containers AVPlayer cannot demux (WebM, MKV, MPEG-TS/PS, FLV)
/// through MobileVLCKit (libVLC, LGPL-2.1-or-later; listed in Settings > About > Open Source
/// Licenses).
///
/// :shared decides which attachments play here (`raftVideoPlaybackEngineFor`, the VLC engine) and
/// hosts `playerView()` like it hosts AVPlayerViewController: this player owns its controls
/// (play/pause, a seek bar with the time, full screen). When VLC cannot open a file the player
/// reports `onFailed()` before anything played, and the file page shows its download card with
/// Download and Open in another app instead.
enum RaftIosVlcVideo {
    /// At launch, before the first page (iOSApp.init).
    static func install() {
        RaftIosVlcVideoHost.shared.factory = RaftIosVlcKitPlayerFactory()
    }
}

private final class RaftIosVlcKitPlayerFactory: NSObject, RaftIosVlcVideoPlayerFactory {
    func createPlayer(url: String, labels: RaftIosVlcVideoLabels) -> RaftIosVlcVideoPlayer {
        RaftIosVlcKitPlayer(url: url, labels: labels)
    }
}

/// One player: the libVLC engine (RaftIosVlcEngine.m), its controls, and full screen.
private final class RaftIosVlcKitPlayer: NSObject, RaftIosVlcVideoPlayer {
    /// What Compose hosts. The controls and video (`chrome`) move from it to the full-screen
    /// controller and back; Compose only ever lays out this view.
    private let hostView = UIView()
    private let chrome: RaftIosVlcPlayerChrome
    private let engine: RaftIosVlcEngine?
    private var events: RaftIosVlcVideoEvents?
    private var fullScreenController: RaftIosVlcFullScreenController?
    private var disposed = false
    private var everPlayed = false
    private var readyReported = false
    private var failed = false

    init(url: String, labels: RaftIosVlcVideoLabels) {
        let chrome = RaftIosVlcPlayerChrome(labels: labels)
        self.chrome = chrome
        engine = URL(string: url).map { RaftIosVlcEngine(url: $0, drawable: chrome.videoView) }
        super.init()
        hostView.backgroundColor = .black
        hostView.clipsToBounds = true
        hostView.raftVlcEmbed(chrome)
        chrome.onTogglePlay = { [weak self] in self?.togglePlay() }
        chrome.onSeek = { [weak self] timeMs in self?.seekTo(timeMs: timeMs) }
        chrome.onToggleFullScreen = { [weak self] in self?.toggleFullScreen() }
        engine?.onPrepared = { [weak self] opened, durationMs in
            self?.prepared(opened: opened, durationMs: durationMs)
        }
        engine?.onStateChanged = { [weak self] state in self?.stateChanged(state) }
        engine?.onTimeChanged = { [weak self] currentMs, durationMs in
            self?.timeChanged(currentMs: currentMs, durationMs: durationMs)
        }
    }

    // MARK: RaftIosVlcVideoPlayer

    func playerView() -> UIView {
        hostView
    }

    func listen(events: RaftIosVlcVideoEvents?) {
        self.events = events
    }

    func prepare() {
        guard !disposed else { return }
        guard let engine else {
            fail()
            return
        }
        chrome.setBusy(true)
        engine.prepare()
    }

    func play() {
        guard !disposed, !failed || everPlayed, let engine else { return }
        chrome.setFailed(false)
        engine.play()
    }

    func pause() {
        guard !disposed else { return }
        engine?.pause()
    }

    func seekTo(timeMs: Int64) {
        guard !disposed, let engine else { return }
        engine.seek(toMs: timeMs)
        chrome.setTime(currentMs: timeMs, durationMs: engine.durationMs)
    }

    func applyMuted(muted: Bool) {
        engine?.muted = muted
    }

    func applyRate(rate: Float) {
        engine?.rate = rate
    }

    func dispose() {
        guard !disposed else { return }
        disposed = true
        events = nil
        if let controller = fullScreenController {
            fullScreenController = nil
            controller.dismiss(animated: false)
        }
        chrome.stopTimers()
        engine?.shutdown()
    }

    // MARK: Engine reports

    private func prepared(opened: Bool, durationMs: Int64) {
        guard !disposed else { return }
        chrome.setBusy(false)
        guard opened else {
            RaftIosDiagnosticsRecord("warn", "RaftMedia", "vlc_open_failed stage=probe")
            fail()
            return
        }
        chrome.setTime(currentMs: 0, durationMs: durationMs)
        reportReady(durationMs: durationMs)
    }

    private func stateChanged(_ state: RaftIosVlcEngineState) {
        guard !disposed else { return }
        switch state {
        case .opening, .buffering:
            chrome.setBusy(true)
            events?.onBuffering()
        case .playing:
            everPlayed = true
            failed = false
            chrome.setBusy(false)
            chrome.setFailed(false)
            chrome.setPlaying(true)
            reportReady(durationMs: engine?.durationMs ?? 0)
            events?.onPlaying()
        case .paused:
            chrome.setBusy(false)
            chrome.setPlaying(false)
            events?.onPaused()
        case .ended:
            chrome.setBusy(false)
            chrome.setPlaying(false)
            events?.onEnded()
        case .failed:
            RaftIosDiagnosticsRecord("warn", "RaftMedia", "vlc_playback_failed everPlayed=\(everPlayed)")
            fail()
        default:
            break
        }
    }

    private func timeChanged(currentMs: Int64, durationMs: Int64) {
        guard !disposed else { return }
        chrome.setTime(currentMs: currentMs, durationMs: durationMs)
        events?.onTime(currentMs: currentMs, durationMs: durationMs)
    }

    private func reportReady(durationMs: Int64) {
        guard !readyReported else { return }
        readyReported = true
        events?.onReady(durationMs: durationMs)
    }

    private func fail() {
        failed = true
        chrome.setBusy(false)
        chrome.setPlaying(false)
        chrome.setFailed(true)
        events?.onFailed()
    }

    // MARK: Controls

    private func togglePlay() {
        guard let engine else { return }
        if engine.playing {
            pause()
        } else {
            play()
        }
    }

    private func toggleFullScreen() {
        if fullScreenController != nil {
            exitFullScreen()
        } else {
            enterFullScreen()
        }
    }

    private func enterFullScreen() {
        guard !disposed, fullScreenController == nil, let presenter = hostView.window?.rootViewController?.raftVlcTopPresented else {
            return
        }
        let controller = RaftIosVlcFullScreenController()
        controller.modalPresentationStyle = .fullScreen
        controller.loadViewIfNeeded()
        controller.view.raftVlcEmbed(chrome)
        chrome.setFullScreen(true)
        fullScreenController = controller
        events?.onFullscreenChanged(fullscreen: true)
        presenter.present(controller, animated: true)
    }

    private func exitFullScreen() {
        guard let controller = fullScreenController else { return }
        fullScreenController = nil
        chrome.setFullScreen(false)
        controller.dismiss(animated: true) { [weak self] in
            guard let self, !self.disposed else { return }
            self.hostView.raftVlcEmbed(self.chrome)
            self.events?.onFullscreenChanged(fullscreen: false)
        }
    }
}

/// The black full-screen stage the player moves to; it rotates with the device.
private final class RaftIosVlcFullScreenController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
    }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .allButUpsideDown }
}

/// The video surface and its controls: a centre play button, a bottom bar (play/pause, time,
/// seek slider, full screen), a spinner while opening or buffering, and "Playback failed".
/// A tap shows or hides the bar; while playing it hides itself after a few seconds.
private final class RaftIosVlcPlayerChrome: UIView {
    /// libVLC draws into this view.
    let videoView = UIView()
    var onTogglePlay: (() -> Void)?
    var onSeek: ((Int64) -> Void)?
    var onToggleFullScreen: (() -> Void)?

    private let labels: RaftIosVlcVideoLabels
    private let bar = UIView()
    private let playButton = UIButton(type: .system)
    private let centerPlayButton = UIButton(type: .system)
    private let currentLabel = UILabel()
    private let durationLabel = UILabel()
    private let slider = UISlider()
    private let fullScreenButton = UIButton(type: .system)
    private let spinner = UIActivityIndicatorView(style: .large)
    private let failedLabel = UILabel()
    private var playing = false
    private var busy = false
    private var failed = false
    private var durationMs: Int64 = 0
    private var controlsVisible = true
    private var hideWork: DispatchWorkItem?

    private static let autoHideDelay: TimeInterval = 3

    init(labels: RaftIosVlcVideoLabels) {
        self.labels = labels
        super.init(frame: .zero)
        backgroundColor = .black
        build()
        setPlaying(false)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    // MARK: State

    func setPlaying(_ playing: Bool) {
        self.playing = playing
        let symbol = playing ? "pause.fill" : "play.fill"
        playButton.setImage(UIImage(systemName: symbol), for: .normal)
        playButton.accessibilityLabel = playing ? labels.pauseLabel : labels.playLabel
        updateOverlays()
        if playing {
            scheduleAutoHide()
        } else {
            showControls()
        }
    }

    func setBusy(_ busy: Bool) {
        self.busy = busy
        if busy {
            spinner.startAnimating()
        } else {
            spinner.stopAnimating()
        }
        updateOverlays()
    }

    func setFailed(_ failed: Bool) {
        self.failed = failed
        updateOverlays()
        if failed { showControls() }
    }

    func setTime(currentMs: Int64, durationMs: Int64) {
        if durationMs > 0 { self.durationMs = durationMs }
        let total = self.durationMs
        currentLabel.text = Self.format(ms: currentMs)
        durationLabel.text = Self.format(ms: total)
        slider.isEnabled = total > 0
        if !slider.isTracking, total > 0 {
            slider.value = Float(min(max(Double(currentMs) / Double(total), 0), 1))
        }
    }

    func setFullScreen(_ fullScreen: Bool) {
        let symbol = fullScreen ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right"
        fullScreenButton.setImage(UIImage(systemName: symbol), for: .normal)
        fullScreenButton.accessibilityLabel = fullScreen ? labels.exitFullScreenLabel : labels.fullScreenLabel
        showControls()
    }

    func stopTimers() {
        hideWork?.cancel()
        hideWork = nil
    }

    // MARK: Layout

    private func build() {
        videoView.backgroundColor = .black
        videoView.isUserInteractionEnabled = false
        raftVlcEmbed(videoView)

        spinner.color = .white
        spinner.hidesWhenStopped = true
        spinner.translatesAutoresizingMaskIntoConstraints = false
        addSubview(spinner)

        let centerConfig = UIImage.SymbolConfiguration(pointSize: 52, weight: .regular)
        centerPlayButton.setImage(UIImage(systemName: "play.circle.fill", withConfiguration: centerConfig), for: .normal)
        centerPlayButton.tintColor = .white
        centerPlayButton.accessibilityLabel = labels.playLabel
        centerPlayButton.translatesAutoresizingMaskIntoConstraints = false
        centerPlayButton.addTarget(self, action: #selector(togglePlayTapped), for: .touchUpInside)
        addSubview(centerPlayButton)

        failedLabel.text = labels.playbackFailedLabel
        failedLabel.textColor = .white
        failedLabel.font = .preferredFont(forTextStyle: .subheadline)
        failedLabel.textAlignment = .center
        failedLabel.numberOfLines = 2
        failedLabel.translatesAutoresizingMaskIntoConstraints = false
        addSubview(failedLabel)

        bar.backgroundColor = UIColor.black.withAlphaComponent(0.6)
        bar.translatesAutoresizingMaskIntoConstraints = false
        addSubview(bar)

        for button in [playButton, fullScreenButton] {
            button.tintColor = .white
            button.translatesAutoresizingMaskIntoConstraints = false
        }
        playButton.addTarget(self, action: #selector(togglePlayTapped), for: .touchUpInside)
        fullScreenButton.addTarget(self, action: #selector(fullScreenTapped), for: .touchUpInside)
        setFullScreen(false)

        for label in [currentLabel, durationLabel] {
            label.textColor = .white
            label.font = .monospacedDigitSystemFont(ofSize: 12, weight: .semibold)
            label.text = Self.format(ms: 0)
            label.setContentHuggingPriority(.required, for: .horizontal)
            label.setContentCompressionResistancePriority(.required, for: .horizontal)
            label.translatesAutoresizingMaskIntoConstraints = false
        }

        slider.minimumTrackTintColor = .white
        slider.maximumTrackTintColor = UIColor.white.withAlphaComponent(0.3)
        slider.isEnabled = false
        slider.translatesAutoresizingMaskIntoConstraints = false
        slider.addTarget(self, action: #selector(sliderChanged), for: .valueChanged)
        slider.addTarget(self, action: #selector(sliderReleased), for: [.touchUpInside, .touchUpOutside, .touchCancel])

        let row = UIStackView(arrangedSubviews: [playButton, currentLabel, slider, durationLabel, fullScreenButton])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = 8
        row.translatesAutoresizingMaskIntoConstraints = false
        bar.addSubview(row)

        let guide = safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            spinner.centerXAnchor.constraint(equalTo: centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: centerYAnchor),
            centerPlayButton.centerXAnchor.constraint(equalTo: centerXAnchor),
            centerPlayButton.centerYAnchor.constraint(equalTo: centerYAnchor),
            failedLabel.centerXAnchor.constraint(equalTo: centerXAnchor),
            failedLabel.centerYAnchor.constraint(equalTo: centerYAnchor),
            failedLabel.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 16),
            bar.leadingAnchor.constraint(equalTo: leadingAnchor),
            bar.trailingAnchor.constraint(equalTo: trailingAnchor),
            bar.bottomAnchor.constraint(equalTo: bottomAnchor),
            row.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 4),
            row.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -4),
            row.topAnchor.constraint(equalTo: bar.topAnchor),
            row.bottomAnchor.constraint(equalTo: guide.bottomAnchor),
            row.heightAnchor.constraint(equalToConstant: 44),
            playButton.widthAnchor.constraint(equalToConstant: 44),
            playButton.heightAnchor.constraint(equalToConstant: 44),
            fullScreenButton.widthAnchor.constraint(equalToConstant: 44),
            fullScreenButton.heightAnchor.constraint(equalToConstant: 44),
        ])

        let tap = UITapGestureRecognizer(target: self, action: #selector(surfaceTapped(_:)))
        tap.cancelsTouchesInView = false
        addGestureRecognizer(tap)
    }

    private func updateOverlays() {
        failedLabel.isHidden = !failed
        centerPlayButton.isHidden = playing || busy || failed
    }

    private func showControls() {
        hideWork?.cancel()
        hideWork = nil
        setControlsVisible(true)
        if playing { scheduleAutoHide() }
    }

    private func scheduleAutoHide() {
        hideWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.playing, !self.slider.isTracking else { return }
            self.setControlsVisible(false)
        }
        hideWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.autoHideDelay, execute: work)
    }

    private func setControlsVisible(_ visible: Bool) {
        controlsVisible = visible
        UIView.animate(withDuration: 0.2) {
            self.bar.alpha = visible ? 1 : 0
        }
        bar.isUserInteractionEnabled = visible
    }

    // MARK: Actions

    @objc private func togglePlayTapped() {
        onTogglePlay?()
        showControls()
    }

    @objc private func fullScreenTapped() {
        onToggleFullScreen?()
    }

    @objc private func surfaceTapped(_ recognizer: UITapGestureRecognizer) {
        // Taps on the bar's controls are theirs.
        if controlsVisible, bar.bounds.contains(recognizer.location(in: bar)) { return }
        if controlsVisible, playing {
            hideWork?.cancel()
            setControlsVisible(false)
        } else {
            showControls()
        }
    }

    @objc private func sliderChanged() {
        hideWork?.cancel()
        currentLabel.text = Self.format(ms: Int64(Double(slider.value) * Double(durationMs)))
    }

    @objc private func sliderReleased() {
        guard durationMs > 0 else { return }
        onSeek?(Int64(Double(slider.value) * Double(durationMs)))
        if playing { scheduleAutoHide() }
    }

    private static func format(ms: Int64) -> String {
        let totalSeconds = Int(max(ms, 0) / 1000)
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%ld:%02ld:%02ld", hours, minutes, seconds)
        }
        return String(format: "%ld:%02ld", minutes, seconds)
    }
}

private extension UIView {
    /// Adds (or moves) `child` here, filling this view.
    func raftVlcEmbed(_ child: UIView) {
        child.removeFromSuperview()
        child.translatesAutoresizingMaskIntoConstraints = true
        child.frame = bounds
        child.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(child)
    }
}

private extension UIViewController {
    var raftVlcTopPresented: UIViewController {
        var top = self
        while let presented = top.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }
        return top
    }
}
