import XCTest
import shared

final class SlockIosInviteJoinLinkTests: XCTestCase {
    func testAcceptsOnlyTheProductionInvitePath() {
        XCTAssertEqual(
            InviteJoinLinkKt.parseSlockInviteJoinUrl(
                rawUrl: "https://app.raft.build/join/Z8fCNaIREN41HXqzYdg1cw"
            )?.token,
            "Z8fCNaIREN41HXqzYdg1cw"
        )
        XCTAssertEqual(
            InviteJoinLinkKt.parseSlockInviteJoinUrl(rawUrl: "https://app.raft.build/join/AB-Rt1m_9")?.token,
            "AB-Rt1m_9"
        )
    }

    func testRejectsAlternateHostsQueriesFragmentsAndExtraSegments() {
        let rejected = [
            "",
            " https://app.raft.build/join/token",
            "http://app.raft.build/join/token",
            "https://www.raft.build/join/token",
            "https://app.raft.build/join/token?next=1",
            "https://app.raft.build/join/token#fragment",
            "https://app.raft.build/join/token/extra",
            "https://app.raft.build/join/",
            "https://app.raft.build/join/a%2Fb",
            "https://app.raft.build/Join/token",
        ]
        for rawUrl in rejected {
            XCTAssertNil(InviteJoinLinkKt.parseSlockInviteJoinUrl(rawUrl: rawUrl), rawUrl)
        }
    }
}
