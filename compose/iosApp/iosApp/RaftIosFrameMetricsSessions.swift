import Darwin
import Foundation
import UIKit

/// Configuration for iOS `CADisplayLink` frame-metrics session probes (`mobile.frames.scroll`,
/// `mobile.frames.resume`, `mobile.frames.nav_transition`), matching Android's
/// `RaftScrollFrameProbe`, `RaftResumeFrameProbe`, and `RaftNavTransitionFrameProbe`.
struct RaftIosFrameMetricsSessionConfig: Equatable {
    let scrollMinFrames: Int
    let scrollThrottleIntervalSeconds: Double
    let scrollJankBudgetMultiplier: Double
    let resumeWindowSeconds: Double
    let resumeJankBudgetMultiplier: Double
    let navTransitionStaleRequestSeconds: Double
    let navTransitionTimeoutSeconds: Double
    let navTransitionJankBudgetMultiplier: Double
    let defaultRefreshRateHz: Int
    let minValidBudgetSeconds: Double
    let maxAdaptiveBudgetSeconds: Double

    static let `default` = RaftIosFrameMetricsSessionConfig(
        scrollMinFrames: 5,
        scrollThrottleIntervalSeconds: 1.0,
        scrollJankBudgetMultiplier: 1.75,
        resumeWindowSeconds: 3.0,
        resumeJankBudgetMultiplier: 2.0,
        navTransitionStaleRequestSeconds: 1.0,
        navTransitionTimeoutSeconds: 5.0,
        navTransitionJankBudgetMultiplier: 1.75,
        defaultRefreshRateHz: 60,
        minValidBudgetSeconds: 1.0 / 240.0,
        maxAdaptiveBudgetSeconds: 0.100
    )

    init(
        scrollMinFrames: Int = 5,
        scrollThrottleIntervalSeconds: Double = 1.0,
        scrollJankBudgetMultiplier: Double = 1.75,
        resumeWindowSeconds: Double = 3.0,
        resumeJankBudgetMultiplier: Double = 2.0,
        navTransitionStaleRequestSeconds: Double = 1.0,
        navTransitionTimeoutSeconds: Double = 5.0,
        navTransitionJankBudgetMultiplier: Double = 1.75,
        defaultRefreshRateHz: Int = 60,
        minValidBudgetSeconds: Double = 1.0 / 240.0,
        maxAdaptiveBudgetSeconds: Double = 0.100
    ) {
        self.scrollMinFrames = max(1, scrollMinFrames)
        self.scrollThrottleIntervalSeconds = max(0.0, scrollThrottleIntervalSeconds)
        self.scrollJankBudgetMultiplier = max(1.1, scrollJankBudgetMultiplier)
        self.resumeWindowSeconds = max(0.05, resumeWindowSeconds)
        self.resumeJankBudgetMultiplier = max(1.1, resumeJankBudgetMultiplier)
        self.navTransitionStaleRequestSeconds = max(0.0, navTransitionStaleRequestSeconds)
        self.navTransitionTimeoutSeconds = max(0.1, navTransitionTimeoutSeconds)
        self.navTransitionJankBudgetMultiplier = max(1.1, navTransitionJankBudgetMultiplier)
        self.defaultRefreshRateHz = max(1, defaultRefreshRateHz)
        self.minValidBudgetSeconds = max(0.001, minValidBudgetSeconds)
        self.maxAdaptiveBudgetSeconds = max(self.minValidBudgetSeconds, maxAdaptiveBudgetSeconds)
    }

    func budgetBoundsConfig() -> RaftIosDisplayLinkJankConfig {
        RaftIosDisplayLinkJankConfig(
            defaultRefreshRateHz: defaultRefreshRateHz,
            minValidBudgetSeconds: minValidBudgetSeconds,
            maxAdaptiveBudgetSeconds: maxAdaptiveBudgetSeconds
        )
    }
}

/// Non-identifying hardware/OS metadata for ScopeDB frame-metrics traces.
///
/// Uses `sysctlbyname("hw.machine")` (e.g. `"iPhone17,2"`, never `UIDevice.current.name` or
/// generic `"iPhone"`) and `UIDevice.current.systemVersion` (normalized to major version by
/// `RaftFrameMetricsTrace.kt`).
enum RaftIosFrameMetricsDeviceInfo {
    static func hardwareDeviceModel() -> String? {
        var size: Int = 0
        guard sysctlbyname("hw.machine", nil, &size, nil, 0) == 0, size > 0 else {
            return nil
        }
        var buffer = [CChar](repeating: 0, count: size + 1)
        guard sysctlbyname("hw.machine", &buffer, &size, nil, 0) == 0 else {
            return nil
        }
        let raw = String(cString: buffer).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return nil }
        if raw == "arm64" || raw == "x86_64" || raw == "i386" {
            if let simModel = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"]?
                .trimmingCharacters(in: .whitespacesAndNewlines),
               !simModel.isEmpty
            {
                return simModel
            }
        }
        return raw
    }

    static func systemOsVersion() -> String? {
        let version = UIDevice.current.systemVersion.trimmingCharacters(in: .whitespacesAndNewlines)
        return version.isEmpty ? nil : version
    }
}

