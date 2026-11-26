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

    func testInitialContentOffsetYKeepsSelectedRowInView() {
        let rowHeight = RaftIosServerSwitcherGeometry.rowHeight
        let viewportHeight = CGFloat(RaftIosServerSwitcherGeometry.maxVisibleRows) * rowHeight

        // Top 6 rows fit in initial viewport -> offset stays 0.
        XCTAssertEqual(
            RaftIosServerSwitcherGeometry.initialContentOffsetY(
                selectedIndex: 0,
                rowCount: 15,
                viewportHeight: viewportHeight
            ),
            0
        )
        XCTAssertEqual(
            RaftIosServerSwitcherGeometry.initialContentOffsetY(
                selectedIndex: 5,
                rowCount: 15,
                viewportHeight: viewportHeight
            ),
            0
        )

        // Mid-list row below fold is centered inside viewport.
        let midOffset = RaftIosServerSwitcherGeometry.initialContentOffsetY(
            selectedIndex: 9,
            rowCount: 15,
            viewportHeight: viewportHeight
        )
        XCTAssertEqual(midOffset, (CGFloat(9) * rowHeight - (viewportHeight - rowHeight) / 2).rounded())
        let midRowTop = CGFloat(9) * rowHeight
        XCTAssertGreaterThanOrEqual(midRowTop, midOffset)
        XCTAssertLessThanOrEqual(midRowTop + rowHeight, midOffset + viewportHeight)

        // Bottom row clamps cleanly to maxOffset so the last row sits fully in view.
        let maxOffset = CGFloat(15) * rowHeight - viewportHeight
        XCTAssertEqual(
            RaftIosServerSwitcherGeometry.initialContentOffsetY(
                selectedIndex: 14,
                rowCount: 15,
                viewportHeight: viewportHeight
            ),
            maxOffset
        )

        // Short list (no scrolling needed) or nil selection stays at 0.
        XCTAssertEqual(
            RaftIosServerSwitcherGeometry.initialContentOffsetY(
                selectedIndex: 4,
                rowCount: 5,
                viewportHeight: viewportHeight
            ),
            0
        )
        XCTAssertEqual(
            RaftIosServerSwitcherGeometry.initialContentOffsetY(
                selectedIndex: nil,
                rowCount: 15,
                viewportHeight: viewportHeight
            ),
            0
        )
    }

    func testGlassOverlayScrollsSelectedServerIntoViewOnOpenAndPreservesUserScrollWhileOpenOnIos26() throws {
        guard #available(iOS 26.0, *) else {
            throw XCTSkip("Liquid Glass server switcher is iOS 26+ only")
        }
        let overlay = RaftIosServerSwitcherGlassOverlayView(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        func makeState(selectedIndex: Int, badgeOnFirst: String? = nil) -> RaftIosServerSwitcherStateSpec {
            let rows = (0..<12).map { index in
                RaftIosServerSwitcherRowSpec(
                    id: "srv-\(index)",
                    name: "Workspace \(index)",
                    slug: "workspace-\(index)",
                    selected: index == selectedIndex,
                    publicPageLive: false,
                    badgeLabel: index == 0 ? badgeOnFirst : nil,
                    badgeMuted: false,
                    mutedAccessibilityLabel: "Muted",
                    avatarImage: nil,
                    avatarFallbackLabel: "W"
                )
            }
            return RaftIosServerSwitcherStateSpec(
                open: true,
                rows: rows,
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
        }

        // Open with the last server selected -> table immediately positions row 11 in view.
        overlay.apply(state: makeState(selectedIndex: 11), animated: false)
        overlay.layoutIfNeeded()

        let viewportHeight = overlay.serverTableView.bounds.height
        let expectedBottomOffset = RaftIosServerSwitcherGeometry.initialContentOffsetY(
            selectedIndex: 11,
            rowCount: 12,
            viewportHeight: viewportHeight
        )
        XCTAssertGreaterThan(expectedBottomOffset, 0)
        XCTAssertEqual(overlay.serverTableView.contentOffset.y, expectedBottomOffset, accuracy: 1.0)

        // Manual scroll while open must not be reset by subsequent badge/avatar updates.
        overlay.serverTableView.setContentOffset(CGPoint(x: 0, y: 24), animated: false)
        overlay.apply(state: makeState(selectedIndex: 11, badgeOnFirst: "3"), animated: false)
        XCTAssertEqual(overlay.serverTableView.contentOffset.y, 24, accuracy: 1.0)

        // Closing and reopening re-positions to the currently selected server.
        overlay.apply(state: .closed, animated: false)
        overlay.apply(state: makeState(selectedIndex: 11), animated: false)
        overlay.layoutIfNeeded()
        XCTAssertEqual(overlay.serverTableView.contentOffset.y, expectedBottomOffset, accuracy: 1.0)

        // Reopening with the top server selected returns to offset 0.
        overlay.apply(state: .closed, animated: false)
        overlay.apply(state: makeState(selectedIndex: 0), animated: false)
        overlay.layoutIfNeeded()
        XCTAssertEqual(overlay.serverTableView.contentOffset.y, 0, accuracy: 1.0)
    }
}
