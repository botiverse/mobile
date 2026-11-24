import UIKit
import XCTest

final class RaftIosDisplayLinkJankTrackerTests: XCTestCase {
    private final class FakeDisplayLinkDriver: RaftIosDisplayLinkDriver {
        private(set) var isRunning = false
        private(set) var startCount = 0
        private(set) var stopCount = 0
        private var tickHandler: ((RaftIosDisplayLinkFrameSample) -> Void)?

        func start(onTick: @escaping (RaftIosDisplayLinkFrameSample) -> Void) {
            guard !isRunning else { return }
            isRunning = true
            startCount += 1
            tickHandler = onTick
        }

        func stop() {
            guard isRunning else { return }
            isRunning = false
            stopCount += 1
            tickHandler = nil
        }

        func emit(_ sample: RaftIosDisplayLinkFrameSample) {
            tickHandler?(sample)
        }
    }

    private func sample(
        timestamp: Double,
        budget: Double = 1.0 / 60.0,
        duration: Double? = nil,
        now: Double? = nil,
        maxFps: Int = 60
    ) -> RaftIosDisplayLinkFrameSample {
        RaftIosDisplayLinkFrameSample(
            timestamp: timestamp,
            targetTimestamp: timestamp + budget,
            duration: duration ?? (1.0 / Double(maxFps)),
            now: now ?? (timestamp + 0.0005),
            maxFramesPerSecond: maxFps
        )
    }

    private func fields(from message: String) -> [String: String] {
        var result: [String: String] = [:]
        let tokens = message.split(separator: " ")
        if let first = tokens.first {
            result["_event"] = String(first)
        }
        for token in tokens.dropFirst() {
            let parts = token.split(separator: "=", maxSplits: 1)
            if parts.count == 2 {
                result[String(parts[0])] = String(parts[1])
            }
        }
        return result
    }

    func testFirstFrameOnlyAnchorsAndPauseDiscardsResumeSpike() {
        let engine = RaftIosDisplayLinkJankEngine()
        _ = engine.updateContext(route: "compose_thread", operation: "page_appear", now: 100.0)

        // First callback after start establishes anchor only.
        XCTAssertTrue(engine.recordFrame(sample(timestamp: 100.0)).isEmpty)
        XCTAssertTrue(engine.hasFrameAnchor)
        XCTAssertEqual(engine.currentWindowFrames, 0)

        // Smooth 60Hz frames.
        XCTAssertTrue(engine.recordFrame(sample(timestamp: 100.01667)).isEmpty)
        XCTAssertTrue(engine.recordFrame(sample(timestamp: 100.03333)).isEmpty)
        XCTAssertEqual(engine.currentWindowFrames, 2)
        XCTAssertEqual(engine.totalJankFrames, 0)
        XCTAssertEqual(engine.totalFrozenFrames, 0)

        // Pause flushes smooth summary and clears anchor.
        let pausedSummary = engine.pause(reason: "enter_background", now: 100.05)
        XCTAssertNotNil(pausedSummary)
        XCTAssertEqual(pausedSummary?.level, "info")
        XCTAssertEqual(pausedSummary?.tag, "SlockPerf")
        let pausedFields = fields(from: pausedSummary?.message ?? "")
        XCTAssertEqual(pausedFields["_event"], "summary")
        XCTAssertEqual(pausedFields["reason"], "enter_background")
        XCTAssertEqual(pausedFields["route"], "compose_thread")
        XCTAssertEqual(pausedFields["frames"], "2")
        XCTAssertEqual(pausedFields["jankFrames"], "0")
        XCTAssertEqual(pausedFields["frozenFrames"], "0")
        XCTAssertFalse(engine.hasFrameAnchor)

        // Redundant second pause while empty produces no duplicate summary.
        XCTAssertNil(engine.pause(reason: "enter_background", now: 100.06))

        // App returns 60 seconds later: first frame MUST be discarded as a fresh anchor, never a frozen frame.
        XCTAssertTrue(engine.recordFrame(sample(timestamp: 160.05)).isEmpty)
        XCTAssertEqual(engine.totalJankFrames, 0)
        XCTAssertEqual(engine.totalFrozenFrames, 0)
        XCTAssertEqual(engine.currentWindowFrames, 0)

        // Next smooth frame after re-anchoring is recorded cleanly.
        XCTAssertTrue(engine.recordFrame(sample(timestamp: 160.06667)).isEmpty)
        XCTAssertEqual(engine.currentWindowFrames, 1)
        XCTAssertEqual(engine.totalJankFrames, 0)
    }