/// Payload for `RaftFrameMetricsTraceReporter.reportScroll` (`mobile.frames.scroll`).
struct RaftIosScrollFrameTracePayload: Equatable {
    let page: String
    let list: String
    let reason: String
    let firstFrameMs: Int64
    let durationMs: Int64
    let frames: Int
    let jankFrames: Int
    let longestFrameMs: Int64
    let p95FrameMs: Int64
    let frameIntervalNanos: Int64
    let endFrameIntervalNanos: Int64
    let skippedSessions: Int
    let skippedJankFrames: Int
    let skippedLongestFrameMs: Int64
    let deviceModel: String?
    let osVersion: String?
}

/// Payload for `RaftFrameMetricsTraceReporter.reportScrollSkippedFlush` (`reason = "paused_skipped"`).
struct RaftIosScrollSkippedFlushTracePayload: Equatable {
    let page: String
    let list: String
    let skippedSessions: Int
    let skippedJankFrames: Int
    let skippedLongestFrameMs: Int64
    let deviceModel: String?
    let osVersion: String?
}

/// Payload for `RaftFrameMetricsTraceReporter.reportResume` (`mobile.frames.resume`).
struct RaftIosResumeFrameTracePayload: Equatable {
    let page: String
    let reason: String
    let backgroundMs: Int64
    let startToResumeMs: Int64
    let firstFrameMs: Int64
    let windowMs: Int64
    let frames: Int
    let jankFrames: Int
    let blockedMs: Int64
    let longestFrameMs: Int64
    let frameIntervalNanos: Int64
    let deviceModel: String?
    let osVersion: String?
}

/// Payload for `RaftFrameMetricsTraceReporter.reportNavTransition` (`mobile.frames.nav_transition`).
struct RaftIosNavTransitionFrameTracePayload: Equatable {
    let navKind: String
    let outcome: String
    let fromPage: String
    let toPage: String
    let firstFrameMs: Int64
    let durationMs: Int64
    let frames: Int
    let jankFrames: Int
    let longestFrameMs: Int64
    let p95FrameMs: Int64
    let frameIntervalNanos: Int64
    let endFrameIntervalNanos: Int64
    let deviceModel: String?
    let osVersion: String?
}

/// Injected sink decoupling `RaftIosDisplayLinkJankTracker` from Kotlin's `shared` framework so
/// `iosAppTests` compiles and tests all session trackers without linking `shared`.
struct RaftIosFrameMetricsTraceSink {
    let reportScroll: (RaftIosScrollFrameTracePayload) -> Void
    let reportScrollSkippedFlush: (RaftIosScrollSkippedFlushTracePayload) -> Void
    let reportResume: (RaftIosResumeFrameTracePayload) -> Void
    let reportNavTransition: (RaftIosNavTransitionFrameTracePayload) -> Void
}

/// Carryover folded from throttled scroll sessions onto the next emitted scroll line (or flushed
/// on pause when any throttled session hit jank). Mirrors Android `RaftScrollSkippedCarryover`.
struct RaftIosScrollSkippedCarryover: Equatable {
    let sessions: Int
    let jankFrames: Int
    let longestFrameMs: Int64
    let lastList: String

    static let empty = RaftIosScrollSkippedCarryover(
        sessions: 0,
        jankFrames: 0,
        longestFrameMs: 0,
        lastList: ""
    )
}

/// Enforces `minIntervalSeconds` between emitted scroll records while preserving throttled session
/// counts, total jank frames, worst frame gap, and worst-jank list name. Mirrors Android's
/// `RaftScrollFrameThrottle`.
final class RaftIosScrollFrameThrottle {
    private let minIntervalSeconds: Double

    private var hasEmitted: Bool = false
    private var lastLineTime: Double = 0.0
    private var skippedSessions: Int = 0
    private var skippedJankFrames: Int = 0
    private var skippedLongestFrameMs: Int64 = 0
    private var skippedLastList: String = ""
    private var skippedWorstJankLongestFrameMs: Int64 = -1

    init(minIntervalSeconds: Double = 1.0) {
        self.minIntervalSeconds = max(0.0, minIntervalSeconds)
    }

    /// Records a finished scroll session at `now`. Returns `nil` when throttled (folding the
    /// session into carryover), or the carryover to attach to this emitted record when allowed.
    func record(
        now: Double,
        list: String,
        jankFrames: Int,
        longestFrameMs: Int64
    ) -> RaftIosScrollSkippedCarryover? {
        if hasEmitted && (now - lastLineTime) < minIntervalSeconds {
            skippedSessions += 1
            skippedJankFrames += jankFrames
            if longestFrameMs > skippedLongestFrameMs {
                skippedLongestFrameMs = longestFrameMs
            }
            if jankFrames > 0 {
                if skippedWorstJankLongestFrameMs < 0 || longestFrameMs >= skippedWorstJankLongestFrameMs {
                    skippedWorstJankLongestFrameMs = longestFrameMs
                    skippedLastList = list
                }
            } else if skippedWorstJankLongestFrameMs < 0 {
                skippedLastList = list
            }
            return nil
        }
        hasEmitted = true
        lastLineTime = now
        let carried = RaftIosScrollSkippedCarryover(
            sessions: skippedSessions,
            jankFrames: skippedJankFrames,
            longestFrameMs: skippedLongestFrameMs,
            lastList: skippedLastList
        )
        clearSkipped()
        return carried
    }

    /// Flushes pending skipped sessions when pausing if any of them hit jank, and resets smooth
    /// carryover so the next foreground visit starts clean.
    func flushAndResetOnPause() -> RaftIosScrollSkippedCarryover? {
        let pending: RaftIosScrollSkippedCarryover?
        if skippedSessions > 0 && skippedJankFrames > 0 {
            pending = RaftIosScrollSkippedCarryover(
                sessions: skippedSessions,
                jankFrames: skippedJankFrames,
                longestFrameMs: skippedLongestFrameMs,
                lastList: skippedLastList
            )
        } else {
            pending = nil
        }
        clearSkipped()
        return pending
    }

