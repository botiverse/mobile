import Foundation
import QuartzCore
import UIKit

/// Configuration for iOS `CADisplayLink` frame-jank and frozen-frame tracing.
///
/// Thresholds mirror Android's `RaftPerformanceDiagnostics` / `BoundedPerformanceTraceRing` /
/// `RaftScrollFrameStats`:
/// - `jankBudgetMultiplier = 1.75`: counts a missed vsync (~2x budget) while tolerating adaptive
///   refresh step jitter without false positives.
/// - `detailedJankThresholdMs = 64` / `detailedMissedVsyncThreshold = 3`: matches Android's
///   `shouldLogDetailedJankFrame` (`>= 64ms`) and `shouldLogDetailedFrameMetrics` (`missedVsync >= 3`).
/// - `frozenFrameThresholdMs = 700`: matches the standard Android Vitals frozen-frame threshold.
struct RaftIosDisplayLinkJankConfig: Equatable {
    let detailedJankThresholdMs: Int64
    let detailedMissedVsyncThreshold: Int
    let frozenFrameThresholdMs: Int64
    let jankBudgetMultiplier: Double
    let minDetailedJankLogIntervalSeconds: Double
    let jankSummaryWindowSeconds: Double
    let smoothSummaryWindowSeconds: Double
    let minRouteChangeFlushDurationSeconds: Double
    let defaultRefreshRateHz: Int
    let minValidBudgetSeconds: Double
    let maxAdaptiveBudgetSeconds: Double

    static let `default` = RaftIosDisplayLinkJankConfig(
        detailedJankThresholdMs: 64,
        detailedMissedVsyncThreshold: 3,
        frozenFrameThresholdMs: 700,
        jankBudgetMultiplier: 1.75,
        minDetailedJankLogIntervalSeconds: 0.5,
        jankSummaryWindowSeconds: 10.0,
        smoothSummaryWindowSeconds: 60.0,
        minRouteChangeFlushDurationSeconds: 1.0,
        defaultRefreshRateHz: 60,
        minValidBudgetSeconds: 1.0 / 240.0,
        maxAdaptiveBudgetSeconds: 0.100
    )

    init(
        detailedJankThresholdMs: Int64 = 64,
        detailedMissedVsyncThreshold: Int = 3,
        frozenFrameThresholdMs: Int64 = 700,
        jankBudgetMultiplier: Double = 1.75,
        minDetailedJankLogIntervalSeconds: Double = 0.5,
        jankSummaryWindowSeconds: Double = 10.0,
        smoothSummaryWindowSeconds: Double = 60.0,
        minRouteChangeFlushDurationSeconds: Double = 1.0,
        defaultRefreshRateHz: Int = 60,
        minValidBudgetSeconds: Double = 1.0 / 240.0,
        maxAdaptiveBudgetSeconds: Double = 0.100
    ) {
        self.detailedJankThresholdMs = max(1, detailedJankThresholdMs)
        self.detailedMissedVsyncThreshold = max(1, detailedMissedVsyncThreshold)
        self.frozenFrameThresholdMs = max(self.detailedJankThresholdMs, frozenFrameThresholdMs)
        self.jankBudgetMultiplier = max(1.1, jankBudgetMultiplier)
        self.minDetailedJankLogIntervalSeconds = max(0.0, minDetailedJankLogIntervalSeconds)
        self.jankSummaryWindowSeconds = max(0.1, jankSummaryWindowSeconds)
        self.smoothSummaryWindowSeconds = max(self.jankSummaryWindowSeconds, smoothSummaryWindowSeconds)
        self.minRouteChangeFlushDurationSeconds = max(0.0, minRouteChangeFlushDurationSeconds)
        self.defaultRefreshRateHz = max(1, defaultRefreshRateHz)
        self.minValidBudgetSeconds = max(0.001, minValidBudgetSeconds)
        self.maxAdaptiveBudgetSeconds = max(self.minValidBudgetSeconds, maxAdaptiveBudgetSeconds)
    }
}

