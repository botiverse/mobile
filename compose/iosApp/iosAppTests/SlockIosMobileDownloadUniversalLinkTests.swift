import XCTest

final class SlockIosMobileDownloadUniversalLinkTests: XCTestCase {
    func testAcceptsCanonicalProductionAndStagingDownloadURLs() {
        for host in ["app.raft.build", "raft-app-staging.botiverse.dev"] {
            XCTAssertTrue(SlockIosMobileDownloadUniversalLink.accepts(URL(string: "https://\(host)/download")))
            XCTAssertTrue(
                SlockIosMobileDownloadUniversalLink.accepts(
                    URL(string: "https://\(host.uppercased())/download?source=desktop#install")
                )
            )
        }
    }

    func testRejectsWrongSchemeHostAndNonExactPaths() {
        XCTAssertFalse(SlockIosMobileDownloadUniversalLink.accepts(nil))
        XCTAssertFalse(SlockIosMobileDownloadUniversalLink.accepts(URL(string: "http://app.raft.build/download")))
        XCTAssertFalse(SlockIosMobileDownloadUniversalLink.accepts(URL(string: "https://app.slock.ai/download")))
        XCTAssertFalse(SlockIosMobileDownloadUniversalLink.accepts(URL(string: "https://example.com/download")))
        for path in ["", "/", "/Download", "/download/", "/download/anything"] {
            XCTAssertFalse(
                SlockIosMobileDownloadUniversalLink.accepts(URL(string: "https://app.raft.build\(path)")),
                "path=\(path)"
            )
        }
    }

    func testAppEntitlementsAndSwiftUIReceiverStayWiredToCanonicalDomains() throws {
        let iosAppDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let entitlementsURL = iosAppDirectory.appendingPathComponent("iosApp/iosApp.entitlements")
        let entitlementsData = try Data(contentsOf: entitlementsURL)
        let entitlements = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: entitlementsData, format: nil) as? [String: Any]
        )
        XCTAssertEqual(
            entitlements["com.apple.developer.associated-domains"] as? [String],
            ["applinks:app.raft.build", "applinks:raft-app-staging.botiverse.dev"]
        )

        let contentView = try String(
            contentsOf: iosAppDirectory.appendingPathComponent("iosApp/ContentView.swift"),
            encoding: .utf8
        )
        XCTAssertTrue(contentView.contains(".onContinueUserActivity(NSUserActivityTypeBrowsingWeb)"))
        XCTAssertTrue(contentView.contains("SlockIosMobileDownloadUniversalLink.accepts(activity.webpageURL)"))
    }
}