    func reset() {
        hasEmitted = false
        lastLineTime = 0.0
        clearSkipped()
    }

    private func clearSkipped() {
        skippedSessions = 0
        skippedJankFrames = 0
        skippedLongestFrameMs = 0
        skippedLastList = ""
        skippedWorstJankLongestFrameMs = -1
    }
}

/// Summary snapshot produced by `RaftIosFrameSessionAccumulator`.
struct RaftIosFrameSessionSummary: Equatable {
    let durationMs: Int64
    let frames: Int
    let jankFrames: Int
    let longestFrameMs: Int64
    let shortestFrameMs: Double
    let p95FrameMs: Int64
    let firstFrameMs: Int64
    let frameIntervalNanos: Int64
    let endFrameIntervalNanos: Int64

    func wireFields() -> String {
        let minMs = RaftIosDisplayLinkJankEngine.formatOneDecimal(shortestFrameMs)
        let vsyncMs = RaftIosDisplayLinkJankEngine.formatOneDecimal(Double(frameIntervalNanos) / 1_000_000.0)
        let vsyncEndMs = RaftIosDisplayLinkJankEngine.formatOneDecimal(Double(endFrameIntervalNanos) / 1_000_000.0)
        return "durationMs=\(durationMs) frames=\(frames) jankFrames=\(jankFrames) " +
            "longestFrameMs=\(longestFrameMs) p95FrameMs=\(p95FrameMs) " +
            "minFrameMs=\(minMs) vsyncMs=\(vsyncMs) vsyncEndMs=\(vsyncEndMs)"
    }
}

/// Reusable, zero-per-frame-allocation frame cadence accumulator mirroring Android's
/// `RaftScrollFrameStats` (128 1ms histogram buckets, ProMotion-adaptive effective budget).
final class RaftIosFrameSessionAccumulator {
    static let histogramBuckets = 128

    private var histogram: [Int]
    private var startTime: Double = 0.0
    private var hasAnchor: Bool = false
    private var lastAnchorTime: Double = 0.0
    private var lastBudgetSeconds: Double = 1.0 / 60.0
    private var startBudgetSeconds: Double = 1.0 / 60.0
    private var endBudgetSeconds: Double = 1.0 / 60.0
    private var firstFrameGapNanos: Int64 = -1
    private var frames: Int = 0
    private var jankFrames: Int = 0
    private var longestGapNanos: Int64 = 0
    private var shortestGapNanos: Int64 = Int64.max

    init() {
        self.histogram = Array(repeating: 0, count: Self.histogramBuckets)
    }

    func begin(startTime: Double, initialBudgetSeconds: Double) {
        self.startTime = startTime
        self.hasAnchor = false
        self.lastAnchorTime = 0.0
        self.lastBudgetSeconds = initialBudgetSeconds
        self.startBudgetSeconds = initialBudgetSeconds
        self.endBudgetSeconds = initialBudgetSeconds
        self.firstFrameGapNanos = -1
        self.frames = 0
        self.jankFrames = 0
        self.longestGapNanos = 0
        self.shortestGapNanos = Int64.max
        for index in 0..<histogram.count {
            histogram[index] = 0
        }
    }

    /// Scroll-session vsync callback mirroring Android `RaftScrollFrameStats.onScrollVsync`:
    /// anchors on the first callback and measures scroll-start -> first-frame latency; if that
    /// opening frame already missed a vsync (`>= jankMultiplier * budget`), counts it as a jank
    /// frame and includes it in longest/p95 stats.
    func onScrollVsync(
        _ sample: RaftIosDisplayLinkFrameSample,
        budgetConfig: RaftIosDisplayLinkJankConfig,
        jankMultiplier: Double
    ) {
        guard sample.timestamp.isFinite, sample.timestamp > 0.0,
              sample.now.isFinite, sample.now > 0.0
        else {
            return
        }
        let resolved = RaftIosDisplayLinkJankEngine.resolvedBudgetSeconds(
            timestamp: sample.timestamp,
            targetTimestamp: sample.targetTimestamp,
            duration: sample.duration,
            maxFramesPerSecond: sample.maxFramesPerSecond,
            config: budgetConfig
        )
        let currentBudget = resolved.budgetSeconds

        if hasAnchor {
            recordSubsequentVsync(sample: sample, currentBudget: currentBudget, jankMultiplier: jankMultiplier)
            return
        }

        hasAnchor = true
        startBudgetSeconds = currentBudget
        endBudgetSeconds = currentBudget
        lastBudgetSeconds = currentBudget

        let firstGapNanos = Self.secondsToNanos(sample.now - startTime)
        firstFrameGapNanos = firstGapNanos

        // Advance stale pre-stall vsync timestamps in whole budget steps up to `now` so the next
        // vsync neither double-counts the same stall nor records an artificial sub-vsync interval.
        let lag = max(0.0, sample.now - sample.timestamp)
        if currentBudget > 0.0 && lag >= currentBudget {
            lastAnchorTime = sample.timestamp + floor(lag / currentBudget) * currentBudget
        } else {
            lastAnchorTime = sample.timestamp
        }

        let budgetNanos = Self.secondsToNanos(currentBudget)
        if Self.isJank(gapNanos: firstGapNanos, budgetNanos: budgetNanos, multiplier: jankMultiplier) {
            frames += 1
            jankFrames += 1
            if firstGapNanos > longestGapNanos { longestGapNanos = firstGapNanos }
            if firstGapNanos < shortestGapNanos { shortestGapNanos = firstGapNanos }
            incrementHistogram(gapNanos: firstGapNanos)
        }
    }