/// One `CADisplayLink` callback sample (all timestamps in monotonic `CACurrentMediaTime()` seconds).
struct RaftIosDisplayLinkFrameSample: Equatable {
    let timestamp: Double
    let targetTimestamp: Double
    let duration: Double
    let now: Double
    let maxFramesPerSecond: Int
}

/// One formatted diagnostics line destined for `raft-diagnostics.jsonl` (`RaftIosDiagnosticsRecord`).
struct RaftIosDisplayLinkJankLogRecord: Equatable {
    let level: String
    let tag: String
    let message: String
}

/// Pure, deterministic state machine that classifies frame intervals, tracks ProMotion-adaptive
/// budgets, suppresses post-resume anchor spikes, rate-limits severe `jank_frame` spikes, and
/// aggregates rolling `summary` windows with zero per-frame heap allocations.
final class RaftIosDisplayLinkJankEngine {
    static let tag = "SlockPerf"
    static let histogramBuckets = 256

    let config: RaftIosDisplayLinkJankConfig

    private(set) var route: String = "unknown"
    private(set) var operation: String = "idle"
    private(set) var totalJankFrames: Int = 0
    private(set) var totalFrozenFrames: Int = 0
    private(set) var sessionLongestFrameMs: Int64 = 0

    private var lastAnchorTime: Double?
    private var lastExpectedBudgetSeconds: Double?
    private var lastDetailedJankLogTime: Double?
    private var pendingSuppressedDetailedJankLogs: Int = 0

    private var windowStartTime: Double?
    private var windowFrames: Int = 0
    private var windowJankFrames: Int = 0
    private var windowFrozenFrames: Int = 0
    private var windowHitchMs: Int64 = 0
    private var windowLongestFrameMs: Int64 = 0
    private var windowBudgetSumSeconds: Double = 0.0
    private var windowMaxFps: Int
    private var windowSuppressedJankLogs: Int = 0
    private var histogram: [Int]

    init(config: RaftIosDisplayLinkJankConfig = .default) {
        self.config = config
        self.windowMaxFps = config.defaultRefreshRateHz
        self.histogram = Array(repeating: 0, count: Self.histogramBuckets)
    }

    var hasFrameAnchor: Bool {
        lastAnchorTime != nil
    }

    var currentWindowFrames: Int {
        windowFrames
    }

