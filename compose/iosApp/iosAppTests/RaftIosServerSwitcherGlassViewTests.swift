import UIKit
import XCTest

final class RaftIosServerSwitcherGlassViewTests: XCTestCase {
    func testPopoverFrameClampsWithinPhoneAndWideBounds() {
        let phone = RaftIosServerSwitcherGeometry.popoverFrame(
            in: CGRect(x: 0, y: 0, width: 390, height: 844),
            safeArea: UIEdgeInsets(top: 59, left: 0, bottom: 34, right: 0),
            anchorTop: 119,
            anchorStart: 8,
            maxWidth: 480,
            rowCount: 3,
            actionCount: 2
        )
        XCTAssertEqual(phone.minX, 8)
        XCTAssertEqual(phone.minY, 119)
        XCTAssertEqual(phone.width, 374)
        XCTAssertLessThan(phone.maxY, 844 - 34)

        let wide = RaftIosServerSwitcherGeometry.popoverFrame(
            in: CGRect(x: 0, y: 0, width: 1024, height: 768),
            safeArea: UIEdgeInsets(top: 24, left: 0, bottom: 20, right: 0),
            anchorTop: 84,
            anchorStart: 8,
            maxWidth: 480,
            rowCount: 12,
            actionCount: 3
        )
        XCTAssertEqual(wide.width, 480)
        XCTAssertLessThanOrEqual(wide.maxY, 768 - 20)
    }

    func testRoundedSquareAvatarDrawsFallbackAndImage() {
        let fallback = RaftIosServerSwitcherGeometry.roundedSquareAvatar(nil, fallbackLabel: "B")
        XCTAssertEqual(fallback.size.width, 35)
        XCTAssertEqual(fallback.size.height, 35)

        let red = Int32(bitPattern: 0xFFE04040)
        let source = RaftIosServerAvatarImage.image(argb: Array(repeating: red, count: 16), width: 4, height: 4)
        let rendered = RaftIosServerSwitcherGeometry.roundedSquareAvatar(source, fallbackLabel: "R")
        XCTAssertEqual(rendered.size.width, 35)
        XCTAssertEqual(rendered.size.height, 35)
    }

    func testGlassOverlayInstallsLiquidGlassAndDispatchesCallbacksOnIos26() throws {
        guard #available(iOS 26.0, *) else {
            throw XCTSkip("Liquid Glass server switcher is iOS 26+ only")
        }
        let overlay = RaftIosServerSwitcherGlassOverlayView(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        var selected: [String] = []
        var actions: [String] = []
        overlay.onSelectServer = { selected.append($0) }
        overlay.onTapAction = { actions.append($0) }

        let state = RaftIosServerSwitcherStateSpec(
            open: true,
            rows: [
                RaftIosServerSwitcherRowSpec(
                    id: "srv-1",
                    name: "Botiverse",
                    slug: "botiverse",
                    selected: true,
                    publicPageLive: true,
                    badgeLabel: nil,
                    badgeMuted: false,
                    mutedAccessibilityLabel: "Muted",
                    avatarImage: nil,
                    avatarFallbackLabel: "B"
                ),
                RaftIosServerSwitcherRowSpec(
                    id: "srv-2",
                    name: "Engineering",
                    slug: "eng",
                    selected: false,
                    publicPageLive: false,
                    badgeLabel: "5",
                    badgeMuted: true,
                    mutedAccessibilityLabel: "Muted",
                    avatarImage: nil,
                    avatarFallbackLabel: "E"
                ),
            ],
            actions: [
                RaftIosServerSwitcherActionSpec(
                    id: "create_server",
                    label: "Switch or create server",
                    imageAssetName: "lucide-plus",
                    systemSymbol: "plus"
                ),
            ],
            emptyLabel: "No servers",
            switchPending: false,
            reorderable: true,
            anchorTopPt: 112,
            anchorStartPt: 8,
            maxWidthPt: 480
        )

        overlay.apply(state: state, animated: false)
        overlay.layoutIfNeeded()

        XCTAssertFalse(overlay.isHidden)
        XCTAssertTrue(
            overlay.glassContainerView.effect is UIGlassEffect || UIAccessibility.isReduceTransparencyEnabled,
            "Expected UIGlassEffect on iOS 26+"
        )
        XCTAssertEqual(overlay.serverTableView.numberOfRows(inSection: 0), 2)
        XCTAssertEqual(overlay.actionButtons.count, 1)

        overlay.tableView(overlay.serverTableView, didSelectRowAt: IndexPath(row: 1, section: 0))
        XCTAssertEqual(selected, ["srv-2"])

        overlay.actionButtons[0].sendActions(for: .touchUpInside)
        XCTAssertEqual(actions, ["create_server"])

        // Pending switch locks server row selection.
        let pendingState = RaftIosServerSwitcherStateSpec(
            open: true,
            rows: state.rows,
            actions: state.actions,
            emptyLabel: state.emptyLabel,
            switchPending: true,
            reorderable: false,
            anchorTopPt: 112,
            anchorStartPt: 8,
            maxWidthPt: 480
        )
        overlay.apply(state: pendingState, animated: false)
        overlay.tableView(overlay.serverTableView, didSelectRowAt: IndexPath(row: 0, section: 0))
        XCTAssertEqual(selected, ["srv-2"])

        overlay.apply(state: .closed, animated: false)
        XCTAssertTrue(overlay.isHidden)
    }
}