    /// Navigation-transition vsync callback mirroring Android `RaftNavTransitionFrameProbe`:
    /// stamps `firstFrameMs` on the first callback and anchors cadence; subsequent ticks record
    /// frame gaps, jank frames, and p95.
    func onNavVsync(
        _ sample: RaftIosDisplayLinkFrameSample,
        budgetConfig: RaftIosDisplayLinkJankConfig,
        jankMultiplier: Double
    ) {
        guard sample.timestamp.isFinite, sample.timestamp > 0.0,
              sample.now.isFinite, sample.now > 0.0
        else {
            return
        }
        let resolved = RaftIosDisplayLinkJankEngine.resolvedBudgetSeconds(
            timestamp: sample.timestamp,
            targetTimestamp: sample.targetTimestamp,
            duration: sample.duration,
            maxFramesPerSecond: sample.maxFramesPerSecond,
            config: budgetConfig
        )
        let currentBudget = resolved.budgetSeconds

        if !hasAnchor {
            hasAnchor = true
            startBudgetSeconds = currentBudget
            endBudgetSeconds = currentBudget
            lastBudgetSeconds = currentBudget
            let callbackPastTarget =
                sample.targetTimestamp.isFinite &&
                sample.targetTimestamp > sample.timestamp &&
                sample.now > sample.targetTimestamp
            let effectiveFirst = callbackPastTarget ? max(sample.timestamp, sample.now) : sample.timestamp
            firstFrameGapNanos = Self.secondsToNanos(effectiveFirst - startTime)
            lastAnchorTime = effectiveFirst
            return
        }

        recordSubsequentVsync(sample: sample, currentBudget: currentBudget, jankMultiplier: jankMultiplier)
    }

    func summary(now: Double) -> RaftIosFrameSessionSummary {
        let durationNanos = Self.secondsToNanos(now - startTime)
        let shortestMs = shortestGapNanos != Int64.max ? Double(shortestGapNanos) / 1_000_000.0 : 0.0
        let firstMs: Int64 = firstFrameGapNanos >= 0 ? (firstFrameGapNanos / 1_000_000) : -1
        return RaftIosFrameSessionSummary(
            durationMs: durationNanos / 1_000_000,
            frames: frames,
            jankFrames: jankFrames,
            longestFrameMs: longestGapNanos / 1_000_000,
            shortestFrameMs: shortestMs,
            p95FrameMs: percentileMs(percent: 95),
            firstFrameMs: firstMs,
            frameIntervalNanos: max(1, Self.secondsToNanos(startBudgetSeconds)),
            endFrameIntervalNanos: max(1, Self.secondsToNanos(endBudgetSeconds))
        )
    }

    private func recordSubsequentVsync(
        sample: RaftIosDisplayLinkFrameSample,
        currentBudget: Double,
        jankMultiplier: Double
    ) {
        let effectiveBudget = max(lastBudgetSeconds, currentBudget)
        let callbackPastTarget =
            sample.targetTimestamp.isFinite &&
            sample.targetTimestamp > sample.timestamp &&
            sample.now > sample.targetTimestamp
        let effectiveEnd = callbackPastTarget ? max(sample.timestamp, sample.now) : sample.timestamp

        let gapNanos = Self.secondsToNanos(effectiveEnd - lastAnchorTime)
        if effectiveEnd > lastAnchorTime {
            lastAnchorTime = effectiveEnd
        }
        lastBudgetSeconds = currentBudget
        endBudgetSeconds = currentBudget

        frames += 1
        if gapNanos > longestGapNanos { longestGapNanos = gapNanos }
        if gapNanos < shortestGapNanos { shortestGapNanos = gapNanos }

        let effectiveBudgetNanos = Self.secondsToNanos(effectiveBudget)
        if Self.isJank(gapNanos: gapNanos, budgetNanos: effectiveBudgetNanos, multiplier: jankMultiplier) {
            jankFrames += 1
        }
        incrementHistogram(gapNanos: gapNanos)
    }

    private func incrementHistogram(gapNanos: Int64) {
        let bucket = min(max(Int(gapNanos / 1_000_000), 0), Self.histogramBuckets - 1)
        histogram[bucket] += 1
    }

    /// Upper edge (ms) of the histogram bucket holding the `percent`th frame; 0 with no frames.
    /// Exact match with Android `RaftScrollFrameStats.percentileMs`.
    private func percentileMs(percent: Int) -> Int64 {
        guard frames > 0 else { return 0 }
        let rank = (Int64(frames) * Int64(percent) + 99) / 100
        var seen: Int64 = 0
        for bucket in 0..<histogram.count {
            seen += Int64(histogram[bucket])
            if seen >= rank {
                return Int64(bucket + 1)
            }
        }
        return Int64(Self.histogramBuckets)
    }

    static func secondsToNanos(_ seconds: Double) -> Int64 {
        guard seconds.isFinite, seconds > 0.0 else { return 0 }
        let clamped = min(seconds, 86_400.0)
        return Int64((clamped * 1_000_000_000.0).rounded())
    }