    static func sanitizeIdentifier(_ raw: String?, fallback: String, maxLength: Int = 64) -> String {
        guard let raw = raw else { return fallback }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return fallback }
        var output = ""
        output.reserveCapacity(min(trimmed.count, maxLength))
        var hasNonUnderscore = false
        for scalar in trimmed.unicodeScalars {
            if output.count >= maxLength { break }
            let value = scalar.value
            let isSafe =
                (value >= 48 && value <= 57) ||   // 0-9
                (value >= 65 && value <= 90) ||   // A-Z
                (value >= 97 && value <= 122) ||  // a-z
                value == 95 ||                    // _
                value == 45 ||                    // -
                value == 46 ||                    // .
                value == 47 ||                    // /
                value == 58                       // :
            if isSafe {
                output.unicodeScalars.append(scalar)
                if value != 95 { hasNonUnderscore = true }
            } else {
                output.append("_")
            }
        }
        guard !output.isEmpty, hasNonUnderscore else { return fallback }
        return output
    }

    /// Computes the refresh-rate-aware frame budget from `targetTimestamp - timestamp` (ProMotion
    /// adaptive interval), falling back to `duration`, then `1 / maxFramesPerSecond`.
    static func resolvedBudgetSeconds(
        timestamp: Double,
        targetTimestamp: Double,
        duration: Double,
        maxFramesPerSecond: Int,
        config: RaftIosDisplayLinkJankConfig
    ) -> (budgetSeconds: Double, maxFps: Int) {
        let maxFps = maxFramesPerSecond > 0 ? maxFramesPerSecond : config.defaultRefreshRateHz
        let candidate: Double
        if targetTimestamp.isFinite && timestamp.isFinite && targetTimestamp > timestamp {
            candidate = targetTimestamp - timestamp
        } else if duration.isFinite && duration > 0.0 {
            candidate = duration
        } else {
            candidate = 1.0 / Double(maxFps)
        }
        let clamped = min(max(candidate, config.minValidBudgetSeconds), config.maxAdaptiveBudgetSeconds)
        return (clamped, maxFps)
    }

    func makeEnabledRecord(maxFramesPerSecond: Int) -> RaftIosDisplayLinkJankLogRecord {
        let maxFps = maxFramesPerSecond > 0 ? maxFramesPerSecond : config.defaultRefreshRateHz
        let targetFrameMs = Self.formatOneDecimal(1000.0 / Double(maxFps))
        let summaryWindowMs = Int64(config.jankSummaryWindowSeconds * 1000.0)
        return RaftIosDisplayLinkJankLogRecord(
            level: "info",
            tag: Self.tag,
            message:
                "displaylink_enabled route=\(route) operation=\(operation) maxFps=\(maxFps) " +
                "targetFrameMs=\(targetFrameMs) detailedJankMs=\(config.detailedJankThresholdMs) " +
                "frozenMs=\(config.frozenFrameThresholdMs) summaryWindowMs=\(summaryWindowMs)"
        )
    }

    /// Discards the baseline anchor so the next `CADisplayLink` tick re-anchors without recording
    /// a false jank or frozen frame across a pause/resume gap.
    func discardFrameAnchor() {
        lastAnchorTime = nil
        lastExpectedBudgetSeconds = nil
    }

    /// Processes one `CADisplayLink` tick and returns any diagnostics records to emit.
    func recordFrame(_ sample: RaftIosDisplayLinkFrameSample) -> [RaftIosDisplayLinkJankLogRecord] {
        guard sample.timestamp.isFinite, sample.timestamp > 0.0,
              sample.now.isFinite, sample.now > 0.0
        else {
            return []
        }

        let resolved = Self.resolvedBudgetSeconds(
            timestamp: sample.timestamp,
            targetTimestamp: sample.targetTimestamp,
            duration: sample.duration,
            maxFramesPerSecond: sample.maxFramesPerSecond,
            config: config
        )
        let currentBudget = resolved.budgetSeconds
        let resolvedMaxFps = resolved.maxFps

        // First callback after install or resume only establishes the baseline anchor so
        // background/inactive duration is never miscounted as a frozen frame.
        guard let prevAnchor = lastAnchorTime else {
            lastAnchorTime = sample.timestamp
            lastExpectedBudgetSeconds = currentBudget
            if windowStartTime == nil {
                windowStartTime = sample.now
            }
            windowMaxFps = resolvedMaxFps
            return []
        }

        // Taking max(prevBudget, currentBudget) avoids false jank when ProMotion ramps down
        // between consecutive frames (e.g. 120Hz 8.3ms -> 60Hz 16.7ms).
        let effectiveBudget = max(lastExpectedBudgetSeconds ?? currentBudget, currentBudget)

        // If the main thread blocked past this tick's own target deadline and CoreAnimation
        // delivered an older timestamp, measure through `now` and advance the anchor to `now`
        // so the stall is recorded immediately without double-counting on the next tick.
        let callbackPastTarget =
            sample.targetTimestamp.isFinite &&
            sample.targetTimestamp > sample.timestamp &&
            sample.now > sample.targetTimestamp
        let effectiveEnd = callbackPastTarget ? max(sample.timestamp, sample.now) : sample.timestamp

        guard effectiveEnd > prevAnchor else {
            lastExpectedBudgetSeconds = currentBudget
            return []
        }

        let frameDurationSeconds = effectiveEnd - prevAnchor
        lastAnchorTime = effectiveEnd
        lastExpectedBudgetSeconds = currentBudget

        let durationMs = Int64((frameDurationSeconds * 1000.0).rounded())
        let isFrozen = durationMs >= config.frozenFrameThresholdMs
        let isJank = isFrozen || (frameDurationSeconds >= effectiveBudget * config.jankBudgetMultiplier)
        let missedVsync: Int
        let hitchMs: Int64
        if isJank {
            let ratio = frameDurationSeconds / effectiveBudget
            missedVsync = max(1, Int(floor(ratio + 0.25)) - 1)
            hitchMs = max(0, Int64(((frameDurationSeconds - effectiveBudget) * 1000.0).rounded()))
        } else {
            missedVsync = 0
            hitchMs = 0
        }

        if windowStartTime == nil {
            windowStartTime = sample.now
        }
        windowFrames += 1
        windowBudgetSumSeconds += effectiveBudget
        if resolvedMaxFps > windowMaxFps {
            windowMaxFps = resolvedMaxFps
        }
        if durationMs > windowLongestFrameMs {
            windowLongestFrameMs = durationMs
        }
        if durationMs > sessionLongestFrameMs {
            sessionLongestFrameMs = durationMs
        }
        let bucket = min(max(Int(durationMs), 0), Self.histogramBuckets - 1)
        histogram[bucket] += 1

        if isJank {
            windowJankFrames += 1
            totalJankFrames += 1
            windowHitchMs += hitchMs
        }
        if isFrozen {
            windowFrozenFrames += 1
            totalFrozenFrames += 1
        }

        var records: [RaftIosDisplayLinkJankLogRecord] = []
        let budgetMsLabel = Self.formatOneDecimal(effectiveBudget * 1000.0)

        if isFrozen {
            records.append(
                RaftIosDisplayLinkJankLogRecord(
                    level: "warn",
                    tag: Self.tag,
                    message:
                        "frozen_frame route=\(route) operation=\(operation) durationMs=\(durationMs) " +
                        "budgetMs=\(budgetMsLabel) hitchMs=\(hitchMs) missedVsync=\(missedVsync) " +
                        "maxFps=\(resolvedMaxFps) totalJank=\(totalJankFrames) " +
                        "totalFrozen=\(totalFrozenFrames) longestFrameMs=\(sessionLongestFrameMs)"
                )
            )
        } else if isJank &&
            (durationMs >= config.detailedJankThresholdMs || missedVsync >= config.detailedMissedVsyncThreshold)
        {
            let allowedByCooldown: Bool
            if let lastLogTime = lastDetailedJankLogTime {
                allowedByCooldown = (sample.now - lastLogTime) >= config.minDetailedJankLogIntervalSeconds
            } else {
                allowedByCooldown = true
            }
            if allowedByCooldown {
                let suppressed = pendingSuppressedDetailedJankLogs
                pendingSuppressedDetailedJankLogs = 0
                lastDetailedJankLogTime = sample.now
                records.append(
                    RaftIosDisplayLinkJankLogRecord(
                        level: "warn",
                        tag: Self.tag,
                        message:
                            "jank_frame route=\(route) operation=\(operation) durationMs=\(durationMs) " +
                            "budgetMs=\(budgetMsLabel) hitchMs=\(hitchMs) missedVsync=\(missedVsync) " +
                            "maxFps=\(resolvedMaxFps) totalJank=\(totalJankFrames) " +
                            "totalFrozen=\(totalFrozenFrames) longestFrameMs=\(sessionLongestFrameMs) " +
                            "suppressed=\(suppressed)"
                    )
                )
            } else {
                pendingSuppressedDetailedJankLogs += 1
                windowSuppressedJankLogs += 1
            }
        }

        let windowElapsed = max(0.0, sample.now - (windowStartTime ?? sample.now))
        let hasWindowHitch = windowJankFrames > 0 || windowFrozenFrames > 0
        if (hasWindowHitch && windowElapsed >= config.jankSummaryWindowSeconds) ||
            (!hasWindowHitch && windowElapsed >= config.smoothSummaryWindowSeconds)
        {
            if let summary = flushWindowSummary(reason: "window", now: sample.now, nextWindowStart: sample.now) {
                records.append(summary)
            }
        }

        return records
    }

    /// Pauses frame tracking on inactive/background transitions: clears the frame anchor so the
    /// next foreground frame never produces a resume spike, and flushes any accumulated window.
    func pause(reason: String, now: Double) -> RaftIosDisplayLinkJankLogRecord? {
        discardFrameAnchor()
        let flushed = flushWindowSummary(reason: reason, now: now, nextWindowStart: nil)
        windowStartTime = nil
        return flushed
    }

    /// Updates active screen/route and/or operation context. When switching away from an established
    /// route that accumulated jank (or >= `minRouteChangeFlushDurationSeconds` of frames), flushes
    /// that route's summary first so every stall stays attributed to the right screen.
    func updateContext(
        route nextRoute: String?,
        operation nextOperation: String?,
        now: Double
    ) -> RaftIosDisplayLinkJankLogRecord? {
        var flushed: RaftIosDisplayLinkJankLogRecord?
        if let rawRoute = nextRoute {
            let trimmedRoute = rawRoute.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmedRoute.isEmpty {
                let sanitized = Self.sanitizeIdentifier(trimmedRoute, fallback: "unknown")
                if sanitized != route {
                    if route != "unknown", windowFrames > 0 {
                        let elapsed = max(0.0, now - (windowStartTime ?? now))
                        let hasHitch = windowJankFrames > 0 || windowFrozenFrames > 0
                        if hasHitch || elapsed >= config.minRouteChangeFlushDurationSeconds {
                            flushed = flushWindowSummary(
                                reason: "route_change",
                                now: now,
                                nextWindowStart: hasFrameAnchor ? now : nil
                            )
                        }
                    }
                    route = sanitized
                }
            }
        }
        if let rawOperation = nextOperation {
            let trimmedOperation = rawOperation.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmedOperation.isEmpty {
                operation = Self.sanitizeIdentifier(trimmedOperation, fallback: "idle")
            }
        }
        return flushed
    }

    private func flushWindowSummary(
        reason: String,
        now: Double,
        nextWindowStart: Double?
    ) -> RaftIosDisplayLinkJankLogRecord? {
        guard windowFrames > 0 else { return nil }
        let safeReason = Self.sanitizeIdentifier(reason, fallback: "summary")
        let windowMs: Int64
        if let start = windowStartTime, now.isFinite, start.isFinite {
            windowMs = Int64((max(0.0, now - start) * 1000.0).rounded())
        } else {
            windowMs = 0
        }
        let meanBudgetMs = (windowBudgetSumSeconds / Double(windowFrames)) * 1000.0
        let p95Ms = percentileMs(percent: 95)
        let hasWindowHitch = windowJankFrames > 0 || windowFrozenFrames > 0
        let record = RaftIosDisplayLinkJankLogRecord(
            level: hasWindowHitch ? "warn" : "info",
            tag: Self.tag,
            message:
                "summary reason=\(safeReason) route=\(route) operation=\(operation) " +
                "windowMs=\(windowMs) frames=\(windowFrames) jankFrames=\(windowJankFrames) " +
                "frozenFrames=\(windowFrozenFrames) hitchMs=\(windowHitchMs) " +
                "longestFrameMs=\(windowLongestFrameMs) p95FrameMs=\(p95Ms) " +
                "budgetMs=\(Self.formatOneDecimal(meanBudgetMs)) maxFps=\(windowMaxFps) " +
                "jank=\(totalJankFrames) frozen=\(totalFrozenFrames) " +
                "sessionLongestFrameMs=\(sessionLongestFrameMs) " +
                "suppressedJankLogs=\(windowSuppressedJankLogs)"
        )
        resetWindow(nextWindowStart: nextWindowStart)
        return record
    }

    private func resetWindow(nextWindowStart: Double?) {
        windowStartTime = nextWindowStart
        windowFrames = 0
        windowJankFrames = 0
        windowFrozenFrames = 0
        windowHitchMs = 0
        windowLongestFrameMs = 0
        windowBudgetSumSeconds = 0.0
        windowSuppressedJankLogs = 0
        pendingSuppressedDetailedJankLogs = 0
        for index in 0..<histogram.count {
            histogram[index] = 0
        }
    }

    private func percentileMs(percent: Int) -> Int64 {
        guard windowFrames > 0 else { return 0 }
        let rank = (Int64(windowFrames) * Int64(percent) + 99) / 100
        var seen: Int64 = 0
        for bucket in 0..<histogram.count {
            seen += Int64(histogram[bucket])
            if seen >= rank {
                return Int64(bucket)
            }
        }
        return Int64(histogram.count - 1)
    }

    private static func formatOneDecimal(_ value: Double) -> String {
        guard value.isFinite else { return "0.0" }
        let clamped = min(max(0.0, value), 1_000_000.0)
        let tenths = Int64((clamped * 10.0).rounded())
        return "\(tenths / 10).\(tenths % 10)"
    }
}

