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

    // MARK: - ScopeDB Frame Metrics Sessions (scroll, resume, nav_transition)

    private final class RecordingTraceSink {
        var scrollPayloads: [RaftIosScrollFrameTracePayload] = []
        var scrollSkippedFlushPayloads: [RaftIosScrollSkippedFlushTracePayload] = []
        var resumePayloads: [RaftIosResumeFrameTracePayload] = []
        var navTransitionPayloads: [RaftIosNavTransitionFrameTracePayload] = []

        func makeSink() -> RaftIosFrameMetricsTraceSink {
            RaftIosFrameMetricsTraceSink(
                reportScroll: { [weak self] payload in self?.scrollPayloads.append(payload) },
                reportScrollSkippedFlush: { [weak self] payload in self?.scrollSkippedFlushPayloads.append(payload) },
                reportResume: { [weak self] payload in self?.resumePayloads.append(payload) },
                reportNavTransition: { [weak self] payload in self?.navTransitionPayloads.append(payload) }
            )
        }
    }

    func testScrollSessionTracksStatsFiltersMicroTouchesAndCarriesThrottledJank() {
        let center = NotificationCenter()
        let driver = FakeDisplayLinkDriver()
        let traces = RecordingTraceSink()
        var currentNow = 1000.0
        var logs: [RaftIosDisplayLinkJankLogRecord] = []

        let tracker = RaftIosDisplayLinkJankTracker(
            notificationCenter: center,
            applicationState: { .active },
            nowProvider: { currentNow },
            maxFpsProvider: { 120 },
            deviceModelProvider: { "iPhone17,2" },
            osVersionProvider: { "18.4.1" },
            driver: driver,
            logSink: { level, tag, message in
                logs.append(RaftIosDisplayLinkJankLogRecord(level: level, tag: tag, message: message))
            },
            traceSink: traces.makeSink()
        )
        tracker.install()
        tracker.noteRoute("compose_thread", operation: "page_appear")
        // Non-allowlisted performance marker route must not overwrite the ScopeDB page name.
        tracker.notePerformanceMarker(route: "kuikly_internal_marker", phase: "thread_scroll")

        // 1) Tiny smooth micro-touch (< 5 frames, 0 jank) is dropped completely.
        let hz120 = 1.0 / 120.0
        tracker.noteScrollChanged(list: "thread", scrolling: true)
        var t = 1000.004
        for _ in 0..<4 {
            driver.emit(sample(timestamp: t, budget: hz120, now: t + 0.0004, maxFps: 120))
            t += hz120
        }
        currentNow = t
        tracker.noteScrollChanged(list: "thread", scrolling: false)
        XCTAssertTrue(traces.scrollPayloads.isEmpty, "smooth micro-touch under MinFrames must be dropped")

        // 2) Short scroll (< 5 frames) WITH opening-frame stall is never dropped.
        currentNow = 1001.000
        tracker.noteScrollChanged(list: "thread", scrolling: true)
        // First callback arrives 90ms after scroll start (stale timestamp).
        driver.emit(sample(timestamp: 1001.008, budget: hz120, now: 1001.090, maxFps: 120))
        currentNow = 1001.100
        tracker.noteScrollChanged(list: "thread", scrolling: false)

        XCTAssertEqual(traces.scrollPayloads.count, 1)
        let jankyShort = traces.scrollPayloads[0]
        XCTAssertEqual(jankyShort.page, "compose_thread")
        XCTAssertEqual(jankyShort.list, "thread")
        XCTAssertEqual(jankyShort.reason, "stopped")
        XCTAssertEqual(jankyShort.firstFrameMs, 90)
        XCTAssertEqual(jankyShort.durationMs, 100)
        XCTAssertEqual(jankyShort.frames, 1)
        XCTAssertEqual(jankyShort.jankFrames, 1)
        XCTAssertEqual(jankyShort.longestFrameMs, 90)
        XCTAssertEqual(jankyShort.p95FrameMs, 91)
        XCTAssertEqual(jankyShort.frameIntervalNanos, 8_333_333)
        XCTAssertEqual(jankyShort.skippedSessions, 0)
        XCTAssertEqual(jankyShort.deviceModel, "iPhone17,2")
        XCTAssertEqual(jankyShort.osVersion, "18.4.1")

        let firstScrollLog = logs.last { $0.message.hasPrefix("scroll_frames ") }
        XCTAssertEqual(firstScrollLog?.level, "warn")
        XCTAssertEqual(firstScrollLog?.tag, "SlockPerf")
        let firstLogFields = fields(from: firstScrollLog?.message ?? "")
        XCTAssertEqual(firstLogFields["page"], "compose_thread")
        XCTAssertEqual(firstLogFields["list"], "thread")
        XCTAssertEqual(firstLogFields["reason"], "stopped")
        XCTAssertEqual(firstLogFields["firstFrameMs"], "90")
        XCTAssertEqual(firstLogFields["skippedSessions"], "0")

        // 3) Rapid consecutive scrolls inside the 1.0s throttle window fold into carryover.
        let hz60 = 1.0 / 60.0
        currentNow = 1001.300
        tracker.noteScrollChanged(list: "inbox", scrolling: true)
        // Nested scroll on another list is ignored while "inbox" is active.
        tracker.noteScrollChanged(list: "home", scrolling: true)
        driver.emit(sample(timestamp: 1001.305, budget: hz60, now: 1001.306, maxFps: 60))
        driver.emit(sample(timestamp: 1001.350, budget: hz60, now: 1001.351, maxFps: 60)) // 45ms jank
        // Stopping non-active "home" must not close "inbox".
        tracker.noteScrollChanged(list: "home", scrolling: false)
        currentNow = 1001.400
        tracker.noteScrollChanged(list: "inbox", scrolling: false)
        XCTAssertEqual(traces.scrollPayloads.count, 1, "scroll within 1.0s throttle must be folded into carryover")

        // 4) Scroll after throttle expires (>= 1.0s since 1001.100) carries skipped stats and
        // verifies ProMotion 120Hz -> 60Hz adaptive step-down produces zero false jank.
        currentNow = 1002.200
        tracker.noteScrollChanged(list: "home", scrolling: true)
        var s = 1002.205
        driver.emit(sample(timestamp: s, budget: hz120, now: s + 0.0003, maxFps: 120)) // anchor @ 120Hz
        for _ in 0..<3 {
            s += hz120
            driver.emit(sample(timestamp: s, budget: hz120, now: s + 0.0003, maxFps: 120))
        }
        // Step down 120Hz -> 60Hz cleanly.
        s += hz60
        driver.emit(sample(timestamp: s, budget: hz60, now: s + 0.0003, maxFps: 120))
        s += hz60
        driver.emit(sample(timestamp: s, budget: hz60, now: s + 0.0003, maxFps: 120))
        currentNow = s + 0.002
        tracker.noteScrollChanged(list: "home", scrolling: false)

        XCTAssertEqual(traces.scrollPayloads.count, 2)
        let carriedPayload = traces.scrollPayloads[1]
        XCTAssertEqual(carriedPayload.list, "home")
        XCTAssertEqual(carriedPayload.frames, 5)
        XCTAssertEqual(carriedPayload.jankFrames, 0, "ProMotion 120Hz -> 60Hz ramp-down must not false-positive")
        XCTAssertEqual(carriedPayload.frameIntervalNanos, 8_333_333)
        XCTAssertEqual(carriedPayload.endFrameIntervalNanos, 16_666_667)
        XCTAssertEqual(carriedPayload.skippedSessions, 1)
        XCTAssertEqual(carriedPayload.skippedJankFrames, 1)
        XCTAssertEqual(carriedPayload.skippedLongestFrameMs, 45)
    }

    func testScrollPauseFlushesPausedSkippedJankAndDropsSmoothSkippedCarryover() {
        let center = NotificationCenter()
        let driver = FakeDisplayLinkDriver()
        let traces = RecordingTraceSink()
        var currentNow = 1100.0
        var logs: [RaftIosDisplayLinkJankLogRecord] = []

        let tracker = RaftIosDisplayLinkJankTracker(
            notificationCenter: center,
            applicationState: { .active },
            nowProvider: { currentNow },
            maxFpsProvider: { 60 },
            deviceModelProvider: { "iPhone16,1" },
            osVersionProvider: { "18.2" },
            driver: driver,
            logSink: { level, tag, message in
                logs.append(RaftIosDisplayLinkJankLogRecord(level: level, tag: tag, message: message))
            },
            traceSink: traces.makeSink()
        )
        tracker.install()
        tracker.noteRoute("compose_slock_app_shell", operation: "page_appear")

        let hz60 = 1.0 / 60.0

        // Emit an initial scroll to arm the 1.0s throttle window.
        tracker.noteScrollChanged(list: "home", scrolling: true)
        var t = 1100.005
        for _ in 0..<6 {
            driver.emit(sample(timestamp: t, budget: hz60, now: t + 0.0005, maxFps: 60))
            t += hz60
        }
        currentNow = t
        tracker.noteScrollChanged(list: "home", scrolling: false)
        XCTAssertEqual(traces.scrollPayloads.count, 1)

        // Two throttled sessions inside 1.0s: second hits worse jank (72ms on "inbox").
        currentNow = 1100.300
        tracker.noteScrollChanged(list: "home", scrolling: true)
        driver.emit(sample(timestamp: 1100.305, budget: hz60, now: 1100.306, maxFps: 60))
        driver.emit(sample(timestamp: 1100.345, budget: hz60, now: 1100.346, maxFps: 60)) // 40ms jank
        currentNow = 1100.350
        tracker.noteScrollChanged(list: "home", scrolling: false)

        currentNow = 1100.400
        tracker.noteScrollChanged(list: "inbox", scrolling: true)
        driver.emit(sample(timestamp: 1100.405, budget: hz60, now: 1100.406, maxFps: 60))
        driver.emit(sample(timestamp: 1100.477, budget: hz60, now: 1100.478, maxFps: 60)) // 72ms jank
        currentNow = 1100.480
        tracker.noteScrollChanged(list: "inbox", scrolling: false)
        XCTAssertEqual(traces.scrollPayloads.count, 1)
        XCTAssertTrue(traces.scrollSkippedFlushPayloads.isEmpty)

        // Pausing flushes the janky throttled carryover via paused_skipped.
        currentNow = 1100.500
        center.post(name: UIApplication.willResignActiveNotification, object: nil)
        XCTAssertEqual(traces.scrollSkippedFlushPayloads.count, 1)
        let flush = traces.scrollSkippedFlushPayloads[0]
        XCTAssertEqual(flush.page, "compose_slock_app_shell")
        XCTAssertEqual(flush.list, "inbox")
        XCTAssertEqual(flush.skippedSessions, 2)
        XCTAssertEqual(flush.skippedJankFrames, 2)
        XCTAssertEqual(flush.skippedLongestFrameMs, 72)
        XCTAssertEqual(flush.deviceModel, "iPhone16,1")
        XCTAssertEqual(flush.osVersion, "18.2")

        let flushLog = logs.last { $0.message.contains("reason=paused_skipped") }
        XCTAssertNotNil(flushLog)
        XCTAssertEqual(fields(from: flushLog?.message ?? "")["list"], "inbox")

        // Resume, emit one scroll, then throttle a smooth-only scroll and pause -> NO paused_skipped flush.
        currentNow = 1105.000
        center.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        tracker.noteScrollChanged(list: "home", scrolling: true)
        t = 1105.005
        for _ in 0..<6 {
            driver.emit(sample(timestamp: t, budget: hz60, now: t + 0.0005, maxFps: 60))
            t += hz60
        }
        currentNow = t
        tracker.noteScrollChanged(list: "home", scrolling: false)
        XCTAssertEqual(traces.scrollPayloads.count, 2)

        currentNow = 1105.300
        tracker.noteScrollChanged(list: "home", scrolling: true)
        t = 1105.305
        for _ in 0..<6 {
            driver.emit(sample(timestamp: t, budget: hz60, now: t + 0.0005, maxFps: 60))
            t += hz60
        }
        currentNow = t
        tracker.noteScrollChanged(list: "home", scrolling: false)

        currentNow = 1105.500
        center.post(name: UIApplication.willResignActiveNotification, object: nil)
        XCTAssertEqual(traces.scrollSkippedFlushPayloads.count, 1, "smooth-only throttled scrolls must not emit paused_skipped")
    }

    func testResumeSessionMeasuresBackgroundWindowFirstFrameAndIgnoresNonBackgroundReturn() {
        let center = NotificationCenter()
        let driver = FakeDisplayLinkDriver()
        let traces = RecordingTraceSink()
        var currentNow = 2000.0
        var logs: [RaftIosDisplayLinkJankLogRecord] = []

        let tracker = RaftIosDisplayLinkJankTracker(
            sessionConfig: RaftIosFrameMetricsSessionConfig(resumeWindowSeconds: 3.0),
            notificationCenter: center,
            applicationState: { .active },
            nowProvider: { currentNow },
            maxFpsProvider: { 60 },
            deviceModelProvider: { "iPhone17,2" },
            osVersionProvider: { "18.4" },
            driver: driver,
            logSink: { level, tag, message in
                logs.append(RaftIosDisplayLinkJankLogRecord(level: level, tag: tag, message: message))
            },
            traceSink: traces.makeSink()
        )
        tracker.install()
        tracker.noteRoute("compose_thread", operation: "page_appear")

        // Inactive -> active blip WITHOUT didEnterBackground must NOT arm a resume window.
        currentNow = 2001.0
        center.post(name: UIApplication.willResignActiveNotification, object: nil)
        currentNow = 2002.0
        center.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        driver.emit(sample(timestamp: 2002.016, budget: 1.0 / 60.0, now: 2002.016, maxFps: 60))
        driver.emit(sample(timestamp: 2006.000, budget: 1.0 / 60.0, now: 2006.000, maxFps: 60))
        XCTAssertTrue(traces.resumePayloads.isEmpty, "resign_active -> didBecomeActive without background must not emit resume trace")

        // Genuine background -> foreground -> active return.
        currentNow = 2010.000
        center.post(name: UIApplication.willResignActiveNotification, object: nil)
        currentNow = 2010.050
        center.post(name: UIApplication.didEnterBackgroundNotification, object: nil)

        currentNow = 2022.550 // 12,500ms in background
        center.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        currentNow = 2022.630 // 80ms startToResumeMs
        center.post(name: UIApplication.didBecomeActiveNotification, object: nil)

        let hz60 = 1.0 / 60.0
        // Frame 1: 24ms after active (smooth at 60Hz: 24ms < 2 * 16.67ms).
        driver.emit(sample(timestamp: 2022.654, budget: hz60, now: 2022.654, maxFps: 60))
        // Frame 2: 120ms stall (>= 2 * 16.67ms -> jankFrames=1, blockedMs ~= 120 - 16.67 = 103ms).
        driver.emit(sample(timestamp: 2022.774, budget: hz60, now: 2022.774, maxFps: 60))
        // Smooth frames until 3.0s window elapses.
        var t = 2022.774
        while t < 2025.640 {
            t += hz60
            driver.emit(sample(timestamp: t, budget: hz60, now: t, maxFps: 60))
        }

        XCTAssertEqual(traces.resumePayloads.count, 1)
        let resume = traces.resumePayloads[0]
        XCTAssertEqual(resume.page, "compose_thread")
        XCTAssertEqual(resume.reason, "window")
        XCTAssertEqual(resume.backgroundMs, 12_500)
        XCTAssertEqual(resume.startToResumeMs, 80)
        XCTAssertEqual(resume.firstFrameMs, 24)
        XCTAssertGreaterThanOrEqual(resume.windowMs, 3_000)
        XCTAssertEqual(resume.jankFrames, 1)
        XCTAssertEqual(resume.longestFrameMs, 120)
        XCTAssertEqual(resume.blockedMs, 103)
        XCTAssertEqual(resume.frameIntervalNanos, 16_666_667)
        XCTAssertEqual(resume.deviceModel, "iPhone17,2")
        XCTAssertEqual(resume.osVersion, "18.4")

        let resumeLog = logs.last { $0.message.hasPrefix("resume_frames ") }
        XCTAssertEqual(resumeLog?.level, "warn")
        XCTAssertEqual(resumeLog?.tag, "SlockPerf")
        let rf = fields(from: resumeLog?.message ?? "")
        XCTAssertEqual(rf["page"], "compose_thread")
        XCTAssertEqual(rf["reason"], "window")
        XCTAssertEqual(rf["backgroundMs"], "12500")
        XCTAssertEqual(rf["startToResumeMs"], "80")
        XCTAssertEqual(rf["firstFrameMs"], "24")
        XCTAssertEqual(rf["blockedMs"], "103")

        // Background again, return, then pause immediately before any frame arrives -> reason=paused, firstFrameMs=-1.
        currentNow = 2030.000
        center.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        currentNow = 2035.000
        center.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        currentNow = 2035.020
        center.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        currentNow = 2035.120
        center.post(name: UIApplication.willResignActiveNotification, object: nil)

        XCTAssertEqual(traces.resumePayloads.count, 2)
        let pausedResume = traces.resumePayloads[1]
        XCTAssertEqual(pausedResume.reason, "paused")
        XCTAssertEqual(pausedResume.backgroundMs, 5_000)
        XCTAssertEqual(pausedResume.startToResumeMs, 20)
        XCTAssertEqual(pausedResume.firstFrameMs, -1)
        XCTAssertEqual(pausedResume.windowMs, 100)
        XCTAssertEqual(pausedResume.frames, 0)
    }

    func testNavTransitionTracksPushPopCancelInterruptAndTimeoutCap() {
        let center = NotificationCenter()
        let driver = FakeDisplayLinkDriver()
        let traces = RecordingTraceSink()
        var currentNow = 3000.0
        var logs: [RaftIosDisplayLinkJankLogRecord] = []

        let tracker = RaftIosDisplayLinkJankTracker(
            sessionConfig: RaftIosFrameMetricsSessionConfig(
                navTransitionStaleRequestSeconds: 1.0,
                navTransitionTimeoutSeconds: 5.0
            ),
            notificationCenter: center,
            applicationState: { .active },
            nowProvider: { currentNow },
            maxFpsProvider: { 120 },
            deviceModelProvider: { "iPhone17,2" },
            osVersionProvider: { "18.4.1" },
            driver: driver,
            logSink: { level, tag, message in
                logs.append(RaftIosDisplayLinkJankLogRecord(level: level, tag: tag, message: message))
            },
            traceSink: traces.makeSink()
        )
        tracker.install()

        let hz120 = 1.0 / 120.0
        let hz60 = 1.0 / 60.0

        // 1) Animated push with navigate request stamp captures tap-to-first-frame latency and completion.
        currentNow = 3000.000
        tracker.noteNavTransitionRequested()
        currentNow = 3000.025
        let pushToken = tracker.beginNavTransition(
            kind: "push",
            fromPage: "compose_slock_app_shell",
            toPage: "compose_thread",
            interactive: false
        )
        XCTAssertNotEqual(pushToken, 0)

        // First vsync 40ms after navigate request (anchors cadence), then 4 smooth frames + 1 42ms jank + 60Hz end.
        driver.emit(sample(timestamp: 3000.040, budget: hz120, now: 3000.041, maxFps: 120))
        var t = 3000.040
        for _ in 0..<4 {
            t += hz120
            driver.emit(sample(timestamp: t, budget: hz120, now: t + 0.0003, maxFps: 120))
        }
        t += 0.042 // 42ms jank at 120Hz
        driver.emit(sample(timestamp: t, budget: hz120, now: t + 0.0003, maxFps: 120))
        t += hz60 // ramp down to 60Hz without false jank
        driver.emit(sample(timestamp: t, budget: hz60, now: t + 0.0003, maxFps: 120))

        currentNow = 3000.380
        tracker.endNavTransition(token: pushToken, outcome: "completed")
        // Duplicate end with same token must be ignored.
        tracker.endNavTransition(token: pushToken, outcome: "cancelled")

        XCTAssertEqual(traces.navTransitionPayloads.count, 1)
        let push = traces.navTransitionPayloads[0]
        XCTAssertEqual(push.navKind, "push")
        XCTAssertEqual(push.outcome, "completed")
        XCTAssertEqual(push.fromPage, "compose_slock_app_shell")
        XCTAssertEqual(push.toPage, "compose_thread")
        XCTAssertEqual(push.firstFrameMs, 40)
        XCTAssertEqual(push.durationMs, 380)
        XCTAssertEqual(push.frames, 6)
        XCTAssertEqual(push.jankFrames, 1)
        XCTAssertEqual(push.longestFrameMs, 42)
        XCTAssertEqual(push.p95FrameMs, 43)
        XCTAssertEqual(push.frameIntervalNanos, 8_333_333)
        XCTAssertEqual(push.endFrameIntervalNanos, 16_666_667)
        XCTAssertEqual(push.deviceModel, "iPhone17,2")
        XCTAssertEqual(push.osVersion, "18.4.1")

        let navLog = logs.last { $0.message.hasPrefix("nav_transition_frames ") }
        XCTAssertEqual(navLog?.level, "warn")
        XCTAssertEqual(navLog?.tag, "SlockPerf")
        let nf = fields(from: navLog?.message ?? "")
        XCTAssertEqual(nf["kind"], "push")
        XCTAssertEqual(nf["outcome"], "completed")
        XCTAssertEqual(nf["from"], "compose_slock_app_shell")
        XCTAssertEqual(nf["to"], "compose_thread")
        XCTAssertEqual(nf["firstFrameMs"], "40")

        // 2) Interactive swipe-back pop cancelled with zero frames omits navigate stamp and records firstFrameMs=-1.
        currentNow = 3001.000
        tracker.noteNavTransitionRequested() // must be ignored for interactive gesture
        currentNow = 3001.200
        let cancelToken = tracker.beginNavTransition(
            kind: "pop",
            fromPage: "compose_thread",
            toPage: "compose_slock_app_shell",
            interactive: true
        )
        currentNow = 3001.260
        tracker.endNavTransition(token: cancelToken, outcome: "cancelled")

        XCTAssertEqual(traces.navTransitionPayloads.count, 2)
        let cancel = traces.navTransitionPayloads[1]
        XCTAssertEqual(cancel.navKind, "pop")
        XCTAssertEqual(cancel.outcome, "cancelled")
        XCTAssertEqual(cancel.fromPage, "compose_thread")
        XCTAssertEqual(cancel.toPage, "compose_slock_app_shell")
        XCTAssertEqual(cancel.firstFrameMs, -1)
        XCTAssertEqual(cancel.durationMs, 60)
        XCTAssertEqual(cancel.frames, 0)

        // 3) Overlapping transition interrupts prior one, and timeout cap finishes an unended transition.
        currentNow = 3002.000
        let tokenA = tracker.beginNavTransition(
            kind: "push",
            fromPage: "compose_slock_app_shell",
            toPage: "compose_settings_home",
            interactive: false
        )
        currentNow = 3002.100
        let tokenB = tracker.beginNavTransition(
            kind: "push",
            fromPage: "compose_settings_home",
            toPage: "compose_settings_account",
            interactive: false
        )
        XCTAssertNotEqual(tokenA, tokenB)
        XCTAssertEqual(traces.navTransitionPayloads.count, 3)
        XCTAssertEqual(traces.navTransitionPayloads[2].outcome, "interrupted")
        XCTAssertEqual(traces.navTransitionPayloads[2].toPage, "compose_settings_home")

        // Stale end for tokenA must not end tokenB.
        currentNow = 3002.150
        tracker.endNavTransition(token: tokenA, outcome: "completed")
        XCTAssertEqual(traces.navTransitionPayloads.count, 3)

        // Frame past 5.0s timeout cap closes tokenB with outcome="interrupted".
        driver.emit(sample(timestamp: 3002.116, budget: hz60, now: 3002.116, maxFps: 60))
        driver.emit(sample(timestamp: 3007.200, budget: hz60, now: 3007.200, maxFps: 60))
        XCTAssertEqual(traces.navTransitionPayloads.count, 4)
        XCTAssertEqual(traces.navTransitionPayloads[3].outcome, "interrupted")
        XCTAssertEqual(traces.navTransitionPayloads[3].toPage, "compose_settings_account")

        // 4) Resigning active mid-transition interrupts cleanly.
        currentNow = 3010.000
        let tokenC = tracker.beginNavTransition(
            kind: "pop",
            fromPage: "compose_settings_account",
            toPage: "compose_settings_home",
            interactive: false
        )
        XCTAssertNotEqual(tokenC, 0)
        currentNow = 3010.080
        center.post(name: UIApplication.willResignActiveNotification, object: nil)
        XCTAssertEqual(traces.navTransitionPayloads.count, 5)
        XCTAssertEqual(traces.navTransitionPayloads[4].outcome, "interrupted")
        XCTAssertEqual(traces.navTransitionPayloads[4].navKind, "pop")
        XCTAssertEqual(traces.navTransitionPayloads[4].durationMs, 80)
    }

    func testHardwareDeviceModelReturnsNonEmptyCodeAndNeverGenericMarketingName() {
        let model = RaftIosFrameMetricsDeviceInfo.hardwareDeviceModel()
        XCTAssertNotNil(model)
        XCTAssertFalse(model?.isEmpty ?? true)
        XCTAssertNotEqual(model, "iPhone")
        XCTAssertNotEqual(model, "iPad")
    }
}