    static func isJank(gapNanos: Int64, budgetNanos: Int64, multiplier: Double) -> Bool {
        guard budgetNanos > 0 else { return false }
        if multiplier == 1.75 {
            return gapNanos * 4 >= 7 * budgetNanos
        }
        if multiplier == 2.0 {
            return gapNanos >= 2 * budgetNanos
        }
        return Double(gapNanos) >= Double(budgetNanos) * multiplier
    }
}

/// Output emitted by `RaftIosScrollFrameSessionTracker` when a scroll session or paused-skipped
/// flush finishes.
struct RaftIosScrollSessionEmission: Equatable {
    let logRecord: RaftIosDisplayLinkJankLogRecord
    let tracePayload: RaftIosScrollFrameTracePayload?
    let skippedFlushPayload: RaftIosScrollSkippedFlushTracePayload?
}

/// Tracks iOS scroll sessions driven by `RaftScrollFrameSignal` (`list`, `scrolling`), mirroring
/// Android's `RaftScrollFrameProbe`: `MinFrames = 5`, 1s throttle with jank carryover, and
/// `paused_skipped` flush on resign-active/background.
final class RaftIosScrollFrameSessionTracker {
    private struct ActiveSession {
        let page: String
        let list: String
    }

    let config: RaftIosFrameMetricsSessionConfig
    private let budgetConfig: RaftIosDisplayLinkJankConfig
    private let throttle: RaftIosScrollFrameThrottle
    private let accumulator = RaftIosFrameSessionAccumulator()
    private var active: ActiveSession?

    init(config: RaftIosFrameMetricsSessionConfig = .default) {
        self.config = config
        self.budgetConfig = config.budgetBoundsConfig()
        self.throttle = RaftIosScrollFrameThrottle(minIntervalSeconds: config.scrollThrottleIntervalSeconds)
    }

    var isTrackingScroll: Bool {
        active != nil
    }

    func onScrollChanged(
        list: String,
        scrolling: Bool,
        page: String,
        isForegroundActive: Bool,
        now: Double,
        maxFramesPerSecond: Int,
        deviceModel: String?,
        osVersion: String?
    ) -> RaftIosScrollSessionEmission? {
        let safeList = RaftIosDisplayLinkJankEngine.sanitizeIdentifier(list, fallback: "other")
        if !scrolling {
            guard let current = active, current.list == safeList else { return nil }
            return finishActive(
                reason: "stopped",
                now: now,
                deviceModel: deviceModel,
                osVersion: osVersion
            )
        }
        // Nested pager + list scrolling together: keep the first active session (matches Android).
        guard active == nil, isForegroundActive else { return nil }
        let safePage = RaftIosDisplayLinkJankEngine.sanitizeIdentifier(page, fallback: "unknown")
        let fps = maxFramesPerSecond > 0 ? maxFramesPerSecond : config.defaultRefreshRateHz
        accumulator.begin(startTime: now, initialBudgetSeconds: 1.0 / Double(fps))
        active = ActiveSession(page: safePage, list: safeList)
        return nil
    }

    func onFrame(_ sample: RaftIosDisplayLinkFrameSample) {
        guard active != nil else { return }
        accumulator.onScrollVsync(
            sample,
            budgetConfig: budgetConfig,
            jankMultiplier: config.scrollJankBudgetMultiplier
        )
    }

    func onPause(
        page: String,
        now: Double,
        deviceModel: String?,
        osVersion: String?
    ) -> [RaftIosScrollSessionEmission] {
        var emissions: [RaftIosScrollSessionEmission] = []
        if let activeEmission = finishActive(
            reason: "paused",
            now: now,
            deviceModel: deviceModel,
            osVersion: osVersion
        ) {
            emissions.append(activeEmission)
        }
        if let pending = throttle.flushAndResetOnPause() {
            let safePage = RaftIosDisplayLinkJankEngine.sanitizeIdentifier(page, fallback: "unknown")
            let listForLog = pending.lastList.isEmpty ? "unknown" : pending.lastList
            let logRecord = RaftIosDisplayLinkJankLogRecord(
                level: "warn",
                tag: RaftIosDisplayLinkJankEngine.tag,
                message:
                    "scroll_frames page=\(safePage) list=\(listForLog) reason=paused_skipped " +
                    "skippedSessions=\(pending.sessions) skippedJankFrames=\(pending.jankFrames) " +
                    "skippedLongestFrameMs=\(pending.longestFrameMs)"
            )
            let flushPayload = RaftIosScrollSkippedFlushTracePayload(
                page: safePage,
                list: pending.lastList,
                skippedSessions: pending.sessions,
                skippedJankFrames: pending.jankFrames,
                skippedLongestFrameMs: pending.longestFrameMs,
                deviceModel: deviceModel,
                osVersion: osVersion
            )
            emissions.append(
                RaftIosScrollSessionEmission(
                    logRecord: logRecord,
                    tracePayload: nil,
                    skippedFlushPayload: flushPayload
                )
            )
        }
        return emissions
    }

    func reset() {
        active = nil
        throttle.reset()
    }