    func testProMotionAdaptiveRefreshTransitionsDoNotFalsePositiveAsJank() {
        let engine = RaftIosDisplayLinkJankEngine()
        let hz120 = 1.0 / 120.0
        let hz80 = 1.0 / 80.0
        let hz60 = 1.0 / 60.0
        let hz24 = 1.0 / 24.0

        var t = 200.0
        XCTAssertTrue(engine.recordFrame(sample(timestamp: t, budget: hz120, maxFps: 120)).isEmpty)

        // Steady 120Hz frames.
        for _ in 0..<5 {
            t += hz120
            XCTAssertTrue(engine.recordFrame(sample(timestamp: t, budget: hz120, maxFps: 120)).isEmpty)
        }

        // ProMotion ramps down 120Hz -> 80Hz -> 60Hz -> 24Hz, then back up to 120Hz.
        t += hz80
        XCTAssertTrue(engine.recordFrame(sample(timestamp: t, budget: hz80, maxFps: 120)).isEmpty)

        t += hz60
        XCTAssertTrue(engine.recordFrame(sample(timestamp: t, budget: hz60, maxFps: 120)).isEmpty)

        t += hz24
        XCTAssertTrue(engine.recordFrame(sample(timestamp: t, budget: hz24, maxFps: 120)).isEmpty)

        t += hz24
        XCTAssertTrue(engine.recordFrame(sample(timestamp: t, budget: hz120, maxFps: 120)).isEmpty)

        t += hz120
        XCTAssertTrue(engine.recordFrame(sample(timestamp: t, budget: hz120, maxFps: 120)).isEmpty)

        XCTAssertEqual(engine.totalJankFrames, 0, "ProMotion adaptive step-downs must not false-positive")
        XCTAssertEqual(engine.totalFrozenFrames, 0)

        // Real 120Hz stall (34ms = ~4x 8.33ms budget -> 3 missed vsyncs) triggers detailed jank_frame.
        t += 0.034
        let stallRecords = engine.recordFrame(sample(timestamp: t, budget: hz120, maxFps: 120))
        XCTAssertEqual(stallRecords.count, 1)
        XCTAssertEqual(stallRecords[0].level, "warn")
        let f = fields(from: stallRecords[0].message)
        XCTAssertEqual(f["_event"], "jank_frame")
        XCTAssertEqual(f["durationMs"], "34")
        XCTAssertEqual(f["budgetMs"], "8.3")
        XCTAssertEqual(f["missedVsync"], "3")
        XCTAssertEqual(f["maxFps"], "120")
        XCTAssertEqual(f["totalJank"], "1")
        XCTAssertEqual(f["totalFrozen"], "0")
    }