/// Abstraction over `CADisplayLink` so lifecycle start/stop behavior is unit-testable without a
/// live run loop.
protocol RaftIosDisplayLinkDriver: AnyObject {
    var isRunning: Bool { get }
    func start(onTick: @escaping (RaftIosDisplayLinkFrameSample) -> Void)
    func stop()
}

/// Public `QuartzCore` `CADisplayLink` driver bound to `RunLoop.main` (`.common` mode so scrolling
/// and gesture tracking are observed). Uses a weak proxy target so `CADisplayLink` never creates
/// a retain cycle.
final class SystemRaftIosDisplayLinkDriver: RaftIosDisplayLinkDriver {
    private final class TargetProxy: NSObject {
        weak var driver: SystemRaftIosDisplayLinkDriver?

        init(driver: SystemRaftIosDisplayLinkDriver) {
            self.driver = driver
        }

        @objc func handleDisplayLinkTick(_ displayLink: CADisplayLink) {
            guard let driver = driver else {
                displayLink.invalidate()
                return
            }
            driver.handleTick(displayLink)
        }
    }

    private var displayLink: CADisplayLink?
    private var onTick: ((RaftIosDisplayLinkFrameSample) -> Void)?

    var isRunning: Bool {
        displayLink != nil
    }

    func start(onTick: @escaping (RaftIosDisplayLinkFrameSample) -> Void) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard displayLink == nil else { return }
        self.onTick = onTick
        let proxy = TargetProxy(driver: self)
        let link = CADisplayLink(target: proxy, selector: #selector(TargetProxy.handleDisplayLinkTick(_:)))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    func stop() {
        dispatchPrecondition(condition: .onQueue(.main))
        displayLink?.invalidate()
        displayLink = nil
        onTick = nil
    }