    private func finishActive(
        reason: String,
        now: Double,
        deviceModel: String?,
        osVersion: String?
    ) -> RaftIosScrollSessionEmission? {
        guard let current = active else { return nil }
        active = nil
        let summary = accumulator.summary(now: now)
        // Drop tiny smooth micro-touches (< scrollMinFrames), never a short scroll that hit jank.
        if summary.frames < config.scrollMinFrames && summary.jankFrames == 0 {
            return nil
        }
        guard let carried = throttle.record(
            now: now,
            list: current.list,
            jankFrames: summary.jankFrames,
            longestFrameMs: summary.longestFrameMs
        ) else {
            return nil
        }
        let firstFramePart = summary.firstFrameMs >= 0 ? " firstFrameMs=\(summary.firstFrameMs)" : ""
        let skippedStatsPart = carried.sessions > 0
            ? " skippedJankFrames=\(carried.jankFrames) skippedLongestFrameMs=\(carried.longestFrameMs)"
            : ""
        let logRecord = RaftIosDisplayLinkJankLogRecord(
            level: "warn",
            tag: RaftIosDisplayLinkJankEngine.tag,
            message:
                "scroll_frames page=\(current.page) list=\(current.list) reason=\(reason)\(firstFramePart) " +
                "\(summary.wireFields()) skippedSessions=\(carried.sessions)\(skippedStatsPart)"
        )
        let tracePayload = RaftIosScrollFrameTracePayload(
            page: current.page,
            list: current.list,
            reason: reason,
            firstFrameMs: summary.firstFrameMs,
            durationMs: summary.durationMs,
            frames: summary.frames,
            jankFrames: summary.jankFrames,
            longestFrameMs: summary.longestFrameMs,
            p95FrameMs: summary.p95FrameMs,
            frameIntervalNanos: summary.frameIntervalNanos,
            endFrameIntervalNanos: summary.endFrameIntervalNanos,
            skippedSessions: carried.sessions,
            skippedJankFrames: carried.jankFrames,
            skippedLongestFrameMs: carried.longestFrameMs,
            deviceModel: deviceModel,
            osVersion: osVersion
        )
        return RaftIosScrollSessionEmission(
            logRecord: logRecord,
            tracePayload: tracePayload,
            skippedFlushPayload: nil
        )
    }
}

/// Output emitted by `RaftIosResumeFrameSessionTracker` when a foreground-return window finishes.
struct RaftIosResumeSessionEmission: Equatable {
    let logRecord: RaftIosDisplayLinkJankLogRecord
    let tracePayload: RaftIosResumeFrameTracePayload
}

/// Tracks foreground-return main-thread frame windows (`mobile.frames.resume`) after genuine
/// `didEnterBackground` transitions, mirroring Android's `RaftResumeFrameProbe` (3s window,
/// `>= 2x` vsync jank/blockedMs accumulation, `firstFrameMs`, `backgroundMs`, `startToResumeMs`).
final class RaftIosResumeFrameSessionTracker {
    private struct ActiveResume {
        let page: String
        let backgroundMs: Int64
        let startToResumeMs: Int64
        let startTime: Double
        var startBudgetSeconds: Double
        var lastBudgetSeconds: Double?
        var firstFrameTime: Double?
        var lastCallbackTime: Double?
        var frames: Int = 0
        var jankFrames: Int = 0
        var blockedNanos: Int64 = 0
        var longestGapNanos: Int64 = 0
    }

    let config: RaftIosFrameMetricsSessionConfig
    private let budgetConfig: RaftIosDisplayLinkJankConfig
    private var backgroundAt: Double?
    private var pendingForegroundAt: Double?
    private var pendingBackgroundMs: Int64 = -1
    private var active: ActiveResume?

    init(config: RaftIosFrameMetricsSessionConfig = .default) {
        self.config = config
        self.budgetConfig = config.budgetBoundsConfig()
    }

    var isTrackingResume: Bool {
        active != nil
    }

    func onWillResignActive(
        now: Double,
        deviceModel: String?,
        osVersion: String?
    ) -> RaftIosResumeSessionEmission? {
        finishActive(reason: "paused", now: now, deviceModel: deviceModel, osVersion: osVersion)
    }

    func onDidEnterBackground(
        now: Double,
        deviceModel: String?,
        osVersion: String?
    ) -> RaftIosResumeSessionEmission? {
        backgroundAt = now
        pendingForegroundAt = nil
        pendingBackgroundMs = -1
        return finishActive(reason: "hidden", now: now, deviceModel: deviceModel, osVersion: osVersion)
    }

    func onWillEnterForeground(now: Double) {
        guard let hiddenAt = backgroundAt else { return }
        backgroundAt = nil
        pendingForegroundAt = now
        pendingBackgroundMs = RaftIosFrameSessionAccumulator.secondsToNanos(now - hiddenAt) / 1_000_000
    }

    func onDidBecomeActive(
        page: String,
        now: Double,
        maxFramesPerSecond: Int,
        deviceModel: String?,
        osVersion: String?
    ) -> RaftIosResumeSessionEmission? {
        let backgroundMs: Int64
        let startToResumeMs: Int64
        if let foregroundAt = pendingForegroundAt {
            backgroundMs = pendingBackgroundMs
            startToResumeMs = RaftIosFrameSessionAccumulator.secondsToNanos(now - foregroundAt) / 1_000_000
            pendingForegroundAt = nil
            pendingBackgroundMs = -1
        } else if let hiddenAt = backgroundAt {
            backgroundMs = RaftIosFrameSessionAccumulator.secondsToNanos(now - hiddenAt) / 1_000_000
            startToResumeMs = 0
            backgroundAt = nil
        } else {
            return nil
        }

        let superseded = finishActive(reason: "superseded", now: now, deviceModel: deviceModel, osVersion: osVersion)
        let safePage = RaftIosDisplayLinkJankEngine.sanitizeIdentifier(page, fallback: "unknown")
        let fps = maxFramesPerSecond > 0 ? maxFramesPerSecond : config.defaultRefreshRateHz
        active = ActiveResume(
            page: safePage,
            backgroundMs: backgroundMs,
            startToResumeMs: startToResumeMs,
            startTime: now,
            startBudgetSeconds: 1.0 / Double(fps),
            lastBudgetSeconds: nil,
            firstFrameTime: nil,
            lastCallbackTime: nil
        )
        return superseded
    }