    func testJankDetailedJankRateLimitingAndFrozenFrameClassification() {
        let engine = RaftIosDisplayLinkJankEngine()
        _ = engine.updateContext(route: "kuikly_home", operation: "bottom_tab_home", now: 300.0)

        let hz60 = 1.0 / 60.0
        var t = 300.0
        _ = engine.recordFrame(sample(timestamp: t, budget: hz60, maxFps: 60))

        // Minor 60Hz jank (34ms = 1 missed vsync, < 64ms and < 3 missed vsyncs): counted, not spammed individually.
        t += 0.034
        XCTAssertTrue(engine.recordFrame(sample(timestamp: t, budget: hz60, maxFps: 60)).isEmpty)
        XCTAssertEqual(engine.totalJankFrames, 1)

        // Detailed jank (85ms >= 64ms): emits warn jank_frame with Android-aligned fields.
        t += 0.085
        let firstDetailed = engine.recordFrame(sample(timestamp: t, budget: hz60, maxFps: 60))
        XCTAssertEqual(firstDetailed.count, 1)
        XCTAssertEqual(firstDetailed[0].level, "warn")
        let f1 = fields(from: firstDetailed[0].message)
        XCTAssertEqual(f1["_event"], "jank_frame")
        XCTAssertEqual(f1["route"], "kuikly_home")
        XCTAssertEqual(f1["operation"], "bottom_tab_home")
        XCTAssertEqual(f1["durationMs"], "85")
        XCTAssertEqual(f1["totalJank"], "2")
        XCTAssertEqual(f1["longestFrameMs"], "85")
        XCTAssertEqual(f1["suppressed"], "0")

        // Two more detailed janks inside the 0.5s cooldown are suppressed from individual warn spam.
        t += 0.080
        XCTAssertTrue(engine.recordFrame(sample(timestamp: t, budget: hz60, maxFps: 60)).isEmpty)
        t += 0.090
        XCTAssertTrue(engine.recordFrame(sample(timestamp: t, budget: hz60, maxFps: 60)).isEmpty)
        XCTAssertEqual(engine.totalJankFrames, 4)

        // A frozen frame (>= 700ms) is ALWAYS emitted immediately even if near prior jank.
        t += 0.780
        let frozenRecords = engine.recordFrame(sample(timestamp: t, budget: hz60, maxFps: 60))
        XCTAssertEqual(frozenRecords.count, 1)
        XCTAssertEqual(frozenRecords[0].level, "warn")
        let ff = fields(from: frozenRecords[0].message)
        XCTAssertEqual(ff["_event"], "frozen_frame")
        XCTAssertEqual(ff["durationMs"], "780")
        XCTAssertEqual(ff["totalJank"], "5")
        XCTAssertEqual(ff["totalFrozen"], "1")
        XCTAssertEqual(ff["longestFrameMs"], "780")

        // Next detailed jank after cooldown carries suppressed=2 from the earlier burst.
        t += 0.075
        let afterCooldown = engine.recordFrame(sample(timestamp: t, budget: hz60, maxFps: 60))
        XCTAssertEqual(afterCooldown.count, 1)
        let f2 = fields(from: afterCooldown[0].message)
        XCTAssertEqual(f2["_event"], "jank_frame")
        XCTAssertEqual(f2["suppressed"], "2")
        XCTAssertEqual(f2["totalJank"], "6")
        XCTAssertEqual(f2["totalFrozen"], "1")

        // Pause summary reflects all 6 janks, 1 frozen frame, and suppressedJankLogs=2.
        let summary = engine.pause(reason: "resign_active", now: t + 0.01)
        let sf = fields(from: summary?.message ?? "")
        XCTAssertEqual(summary?.level, "warn")
        XCTAssertEqual(sf["_event"], "summary")
        XCTAssertEqual(sf["frames"], "6")
        XCTAssertEqual(sf["jankFrames"], "6")
        XCTAssertEqual(sf["frozenFrames"], "1")
        XCTAssertEqual(sf["longestFrameMs"], "780")
        XCTAssertEqual(sf["sessionLongestFrameMs"], "780")
        XCTAssertEqual(sf["suppressedJankLogs"], "2")
    }