    deinit {
        displayLink?.invalidate()
    }

    private func handleTick(_ displayLink: CADisplayLink) {
        let now = CACurrentMediaTime()
        let maxFps = Self.currentMaxFramesPerSecond(fallbackDuration: displayLink.duration)
        onTick?(
            RaftIosDisplayLinkFrameSample(
                timestamp: displayLink.timestamp,
                targetTimestamp: displayLink.targetTimestamp,
                duration: displayLink.duration,
                now: now,
                maxFramesPerSecond: maxFps
            )
        )
    }

    static func currentMaxFramesPerSecond(fallbackDuration: Double = 0.0) -> Int {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene =
            scenes.first(where: { $0.activationState == .foregroundActive }) ??
            scenes.first(where: { $0.activationState == .foregroundInactive }) ??
            scenes.first
        if let maxFps = scene?.screen.maximumFramesPerSecond, maxFps > 0 {
            return maxFps
        }
        if fallbackDuration.isFinite && fallbackDuration > 0.0 {
            let derived = Int((1.0 / fallbackDuration).rounded())
            if derived > 0 {
                return derived
            }
        }
        return 60
    }
}

/// Process-wide iOS `CADisplayLink` frame-jank and frozen-frame diagnostics tracker.
///
/// Mirrors Android's `RaftPerformanceDiagnostics` (`SlockPerf` tag, `jank_frame`, `frozen_frame`,
/// `summary`) into `raft-diagnostics.jsonl` so Hands feedback/crash bundles expose iOS main-thread
/// stalls with screen/route attribution. Automatically stops `CADisplayLink` when inactive or
/// backgrounded (zero background battery drain) and discards the first post-resume frame so
/// backgrounding never registers as a frozen frame.
@objc final class RaftIosDisplayLinkJankTracker: NSObject {
    typealias LogSink = (_ level: String, _ tag: String, _ message: String) -> Void
    typealias ApplicationStateProvider = () -> UIApplication.State
    typealias NowProvider = () -> Double
    typealias MaxFpsProvider = () -> Int