    func onFrame(
        _ sample: RaftIosDisplayLinkFrameSample,
        deviceModel: String?,
        osVersion: String?
    ) -> RaftIosResumeSessionEmission? {
        guard var current = active else { return nil }
        guard sample.now.isFinite, sample.now > 0.0 else { return nil }

        let resolved = RaftIosDisplayLinkJankEngine.resolvedBudgetSeconds(
            timestamp: sample.timestamp,
            targetTimestamp: sample.targetTimestamp,
            duration: sample.duration,
            maxFramesPerSecond: sample.maxFramesPerSecond,
            config: budgetConfig
        )
        let currentBudget = resolved.budgetSeconds
        if current.firstFrameTime == nil {
            current.firstFrameTime = sample.now
            current.startBudgetSeconds = currentBudget
        }
        let effectiveBudget = max(current.lastBudgetSeconds ?? currentBudget, currentBudget)
        current.lastBudgetSeconds = currentBudget

        let previous = current.lastCallbackTime ?? current.startTime
        let gapNanos = RaftIosFrameSessionAccumulator.secondsToNanos(sample.now - previous)
        current.lastCallbackTime = sample.now
        current.frames += 1
        if gapNanos > current.longestGapNanos {
            current.longestGapNanos = gapNanos
        }
        let effectiveBudgetNanos = RaftIosFrameSessionAccumulator.secondsToNanos(effectiveBudget)
        if RaftIosFrameSessionAccumulator.isJank(
            gapNanos: gapNanos,
            budgetNanos: effectiveBudgetNanos,
            multiplier: config.resumeJankBudgetMultiplier
        ) {
            current.jankFrames += 1
            current.blockedNanos += max(0, gapNanos - effectiveBudgetNanos)
        }

        active = current
        if (sample.now - current.startTime) >= config.resumeWindowSeconds {
            return finishActive(reason: "window", now: sample.now, deviceModel: deviceModel, osVersion: osVersion)
        }
        return nil
    }

    func reset() {
        backgroundAt = nil
        pendingForegroundAt = nil
        pendingBackgroundMs = -1
        active = nil
    }

    private func finishActive(
        reason: String,
        now: Double,
        deviceModel: String?,
        osVersion: String?
    ) -> RaftIosResumeSessionEmission? {
        guard let current = active else { return nil }
        active = nil
        let windowMs = RaftIosFrameSessionAccumulator.secondsToNanos(now - current.startTime) / 1_000_000
        let firstFrameMs: Int64
        if let firstTime = current.firstFrameTime {
            firstFrameMs = RaftIosFrameSessionAccumulator.secondsToNanos(firstTime - current.startTime) / 1_000_000
        } else {
            firstFrameMs = -1
        }
        let blockedMs = current.blockedNanos / 1_000_000
        let longestFrameMs = current.longestGapNanos / 1_000_000
        let frameIntervalNanos = max(1, RaftIosFrameSessionAccumulator.secondsToNanos(current.startBudgetSeconds))
        let vsyncMs = RaftIosDisplayLinkJankEngine.formatOneDecimal(Double(frameIntervalNanos) / 1_000_000.0)
        let logRecord = RaftIosDisplayLinkJankLogRecord(
            level: "warn",
            tag: RaftIosDisplayLinkJankEngine.tag,
            message:
                "resume_frames page=\(current.page) reason=\(reason) " +
                "backgroundMs=\(current.backgroundMs) startToResumeMs=\(current.startToResumeMs) " +
                "firstFrameMs=\(firstFrameMs) windowMs=\(windowMs) frames=\(current.frames) " +
                "jankFrames=\(current.jankFrames) blockedMs=\(blockedMs) " +
                "longestFrameMs=\(longestFrameMs) vsyncMs=\(vsyncMs)"
        )
        let payload = RaftIosResumeFrameTracePayload(
            page: current.page,
            reason: reason,
            backgroundMs: current.backgroundMs,
            startToResumeMs: current.startToResumeMs,
            firstFrameMs: firstFrameMs,
            windowMs: windowMs,
            frames: current.frames,
            jankFrames: current.jankFrames,
            blockedMs: blockedMs,
            longestFrameMs: longestFrameMs,
            frameIntervalNanos: frameIntervalNanos,
            deviceModel: deviceModel,
            osVersion: osVersion
        )
        return RaftIosResumeSessionEmission(logRecord: logRecord, tracePayload: payload)
    }
}

/// Output emitted by `RaftIosNavTransitionSessionTracker` when a page transition finishes.
struct RaftIosNavTransitionSessionEmission: Equatable {
    let logRecord: RaftIosDisplayLinkJankLogRecord
    let tracePayload: RaftIosNavTransitionFrameTracePayload
}

/// Tracks UIKit push/pop page transitions (`mobile.frames.nav_transition`) with interactive
/// cancel support, stale navigate-request filtering, pause interruption, and a timeout cap.
/// Mirrors Android's `RaftNavTransitionFrameProbe`.
final class RaftIosNavTransitionSessionTracker {
    private struct ActiveTransition {
        let token: UInt64
        let navKind: String
        let fromPage: String
        let toPage: String
        let startTime: Double
    }