    func testStaleTimestampDelayedCallbackIsDetectedWithoutDoubleCountingNextFrame() {
        let engine = RaftIosDisplayLinkJankEngine()
        let hz60 = 1.0 / 60.0

        _ = engine.recordFrame(sample(timestamp: 400.0, budget: hz60, now: 400.001, maxFps: 60))

        // Main thread blocked 95ms, but CoreAnimation delivered the older scheduled vsync timestamp
        // (400.0167, target 400.0333) while CACurrentMediaTime() is already 400.095.
        let stalled = engine.recordFrame(
            sample(timestamp: 400.0167, budget: hz60, now: 400.095, maxFps: 60)
        )
        XCTAssertEqual(stalled.count, 1)
        let sf = fields(from: stalled[0].message)
        XCTAssertEqual(sf["_event"], "jank_frame")
        XCTAssertEqual(sf["durationMs"], "95")
        XCTAssertEqual(engine.totalJankFrames, 1)

        // Next vsync at 400.1117 (~16.7ms after the 400.095 anchor) is smooth — zero double-counting.
        let next = engine.recordFrame(
            sample(timestamp: 400.1117, budget: hz60, now: 400.112, maxFps: 60)
        )
        XCTAssertTrue(next.isEmpty)
        XCTAssertEqual(engine.totalJankFrames, 1)
    }

    func testRollingSummaryWindowEmitsWarnAtJankIntervalAndInfoHeartbeatAtSmoothInterval() {
        let config = RaftIosDisplayLinkJankConfig(
            jankSummaryWindowSeconds: 0.5,
            smoothSummaryWindowSeconds: 1.0
        )
        let engine = RaftIosDisplayLinkJankEngine(config: config)
        let hz60 = 1.0 / 60.0

        var t = 500.0
        _ = engine.recordFrame(sample(timestamp: t, budget: hz60, maxFps: 60))

        // Smooth frames past the 0.5s jank interval must NOT emit until the 1.0s smooth heartbeat.
        var emittedSmooth: [RaftIosDisplayLinkJankLogRecord] = []
        for frameIndex in 1...65 {
            t += hz60
            let records = engine.recordFrame(sample(timestamp: t, budget: hz60, maxFps: 60))
            if frameIndex < 55 {
                XCTAssertTrue(records.isEmpty, "smooth window must not emit early at frame \(frameIndex)")
            }
            emittedSmooth.append(contentsOf: records)
        }
        XCTAssertEqual(emittedSmooth.count, 1)
        XCTAssertEqual(emittedSmooth[0].level, "info")
        let smoothFields = fields(from: emittedSmooth[0].message)
        XCTAssertEqual(smoothFields["_event"], "summary")
        XCTAssertEqual(smoothFields["reason"], "window")
        XCTAssertEqual(smoothFields["jankFrames"], "0")
        XCTAssertEqual(smoothFields["p95FrameMs"], "17")

        // Next window has a minor jank frame -> emits warn summary once 0.5s has elapsed.
        t += 0.036
        XCTAssertTrue(engine.recordFrame(sample(timestamp: t, budget: hz60, maxFps: 60)).isEmpty)
        var emittedJank: [RaftIosDisplayLinkJankLogRecord] = []
        for _ in 0..<35 {
            t += hz60
            let records = engine.recordFrame(sample(timestamp: t, budget: hz60, maxFps: 60))
            emittedJank.append(contentsOf: records)
            if !emittedJank.isEmpty { break }
        }
        XCTAssertEqual(emittedJank.count, 1)
        XCTAssertEqual(emittedJank[0].level, "warn")
        let jankFields = fields(from: emittedJank[0].message)
        XCTAssertEqual(jankFields["_event"], "summary")
        XCTAssertEqual(jankFields["reason"], "window")
        XCTAssertEqual(jankFields["jankFrames"], "1")
        XCTAssertEqual(jankFields["jank"], "1")
    }

    func testFrozenFrameBypassesDetailedJankCooldown() {
        let config = RaftIosDisplayLinkJankConfig(minDetailedJankLogIntervalSeconds: 5.0)
        let engine = RaftIosDisplayLinkJankEngine(config: config)
        let hz60 = 1.0 / 60.0

        var t = 550.0
        _ = engine.recordFrame(sample(timestamp: t, budget: hz60, maxFps: 60))

        t += 0.080
        let firstJank = engine.recordFrame(sample(timestamp: t, budget: hz60, maxFps: 60))
        XCTAssertEqual(firstJank.count, 1)
        XCTAssertEqual(fields(from: firstJank[0].message)["_event"], "jank_frame")

        // 0.75s later (< 5.0s jank cooldown), a frozen frame must still emit immediately.
        t += 0.750
        let frozen = engine.recordFrame(sample(timestamp: t, budget: hz60, maxFps: 60))
        XCTAssertEqual(frozen.count, 1)
        XCTAssertEqual(fields(from: frozen[0].message)["_event"], "frozen_frame")
        XCTAssertEqual(fields(from: frozen[0].message)["durationMs"], "750")
    }