    static let shared = RaftIosDisplayLinkJankTracker()

    private let engine: RaftIosDisplayLinkJankEngine
    private let notificationCenter: NotificationCenter
    private let applicationState: ApplicationStateProvider
    private let nowProvider: NowProvider
    private let maxFpsProvider: MaxFpsProvider
    private let driver: RaftIosDisplayLinkDriver

    private var logSink: LogSink?
    private var installed = false
    private var observerTokens: [NSObjectProtocol] = []

    init(
        config: RaftIosDisplayLinkJankConfig = .default,
        notificationCenter: NotificationCenter = .default,
        applicationState: @escaping ApplicationStateProvider = { UIApplication.shared.applicationState },
        nowProvider: @escaping NowProvider = { CACurrentMediaTime() },
        maxFpsProvider: @escaping MaxFpsProvider = { SystemRaftIosDisplayLinkDriver.currentMaxFramesPerSecond() },
        driver: RaftIosDisplayLinkDriver = SystemRaftIosDisplayLinkDriver(),
        logSink: LogSink? = nil
    ) {
        self.engine = RaftIosDisplayLinkJankEngine(config: config)
        self.notificationCenter = notificationCenter
        self.applicationState = applicationState
        self.nowProvider = nowProvider
        self.maxFpsProvider = maxFpsProvider
        self.driver = driver
        self.logSink = logSink
        super.init()
    }