    let config: RaftIosFrameMetricsSessionConfig
    private let budgetConfig: RaftIosDisplayLinkJankConfig
    private let accumulator = RaftIosFrameSessionAccumulator()
    private var navigateRequestedAt: Double?
    private var nextToken: UInt64 = 0
    private var active: ActiveTransition?

    init(config: RaftIosFrameMetricsSessionConfig = .default) {
        self.config = config
        self.budgetConfig = config.budgetBoundsConfig()
    }

    var isTrackingTransition: Bool {
        active != nil
    }

    func noteNavigateRequested(now: Double) {
        guard now.isFinite, now > 0.0 else { return }
        if let existing = navigateRequestedAt, (now - existing) < config.navTransitionStaleRequestSeconds {
            return
        }
        navigateRequestedAt = now
    }

    func beginTransition(
        kind: String,
        fromPage: String,
        toPage: String,
        interactive: Bool,
        now: Double,
        maxFramesPerSecond: Int,
        deviceModel: String?,
        osVersion: String?
    ) -> (token: UInt64, interrupted: RaftIosNavTransitionSessionEmission?) {
        let interrupted = finishActive(outcome: "interrupted", now: now, deviceModel: deviceModel, osVersion: osVersion)
        let startTime: Double
        if !interactive,
           let requested = navigateRequestedAt,
           now >= requested,
           (now - requested) < config.navTransitionStaleRequestSeconds
        {
            startTime = requested
        } else {
            startTime = now
        }
        navigateRequestedAt = nil

        nextToken &+= 1
        if nextToken == 0 {
            nextToken = 1
        }
        let token = nextToken
        let safeKind = RaftIosDisplayLinkJankEngine.sanitizeIdentifier(kind, fallback: "other")
        let safeFrom = RaftIosDisplayLinkJankEngine.sanitizeIdentifier(fromPage, fallback: "other")
        let safeTo = RaftIosDisplayLinkJankEngine.sanitizeIdentifier(toPage, fallback: "other")
        let fps = maxFramesPerSecond > 0 ? maxFramesPerSecond : config.defaultRefreshRateHz
        accumulator.begin(startTime: startTime, initialBudgetSeconds: 1.0 / Double(fps))
        active = ActiveTransition(
            token: token,
            navKind: safeKind,
            fromPage: safeFrom,
            toPage: safeTo,
            startTime: startTime
        )
        return (token, interrupted)
    }

    func onFrame(
        _ sample: RaftIosDisplayLinkFrameSample,
        deviceModel: String?,
        osVersion: String?
    ) -> RaftIosNavTransitionSessionEmission? {
        guard let current = active else { return nil }
        accumulator.onNavVsync(
            sample,
            budgetConfig: budgetConfig,
            jankMultiplier: config.navTransitionJankBudgetMultiplier
        )
        if sample.now.isFinite && (sample.now - current.startTime) >= config.navTransitionTimeoutSeconds {
            return finishActive(outcome: "interrupted", now: sample.now, deviceModel: deviceModel, osVersion: osVersion)
        }
        return nil
    }

    func endTransition(
        token: UInt64,
        outcome: String,
        now: Double,
        deviceModel: String?,
        osVersion: String?
    ) -> RaftIosNavTransitionSessionEmission? {
        navigateRequestedAt = nil
        guard let current = active, current.token == token else { return nil }
        let safeOutcome = RaftIosDisplayLinkJankEngine.sanitizeIdentifier(outcome, fallback: "other")
        return finishActive(outcome: safeOutcome, now: now, deviceModel: deviceModel, osVersion: osVersion)
    }

    func onPause(
        now: Double,
        deviceModel: String?,
        osVersion: String?
    ) -> RaftIosNavTransitionSessionEmission? {
        navigateRequestedAt = nil
        return finishActive(outcome: "interrupted", now: now, deviceModel: deviceModel, osVersion: osVersion)
    }

    func reset() {
        navigateRequestedAt = nil
        active = nil
    }

    private func finishActive(
        outcome: String,
        now: Double,
        deviceModel: String?,
        osVersion: String?
    ) -> RaftIosNavTransitionSessionEmission? {
        guard let current = active else { return nil }
        active = nil
        let summary = accumulator.summary(now: now)
        let logRecord = RaftIosDisplayLinkJankLogRecord(
            level: "warn",
            tag: RaftIosDisplayLinkJankEngine.tag,
            message:
                "nav_transition_frames kind=\(current.navKind) outcome=\(outcome) " +
                "from=\(current.fromPage) to=\(current.toPage) " +
                "firstFrameMs=\(summary.firstFrameMs) \(summary.wireFields())"
        )
        let payload = RaftIosNavTransitionFrameTracePayload(
            navKind: current.navKind,
            outcome: outcome,
            fromPage: current.fromPage,
            toPage: current.toPage,
            firstFrameMs: summary.firstFrameMs,
            durationMs: summary.durationMs,
            frames: summary.frames,
            jankFrames: summary.jankFrames,
            longestFrameMs: summary.longestFrameMs,
            p95FrameMs: summary.p95FrameMs,
            frameIntervalNanos: summary.frameIntervalNanos,
            endFrameIntervalNanos: summary.endFrameIntervalNanos,
            deviceModel: deviceModel,
            osVersion: osVersion
        )
        return RaftIosNavTransitionSessionEmission(logRecord: logRecord, tracePayload: payload)
    }
}