    func testRouteChangeFlushesOutgoingRouteAndSanitizesIdentifiers() {
        let engine = RaftIosDisplayLinkJankEngine()
        let hz60 = 1.0 / 60.0

        // Initial "unknown" -> first real route does not flush an artificial "unknown" summary.
        _ = engine.recordFrame(sample(timestamp: 600.0, budget: hz60, now: 600.0, maxFps: 60))
        _ = engine.recordFrame(sample(timestamp: 600.0167, budget: hz60, now: 600.0167, maxFps: 60))
        XCTAssertNil(engine.updateContext(route: "compose_slock_app_shell", operation: "page_appear", now: 600.02))
        XCTAssertEqual(engine.route, "compose_slock_app_shell")
        XCTAssertEqual(engine.operation, "page_appear")

        // Jank on app shell, then navigate to compose_thread -> flushes app shell summary first.
        _ = engine.recordFrame(sample(timestamp: 600.0567, budget: hz60, now: 600.0567, maxFps: 60))
        let flushed = engine.updateContext(route: "compose_thread", operation: "page_appear", now: 600.10)
        XCTAssertNotNil(flushed)
        XCTAssertEqual(flushed?.level, "warn")
        let ff = fields(from: flushed?.message ?? "")
        XCTAssertEqual(ff["_event"], "summary")
        XCTAssertEqual(ff["reason"], "route_change")
        XCTAssertEqual(ff["route"], "compose_slock_app_shell")
        XCTAssertEqual(ff["jankFrames"], "1")
        XCTAssertEqual(engine.route, "compose_thread")

        // Sanitizer strips spaces/newlines and falls back on blank strings.
        XCTAssertEqual(
            RaftIosDisplayLinkJankEngine.sanitizeIdentifier(" tab switch\nbad=val ", fallback: "idle"),
            "tab_switch_bad_val"
        )
        XCTAssertEqual(
            RaftIosDisplayLinkJankEngine.sanitizeIdentifier("   \n\t  ", fallback: "unknown"),
            "unknown"
        )
    }