    deinit {
        observerTokens.forEach(notificationCenter.removeObserver)
        observerTokens.removeAll()
        if Thread.isMainThread {
            driver.stop()
        } else {
            let capturedDriver = driver
            DispatchQueue.main.async {
                capturedDriver.stop()
            }
        }
    }

    /// Installs the process-wide tracker once (called from `RaftIosSceneDelegate` on connect).
    static func install(logSink: @escaping LogSink) {
        shared.install(logSink: logSink)
    }

    @objc(noteRoute:operation:)
    static func noteRoute(_ route: String?, operation: String? = nil) {
        shared.noteRoute(route, operation: operation)
    }

    @objc(noteOperation:)
    static func noteOperation(_ operation: String?) {
        shared.noteOperation(operation)
    }

    @objc(notePerformanceScene:action:)
    static func notePerformanceScene(_ sceneName: String?, action: String?) {
        shared.notePerformanceScene(sceneName, action: action)
    }

    @objc(notePerformanceMarkerWithRoute:phase:)
    static func notePerformanceMarker(route: String?, phase: String?) {
        shared.notePerformanceMarker(route: route, phase: phase)
    }

    func install(logSink: LogSink? = nil) {
        runOnMain { [weak self] in
            guard let self = self else { return }
            if let logSink = logSink {
                self.logSink = logSink
            }
            guard !self.installed else { return }
            self.installed = true

            self.observerTokens = [
                self.notificationCenter.addObserver(
                    forName: UIApplication.willResignActiveNotification,
                    object: nil,
                    queue: nil
                ) { [weak self] _ in
                    self?.runOnMain {
                        self?.handlePause(reason: "resign_active")
                    }
                },
                self.notificationCenter.addObserver(
                    forName: UIApplication.didEnterBackgroundNotification,
                    object: nil,
                    queue: nil
                ) { [weak self] _ in
                    self?.runOnMain {
                        self?.handlePause(reason: "enter_background")
                    }
                },
                self.notificationCenter.addObserver(
                    forName: UIApplication.willEnterForegroundNotification,
                    object: nil,
                    queue: nil
                ) { [weak self] _ in
                    self?.runOnMain {
                        self?.handleResume()
                    }
                },
                self.notificationCenter.addObserver(
                    forName: UIApplication.didBecomeActiveNotification,
                    object: nil,
                    queue: nil
                ) { [weak self] _ in
                    self?.runOnMain {
                        self?.handleResume()
                    }
                },
            ]

            self.emit(self.engine.makeEnabledRecord(maxFramesPerSecond: self.maxFpsProvider()))
            if self.applicationState() == .active {
                self.handleResume()
            }
        }
    }

