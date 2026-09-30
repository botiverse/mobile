import Foundation
import XCTest

final class SlockIosPermalinkIntakeTests: XCTestCase {
    private let url = URL(string: "https://app.raft.build/s/test/channel/c?thread=c%3Ap&msg=r")!

    func testParserKeepsChannelAndReplyIdentitiesSeparateWithoutInventingAnchor() {
        let target = SlockIosPermalinkIdentity.parse(url)
        XCTAssertEqual(target?.channelId, "c")
        XCTAssertEqual(target?.parentMessageId, "p")
        XCTAssertEqual(target?.messageId, "r")
        for suffix in ["", "?thread=c%3Ap"] {
            let noMessage = SlockIosPermalinkIdentity.parse(URL(string: "https://app.raft.build/s/test/channel/c" + suffix)!)
            XCTAssertNotNil(noMessage)
            XCTAssertNil(noMessage?.messageId)
        }
    }

    func testParserRejectsUnapprovedOriginsAndAmbiguousIdentity() {
        let invalid = [
            "http://app.raft.build/s/test/channel/c?msg=m",
            "https://app.raft.build.evil.test/s/test/channel/c?msg=m",
            "https://user@app.raft.build/s/test/channel/c?msg=m",
            "https://app.raft.build:444/s/test/channel/c?msg=m",
            "https://app.raft.build/s/test/channel/c?thread=other%3Ap&msg=r",
            "https://app.raft.build/s/test/channel/c?msg=m&msg=r",
            "https://app.raft.build/s/test/channel/c?msg=",
            "https://app.raft.build/s/test/channel/c?thread=",
            "https://app.raft.build/s/test/channel/c%2Fother?msg=m",
            "https://app.raft.build/s/test/channel/c/extra?msg=m",
            "raft://oauth/callback?code=test",
        ]
        for raw in invalid { XCTAssertNil(SlockIosPermalinkIdentity.parse(URL(string: raw)!), raw) }
    }

    func testColdUnboundWaitsAndBindsOnceAfterLogin() {
        let recorder = Recorder()
        let queue = makeQueue(recorder)
        XCTAssertTrue(queue.receive(url, activationId: "first"))
        queue.setReady(scope: "scene-a/root-1", authGeneration: nil, isReady: true)
        XCTAssertTrue(recorder.sent.isEmpty)
        queue.setReady(scope: "scene-a/root-1", authGeneration: "user-a:1", isReady: true)
        XCTAssertEqual(recorder.sent.count, 1)
        XCTAssertEqual(recorder.sent[0]["activationId"] as? String, "first")
        XCTAssertEqual(queue.pending.first?.authGeneration, "user-a:1")
    }

    func testWrongSceneAndOldRootCannotDispatchOrAcknowledge() {
        let recorder = Recorder()
        let queue = makeQueue(recorder)
        queue.receive(url, activationId: "first")
        queue.setReady(scope: "scene-b/root-1", authGeneration: "u:1", isReady: true)
        XCTAssertTrue(recorder.sent.isEmpty)
        queue.replaceReceiver(shellScopeId: "scene-a/root-2")
        queue.setReady(scope: "scene-a/root-1", authGeneration: "u:1", isReady: true)
        XCTAssertTrue(recorder.sent.isEmpty)
        queue.setReady(scope: "scene-a/root-2", authGeneration: "u:1", isReady: true)
        queue.acknowledge(activationId: "first", scope: "scene-a/root-1", authGeneration: "u:1", status: .firstTime)
        XCTAssertEqual(queue.pending.count, 1)
        queue.acknowledge(activationId: "first", scope: "scene-a/root-2", authGeneration: "u:1", status: .duplicate)
        XCTAssertTrue(queue.pending.isEmpty)
    }

    func testReplayKeepsIDAndNewClickCanOpenSameURLAgain() {
        let recorder = Recorder()
        let queue = makeQueue(recorder)
        queue.setReady(scope: "scene-a/root-1", authGeneration: "u:1", isReady: true)
        queue.receive(url, activationId: "first")
        queue.retry()
        XCTAssertEqual(recorder.sent.compactMap { $0["activationId"] as? String }, ["first", "first"])
        queue.acknowledge(activationId: "first", scope: "scene-a/root-1", authGeneration: "u:1", status: .firstTime)
        queue.receive(url, activationId: "second")
        XCTAssertEqual(queue.pending.map(\.activationId), ["second"])
        XCTAssertEqual(recorder.sent.last?["activationId"] as? String, "second")
    }

    func testAccountChangeTerminatesBoundPendingAndRejectsLateAck() {
        let recorder = Recorder()
        let queue = makeQueue(recorder)
        queue.setReady(scope: "scene-a/root-1", authGeneration: "a:1", isReady: true)
        queue.receive(url, activationId: "old")
        queue.setReady(scope: "scene-a/root-1", authGeneration: "b:2", isReady: true)
        XCTAssertTrue(queue.pending.isEmpty)
        XCTAssertEqual(recorder.results.last?.1, "auth_generation_stale")
        queue.receive(url, activationId: "new")
        queue.acknowledge(activationId: "new", scope: "scene-a/root-1", authGeneration: "a:1", status: .firstTime)
        XCTAssertEqual(queue.pending.count, 1)
        queue.setReady(scope: "scene-a/root-1", authGeneration: "a:1", isReady: false)
        XCTAssertTrue(queue.ready)
    }

    func testCapacityExpiryAndCancellationDoNotEvictLiveOwnerSilently() {
        let recorder = Recorder()
        var now: Int64 = 0
        let queue = SlockIosPermalinkIntake(shellScopeId: "s", capacity: 1, lifetimeMillis: 10,
            clock: { now }, dispatch: { recorder.sent.append($0) }, result: { recorder.results.append(($0, $1)) })
        XCTAssertTrue(queue.receive(url, activationId: "first"))
        XCTAssertFalse(queue.receive(url, activationId: "overflow"))
        XCTAssertEqual(recorder.results.last?.1, "queue_full")
        now = 10
        queue.retry()
        XCTAssertTrue(queue.pending.isEmpty)
        XCTAssertEqual(recorder.results.last?.1, "expired")
        queue.receive(url, activationId: "cancel")
        queue.cancel(reason: "login_cancelled")
        XCTAssertTrue(queue.pending.isEmpty)
        XCTAssertEqual(recorder.results.last?.1, "login_cancelled")
    }

    func testSynchronousAckDuringDispatchDoesNotSkipOtherPending() {
        let recorder = Recorder()
        var queue: SlockIosPermalinkIntake!
        queue = SlockIosPermalinkIntake(shellScopeId: "s", dispatch: { data in
            recorder.sent.append(data)
            queue.acknowledge(activationId: data["activationId"] as! String,
                              scope: "s", authGeneration: "u:1", status: .firstTime)
        }, result: { recorder.results.append(($0, $1)) })
        queue.receive(url, activationId: "one")
        queue.receive(url, activationId: "two")
        queue.setReady(scope: "s", authGeneration: "u:1", isReady: true)
        XCTAssertEqual(recorder.sent.count, 2)
        XCTAssertTrue(queue.pending.isEmpty)
    }

    private func makeQueue(_ recorder: Recorder) -> SlockIosPermalinkIntake {
        SlockIosPermalinkIntake(shellScopeId: "scene-a/root-1", dispatch: { recorder.sent.append($0) },
                               result: { recorder.results.append(($0, $1)) })
    }
    private final class Recorder {
        var sent: [[String: Any]] = []
        var results: [(String, String)] = []
    }
}