    func testLifecyclePausesInBackgroundNeverStartsInBackgroundAndAvoidsRetainCycles() {
        let center = NotificationCenter()
        let driver = FakeDisplayLinkDriver()
        var currentState: UIApplication.State = .background
        var currentNow = 700.0
        var logs: [RaftIosDisplayLinkJankLogRecord] = []

        var tracker: RaftIosDisplayLinkJankTracker? = RaftIosDisplayLinkJankTracker(
            notificationCenter: center,
            applicationState: { currentState },
            nowProvider: { currentNow },
            maxFpsProvider: { 120 },
            driver: driver,
            logSink: { level, tag, message in
                logs.append(RaftIosDisplayLinkJankLogRecord(level: level, tag: tag, message: message))
            }
        )
        weak var weakTracker = tracker

        // Installing while backgrounded (e.g. silent push wake) must NOT start CADisplayLink.
        tracker?.install()
        tracker?.install() // Idempotent.
        XCTAssertFalse(driver.isRunning)
        XCTAssertEqual(driver.startCount, 0)
        XCTAssertEqual(logs.count, 1)
        XCTAssertEqual(fields(from: logs[0].message)["_event"], "displaylink_enabled")
        XCTAssertEqual(fields(from: logs[0].message)["maxFps"], "120")

        // Entering foreground starts CADisplayLink; subsequent didBecomeActive is an idempotent no-op.
        currentState = .active
        center.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        XCTAssertTrue(driver.isRunning)
        XCTAssertEqual(driver.startCount, 1)

        // Feed anchor + one frame before didBecomeActive; didBecomeActive must not reset/drop frames.
        tracker?.notePerformanceScene("kuikly_thread", action: "enter")
        driver.emit(sample(timestamp: 700.0, budget: 1.0 / 120.0, now: 700.0, maxFps: 120))
        driver.emit(sample(timestamp: 700.00833, budget: 1.0 / 120.0, now: 700.00833, maxFps: 120))
        center.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        XCTAssertEqual(driver.startCount, 1)

        // Resign active stops CADisplayLink and flushes summary; didEnterBackground does not double-flush.
        currentNow = 700.05
        center.post(name: UIApplication.willResignActiveNotification, object: nil)
        XCTAssertFalse(driver.isRunning)
        XCTAssertEqual(driver.stopCount, 1)
        XCTAssertEqual(logs.count, 2)
        let resignSummary = fields(from: logs[1].message)
        XCTAssertEqual(resignSummary["_event"], "summary")
        XCTAssertEqual(resignSummary["reason"], "resign_active")
        XCTAssertEqual(resignSummary["route"], "kuikly_thread")
        XCTAssertEqual(resignSummary["operation"], "scene_enter")
        XCTAssertEqual(resignSummary["frames"], "1")

        center.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        XCTAssertFalse(driver.isRunning)
        XCTAssertEqual(logs.count, 2, "second pause after empty window must not duplicate summary")

        // Releasing tracker tears down observers and display link with zero retain cycle.
        tracker = nil
        XCTAssertNil(weakTracker, "tracker must deallocate cleanly with no retain cycle")
        center.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        XCTAssertFalse(driver.isRunning)
    }

    func testActiveInstallStartsImmediatelyAndPerformanceContextHooksPreserveRoute() {
        let center = NotificationCenter()
        let driver = FakeDisplayLinkDriver()
        var currentNow = 800.0
        var logs: [RaftIosDisplayLinkJankLogRecord] = []

        let tracker = RaftIosDisplayLinkJankTracker(
            notificationCenter: center,
            applicationState: { .active },
            nowProvider: { currentNow },
            maxFpsProvider: { 60 },
            driver: driver,
            logSink: { level, tag, message in
                logs.append(RaftIosDisplayLinkJankLogRecord(level: level, tag: tag, message: message))
            }
        )

        tracker.install()
        XCTAssertTrue(driver.isRunning, "installing while active starts CADisplayLink immediately")
        XCTAssertEqual(driver.startCount, 1)

        tracker.noteRoute("compose_thread", operation: "page_appear")
        // Empty route on performanceMarker preserves active route while updating operation.
        tracker.notePerformanceMarker(route: "", phase: "composer_send")
        driver.emit(sample(timestamp: 800.0, budget: 1.0 / 60.0, now: 800.0, maxFps: 60))
        driver.emit(sample(timestamp: 800.080, budget: 1.0 / 60.0, now: 800.080, maxFps: 60))

        XCTAssertEqual(logs.count, 2)
        let jankFields = fields(from: logs[1].message)
        XCTAssertEqual(jankFields["_event"], "jank_frame")
        XCTAssertEqual(jankFields["route"], "compose_thread")
        XCTAssertEqual(jankFields["operation"], "composer_send")

        // Scene exit updates operation without clobbering route.
        tracker.notePerformanceScene("kuikly_thread", action: "exit")
        currentNow = 800.12
        center.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        XCTAssertEqual(logs.count, 3)
        let summaryFields = fields(from: logs[2].message)
        XCTAssertEqual(summaryFields["_event"], "summary")
        XCTAssertEqual(summaryFields["reason"], "enter_background")
        XCTAssertEqual(summaryFields["route"], "compose_thread")
        XCTAssertEqual(summaryFields["operation"], "scene_exit")

        tracker.uninstall()
        XCTAssertFalse(driver.isRunning)
    }
}