    func uninstall() {
        runOnMain { [weak self] in
            guard let self = self else { return }
            self.installed = false
            self.observerTokens.forEach(self.notificationCenter.removeObserver)
            self.observerTokens.removeAll()
            self.driver.stop()
            self.engine.discardFrameAnchor()
        }
    }

    func noteRoute(_ route: String?, operation: String? = nil) {
        runOnMain { [weak self] in
            guard let self = self else { return }
            if let summary = self.engine.updateContext(route: route, operation: operation, now: self.nowProvider()) {
                self.emit(summary)
            }
        }
    }

    func noteOperation(_ operation: String?) {
        runOnMain { [weak self] in
            guard let self = self else { return }
            _ = self.engine.updateContext(route: nil, operation: operation, now: self.nowProvider())
        }
    }

    func notePerformanceScene(_ sceneName: String?, action: String?) {
        runOnMain { [weak self] in
            guard let self = self else { return }
            let normalizedAction = action?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if normalizedAction == "exit" {
                _ = self.engine.updateContext(route: nil, operation: "scene_exit", now: self.nowProvider())
            } else {
                if let summary = self.engine.updateContext(
                    route: sceneName,
                    operation: "scene_enter",
                    now: self.nowProvider()
                ) {
                    self.emit(summary)
                }
            }
        }
    }

    func notePerformanceMarker(route: String?, phase: String?) {
        runOnMain { [weak self] in
            guard let self = self else { return }
            if let summary = self.engine.updateContext(route: route, operation: phase, now: self.nowProvider()) {
                self.emit(summary)
            }
        }
    }

    private func handleResume() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard installed, !driver.isRunning else { return }
        engine.discardFrameAnchor()
        driver.start { [weak self] sample in
            self?.handleSample(sample)
        }
    }

    private func handlePause(reason: String) {
        dispatchPrecondition(condition: .onQueue(.main))
        driver.stop()
        if let summary = engine.pause(reason: reason, now: nowProvider()) {
            emit(summary)
        }
    }

    private func handleSample(_ sample: RaftIosDisplayLinkFrameSample) {
        dispatchPrecondition(condition: .onQueue(.main))
        let records = engine.recordFrame(sample)
        for record in records {
            emit(record)
        }
    }

    private func emit(_ record: RaftIosDisplayLinkJankLogRecord) {
        logSink?(record.level, record.tag, record.message)
    }

    private func runOnMain(_ work: @escaping () -> Void) {
        if Thread.isMainThread {
            work()
        } else {
            DispatchQueue.main.async(execute: work)
        }
    }
}
