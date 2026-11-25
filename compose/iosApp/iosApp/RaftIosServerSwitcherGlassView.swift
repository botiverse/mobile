import UIKit

/// Pure-UIKit value specs for the iOS 26+ native Liquid Glass Home server switcher
/// (#proj-mobile:#369). Kept free of `import shared` so both `iosApp` and `iosAppTests` compile it.
struct RaftIosServerSwitcherRowSpec: Equatable {
    let id: String
    let name: String
    let slug: String?
    let selected: Bool
    let publicPageLive: Bool
    let badgeLabel: String?
    let badgeMuted: Bool
    let mutedAccessibilityLabel: String
    let avatarImage: UIImage?
    let avatarFallbackLabel: String
}

struct RaftIosServerSwitcherActionSpec: Equatable {
    let id: String
    let label: String
    let imageAssetName: String
    let systemSymbol: String
}

struct RaftIosServerSwitcherStateSpec: Equatable {
    let open: Bool
    let rows: [RaftIosServerSwitcherRowSpec]
    let actions: [RaftIosServerSwitcherActionSpec]
    let emptyLabel: String
    let switchPending: Bool
    let reorderable: Bool
    let anchorTopPt: CGFloat
    let anchorStartPt: CGFloat
    let maxWidthPt: CGFloat

    static let closed = RaftIosServerSwitcherStateSpec(
        open: false,
        rows: [],
        actions: [],
        emptyLabel: "",
        switchPending: false,
        reorderable: false,
        anchorTopPt: 0,
        anchorStartPt: 8,
        maxWidthPt: 480
    )
}

enum RaftIosServerSwitcherGeometry {
    static let rowHeight: CGFloat = 48
    static let actionRowHeight: CGFloat = 44
    static let emptyStateHeight: CGFloat = 48
    static let maxVisibleRows: Int = 6
    static let panelPadding: CGFloat = 6
    static let separatorHeight: CGFloat = 1
    static let separatorVerticalMargin: CGFloat = 4
    static let minWidth: CGFloat = 276
    static let cornerRadius: CGFloat = 22
    static let bottomClearance: CGFloat = 16

    static func listHeight(rowCount: Int, availableHeight: CGFloat) -> CGFloat {
        guard rowCount > 0 else { return emptyStateHeight }
        let natural = CGFloat(min(rowCount, maxVisibleRows)) * rowHeight
        return max(rowHeight, min(natural, max(rowHeight, availableHeight)))
    }

    static func popoverFrame(
        in bounds: CGRect,
        safeArea: UIEdgeInsets,
        anchorTop: CGFloat,
        anchorStart: CGFloat,
        maxWidth: CGFloat,
        rowCount: Int,
        actionCount: Int
    ) -> CGRect {
        let leading = max(safeArea.left + 8, anchorStart)
        let trailingMargin = max(safeArea.right + 8, 8)
        let availableWidth = max(0, bounds.width - leading - trailingMargin)
        let clampedMaxWidth = max(minWidth, maxWidth)
        let width: CGFloat
        if availableWidth < minWidth {
            width = max(160, availableWidth)
        } else {
            width = min(availableWidth, clampedMaxWidth)
        }
        let top = max(safeArea.top + 8, anchorTop)
        let footerHeight: CGFloat =
            actionCount > 0
                ? (separatorVerticalMargin * 2 + separatorHeight + CGFloat(actionCount) * actionRowHeight)
                : 0
        let maxPanelHeight = max(120, bounds.height - top - safeArea.bottom - bottomClearance)
        let availableForList = max(rowHeight, maxPanelHeight - panelPadding * 2 - footerHeight)
        let rowsHeight = listHeight(rowCount: rowCount, availableHeight: availableForList)
        let height = min(maxPanelHeight, panelPadding * 2 + rowsHeight + footerHeight)
        return CGRect(x: leading, y: top, width: width, height: height).integral
    }

    /// Rounded-square sticker avatar matching Raft's server switcher tile (`ServerSwitcherAvatarTile`).
    static func roundedSquareAvatar(
        _ source: UIImage?,
        fallbackLabel: String,
        size: CGFloat = 35,
        cornerRadius: CGFloat = 7
    ) -> UIImage {
        let canvas = CGSize(width: size, height: size)
        let rect = CGRect(origin: .zero, size: canvas)
        return UIGraphicsImageRenderer(size: canvas).image { _ in
            let clipPath = UIBezierPath(roundedRect: rect, cornerRadius: cornerRadius)
            clipPath.addClip()
            if let source, source.size.width > 0, source.size.height > 0 {
                let scale = max(size / source.size.width, size / source.size.height)
                let drawn = CGSize(width: source.size.width * scale, height: source.size.height * scale)
                source.draw(in: CGRect(
                    x: (size - drawn.width) / 2,
                    y: (size - drawn.height) / 2,
                    width: drawn.width,
                    height: drawn.height
                ))
            } else {
                RaftIosServerAvatarImage.ink.setFill()
                UIRectFill(rect)
                let glyph = (fallbackLabel.isEmpty ? "?" : String(fallbackLabel.prefix(1)).uppercased()) as NSString
                let font = UIFont(name: "SpaceMono-Bold", size: (size * 0.44).rounded())
                    ?? UIFont.monospacedSystemFont(ofSize: (size * 0.44).rounded(), weight: .bold)
                let attrs: [NSAttributedString.Key: Any] = [
                    .font: font,
                    .foregroundColor: RaftIosServerAvatarImage.brandYellow,
                ]
                let textSize = glyph.size(withAttributes: attrs)
                glyph.draw(
                    at: CGPoint(x: (size - textSize.width) / 2, y: (size - textSize.height) / 2),
                    withAttributes: attrs
                )
            }
            RaftIosServerAvatarImage.ink.withAlphaComponent(0.28).setStroke()
            clipPath.lineWidth = 1
            clipPath.stroke()
        }.withRenderingMode(.alwaysOriginal)
    }

    static func iconImage(assetName: String, systemSymbol: String, pointSize: CGFloat = 16) -> UIImage? {
        if let asset = UIImage(named: assetName) {
            return asset.withRenderingMode(.alwaysTemplate)
        }
        let config = UIImage.SymbolConfiguration(pointSize: pointSize, weight: .medium)
        return UIImage(systemName: systemSymbol, withConfiguration: config)?.withRenderingMode(.alwaysTemplate)
    }
}

/// Native iOS 26+ Liquid Glass Home server switcher popover (#proj-mobile:#369).
///
/// Hosted directly above the Compose Metal view inside `RaftPageViewController.view` so
/// `UIGlassEffect` refracts the live Home list underneath with zero Metal punch-hole.
@available(iOS 26.0, *)
final class RaftIosServerSwitcherGlassOverlayView: UIView,
    UITableViewDataSource,
    UITableViewDelegate,
    UITableViewDragDelegate,
    UITableViewDropDelegate,
    UIGestureRecognizerDelegate
{
    var onSelectServer: ((String) -> Void)?
    var onTapAction: ((String) -> Void)?
    var onReorderServers: (([String]) -> Void)?
    var onDismiss: (() -> Void)?

    private(set) var currentState: RaftIosServerSwitcherStateSpec = .closed
    private var orderedRows: [RaftIosServerSwitcherRowSpec] = []
    private var presentedVisible = false

    let glassContainerView: UIVisualEffectView
    let serverTableView: UITableView
    private let emptyLabelView = UILabel()
    private let separatorView = UIView()
    private let actionsStackView = UIStackView()
    private(set) var actionButtons: [UIButton] = []

    private var tableHeightConstraint: NSLayoutConstraint?
    private var emptyHeightConstraint: NSLayoutConstraint?
    private var reduceTransparencyObserver: NSObjectProtocol?

    private static let cellReuseId = "RaftIosServerSwitcherRowCell"
    private static let accentPink = UIColor(red: 1.0, green: 0x2D / 255.0, blue: 0x78 / 255.0, alpha: 1.0)

    override init(frame: CGRect) {
        self.glassContainerView = UIVisualEffectView(effect: Self.makeGlassEffect())
        self.serverTableView = UITableView(frame: .zero, style: .plain)
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        isHidden = true
        accessibilityViewIsModal = true

        let dismissTap = UITapGestureRecognizer(target: self, action: #selector(handleBackdropTap))
        dismissTap.cancelsTouchesInView = false
        dismissTap.delegate = self
        addGestureRecognizer(dismissTap)

        glassContainerView.cornerConfiguration = .uniformCorners(radius: .fixed(RaftIosServerSwitcherGeometry.cornerRadius))
        glassContainerView.layer.shadowColor = UIColor.black.cgColor
        glassContainerView.layer.shadowOpacity = 0.16
        glassContainerView.layer.shadowRadius = 24
        glassContainerView.layer.shadowOffset = CGSize(width: 0, height: 10)
        addSubview(glassContainerView)

        serverTableView.backgroundColor = .clear
        serverTableView.separatorStyle = .none
        serverTableView.showsVerticalScrollIndicator = true
        serverTableView.rowHeight = RaftIosServerSwitcherGeometry.rowHeight
        serverTableView.estimatedRowHeight = RaftIosServerSwitcherGeometry.rowHeight
        serverTableView.dataSource = self
        serverTableView.delegate = self
        serverTableView.dragDelegate = self
        serverTableView.dropDelegate = self
        serverTableView.dragInteractionEnabled = false
        serverTableView.register(RaftIosServerSwitcherCell.self, forCellReuseIdentifier: Self.cellReuseId)
        serverTableView.translatesAutoresizingMaskIntoConstraints = false

        emptyLabelView.font = UIFont.preferredFont(forTextStyle: .footnote)
        emptyLabelView.textColor = .secondaryLabel
        emptyLabelView.textAlignment = .natural
        emptyLabelView.numberOfLines = 2
        emptyLabelView.isHidden = true
        emptyLabelView.translatesAutoresizingMaskIntoConstraints = false

        separatorView.backgroundColor = UIColor.separator.withAlphaComponent(0.35)
        separatorView.translatesAutoresizingMaskIntoConstraints = false

        actionsStackView.axis = .vertical
        actionsStackView.alignment = .fill
        actionsStackView.distribution = .fillEqually
        actionsStackView.spacing = 0
        actionsStackView.translatesAutoresizingMaskIntoConstraints = false

        let contentStack = UIStackView(arrangedSubviews: [serverTableView, emptyLabelView, separatorView, actionsStackView])
        contentStack.axis = .vertical
        contentStack.alignment = .fill
        contentStack.spacing = 0
        contentStack.setCustomSpacing(RaftIosServerSwitcherGeometry.separatorVerticalMargin, after: serverTableView)
        contentStack.setCustomSpacing(RaftIosServerSwitcherGeometry.separatorVerticalMargin, after: emptyLabelView)
        contentStack.setCustomSpacing(RaftIosServerSwitcherGeometry.separatorVerticalMargin, after: separatorView)
        contentStack.translatesAutoresizingMaskIntoConstraints = false

        let inset = RaftIosServerSwitcherGeometry.panelPadding
        glassContainerView.contentView.addSubview(contentStack)
        let tableHeight = serverTableView.heightAnchor.constraint(equalToConstant: RaftIosServerSwitcherGeometry.rowHeight)
        tableHeight.priority = UILayoutPriority(999)
        let emptyHeight = emptyLabelView.heightAnchor.constraint(equalToConstant: RaftIosServerSwitcherGeometry.emptyStateHeight)
        emptyHeight.priority = UILayoutPriority(999)
        self.tableHeightConstraint = tableHeight
        self.emptyHeightConstraint = emptyHeight

        NSLayoutConstraint.activate([
            contentStack.topAnchor.constraint(equalTo: glassContainerView.contentView.topAnchor, constant: inset),
            contentStack.leadingAnchor.constraint(equalTo: glassContainerView.contentView.leadingAnchor, constant: inset),
            contentStack.trailingAnchor.constraint(equalTo: glassContainerView.contentView.trailingAnchor, constant: -inset),
            contentStack.bottomAnchor.constraint(lessThanOrEqualTo: glassContainerView.contentView.bottomAnchor, constant: -inset),
            tableHeight,
            emptyHeight,
            separatorView.heightAnchor.constraint(equalToConstant: RaftIosServerSwitcherGeometry.separatorHeight),
        ])

        reduceTransparencyObserver = NotificationCenter.default.addObserver(
            forName: UIAccessibility.reduceTransparencyStatusDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.glassContainerView.effect = Self.makeGlassEffect()
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    deinit {
        if let observer = reduceTransparencyObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    private static func makeGlassEffect() -> UIVisualEffect {
        if UIAccessibility.isReduceTransparencyEnabled {
            return UIBlurEffect(style: .systemChromeMaterial)
        }
        let glass = UIGlassEffect(style: .regular)
        glass.isInteractive = true
        return glass
    }

    override var keyCommands: [UIKeyCommand]? {
        [
            UIKeyCommand(
                input: UIKeyCommand.inputEscape,
                modifierFlags: [],
                action: #selector(handleEscapeKey)
            )
        ]
    }

    override var canBecomeFirstResponder: Bool { true }

    override func layoutSubviews() {
        super.layoutSubviews()
        if currentState.open {
            positionGlassPopover()
        }
    }

    func apply(state: RaftIosServerSwitcherStateSpec, animated: Bool = true) {
        let wasOpen = currentState.open
        currentState = state
        orderedRows = state.rows

        if !state.open {
            dismissPopover(animated: animated && wasOpen)
            return
        }

        let hasRows = !orderedRows.isEmpty
        serverTableView.isHidden = !hasRows
        emptyLabelView.isHidden = hasRows
        emptyLabelView.text = state.emptyLabel
        serverTableView.alpha = state.switchPending ? 0.6 : 1.0
        serverTableView.isUserInteractionEnabled = !state.switchPending
        serverTableView.dragInteractionEnabled = state.reorderable
        serverTableView.isScrollEnabled = orderedRows.count > RaftIosServerSwitcherGeometry.maxVisibleRows
        serverTableView.reloadData()

        rebuildActionButtons(state.actions)
        separatorView.isHidden = state.actions.isEmpty
        actionsStackView.isHidden = state.actions.isEmpty

        isHidden = false
        positionGlassPopover()

        if !presentedVisible {
            presentedVisible = true
            animatePresent(animated: animated)
        }
    }

    private func positionGlassPopover() {
        let targetFrame = RaftIosServerSwitcherGeometry.popoverFrame(
            in: bounds,
            safeArea: safeAreaInsets,
            anchorTop: currentState.anchorTopPt,
            anchorStart: currentState.anchorStartPt,
            maxWidth: currentState.maxWidthPt,
            rowCount: orderedRows.count,
            actionCount: currentState.actions.count
        )
        let footerHeight: CGFloat =
            currentState.actions.isEmpty
                ? 0
                : (RaftIosServerSwitcherGeometry.separatorVerticalMargin * 2
                    + RaftIosServerSwitcherGeometry.separatorHeight
                    + CGFloat(currentState.actions.count) * RaftIosServerSwitcherGeometry.actionRowHeight)
        let availableListHeight = max(
            RaftIosServerSwitcherGeometry.rowHeight,
            targetFrame.height - RaftIosServerSwitcherGeometry.panelPadding * 2 - footerHeight
        )
        tableHeightConstraint?.constant = RaftIosServerSwitcherGeometry.listHeight(
            rowCount: orderedRows.count,
            availableHeight: availableListHeight
        )
        let savedTransform = glassContainerView.transform
        glassContainerView.transform = .identity
        glassContainerView.layer.anchorPoint = CGPoint(x: 0.12, y: 0.0)
        glassContainerView.frame = targetFrame
        glassContainerView.transform = savedTransform
    }

    private func animatePresent(animated: Bool) {
        glassContainerView.layer.removeAllAnimations()
        guard animated else {
            glassContainerView.alpha = 1
            glassContainerView.transform = .identity
            return
        }
        let reduceMotion = UIAccessibility.isReduceMotionEnabled
        glassContainerView.alpha = 0
        glassContainerView.transform = reduceMotion ? .identity : CGAffineTransform(scaleX: 0.88, y: 0.88)
        UIView.animate(
            withDuration: reduceMotion ? 0.18 : 0.34,
            delay: 0,
            usingSpringWithDamping: reduceMotion ? 1.0 : 0.84,
            initialSpringVelocity: 0.4,
            options: [.allowUserInteraction, .beginFromCurrentState]
        ) {
            self.glassContainerView.alpha = 1
            self.glassContainerView.transform = .identity
        }
    }

    private func dismissPopover(animated: Bool) {
        guard presentedVisible || !isHidden else { return }
        presentedVisible = false
        glassContainerView.layer.removeAllAnimations()
        guard animated else {
            isHidden = true
            glassContainerView.alpha = 1
            glassContainerView.transform = .identity
            return
        }
        let reduceMotion = UIAccessibility.isReduceMotionEnabled
        UIView.animate(
            withDuration: 0.18,
            delay: 0,
            options: [.curveEaseIn, .beginFromCurrentState]
        ) {
            self.glassContainerView.alpha = 0
            if !reduceMotion {
                self.glassContainerView.transform = CGAffineTransform(scaleX: 0.90, y: 0.90)
            }
        } completion: { _ in
            if !self.currentState.open {
                self.isHidden = true
                self.glassContainerView.alpha = 1
                self.glassContainerView.transform = .identity
            }
        }
    }

    private func rebuildActionButtons(_ actions: [RaftIosServerSwitcherActionSpec]) {
        for view in actionsStackView.arrangedSubviews {
            actionsStackView.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
        actionButtons = actions.map { action in
            let button = UIButton(type: .system)
            var config = UIButton.Configuration.plain()
            config.title = action.label
            config.image = RaftIosServerSwitcherGeometry.iconImage(
                assetName: action.imageAssetName,
                systemSymbol: action.systemSymbol,
                pointSize: 16
            )
            config.imagePadding = 10
            config.baseForegroundColor = .label
            config.contentInsets = NSDirectionalEdgeInsets(top: 10, leading: 12, bottom: 10, trailing: 12)
            config.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
                var attrs = incoming
                attrs.font = UIFont(name: "SpaceGrotesk-Medium", size: 15)
                    ?? UIFont.systemFont(ofSize: 15, weight: .medium)
                return attrs
            }
            button.configuration = config
            button.contentHorizontalAlignment = .leading
            button.accessibilityIdentifier = "server-switcher-action-\(action.id)"
            button.heightAnchor.constraint(equalToConstant: RaftIosServerSwitcherGeometry.actionRowHeight).isActive = true
            button.addAction(
                UIAction { [weak self] _ in
                    self?.onTapAction?(action.id)
                },
                for: .touchUpInside
            )
            actionsStackView.addArrangedSubview(button)
            return button
        }
    }

    @objc private func handleBackdropTap() {
        onDismiss?()
    }

    @objc private func handleEscapeKey() {
        onDismiss?()
    }

    // MARK: - UIGestureRecognizerDelegate

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        let point = touch.location(in: self)
        return !glassContainerView.frame.contains(point)
    }

    // MARK: - UITableViewDataSource

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        orderedRows.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        guard let cell = tableView.dequeueReusableCell(withIdentifier: Self.cellReuseId, for: indexPath) as? RaftIosServerSwitcherCell else {
            return UITableViewCell()
        }
        let row = orderedRows[indexPath.row]
        cell.configure(row: row, showDragHandle: currentState.reorderable, accentPink: Self.accentPink)
        return cell
    }

    // MARK: - UITableViewDelegate

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard !currentState.switchPending, indexPath.row < orderedRows.count else { return }
        onSelectServer?(orderedRows[indexPath.row].id)
    }

    // MARK: - UITableViewDragDelegate & UITableViewDropDelegate

    func tableView(_ tableView: UITableView, itemsForBeginning session: UIDragSession, at indexPath: IndexPath) -> [UIDragItem] {
        guard currentState.reorderable, indexPath.row < orderedRows.count else { return [] }
        let row = orderedRows[indexPath.row]
        let item = UIDragItem(itemProvider: NSItemProvider(object: row.id as NSString))
        item.localObject = row.id
        return [item]
    }

    func tableView(_ tableView: UITableView, dropSessionDidUpdate session: UIDropSession, withDestinationIndexPath destinationIndexPath: IndexPath?) -> UITableViewDropProposal {
        guard currentState.reorderable, tableView.hasActiveDrag else {
            return UITableViewDropProposal(operation: .forbidden)
        }
        return UITableViewDropProposal(operation: .move, intent: .insertAtDestinationIndexPath)
    }

    func tableView(_ tableView: UITableView, performDropWith coordinator: UITableViewDropCoordinator) {
        guard currentState.reorderable,
              let item = coordinator.items.first,
              let sourceIndexPath = item.sourceIndexPath
        else { return }
        let destRow = min(coordinator.destinationIndexPath?.row ?? max(0, orderedRows.count - 1), max(0, orderedRows.count - 1))
        guard sourceIndexPath.row != destRow, sourceIndexPath.row < orderedRows.count else { return }
        let moved = orderedRows.remove(at: sourceIndexPath.row)
        orderedRows.insert(moved, at: destRow)
        tableView.moveRow(at: sourceIndexPath, to: IndexPath(row: destRow, section: 0))
        onReorderServers?(orderedRows.map(\.id))
    }
}

@available(iOS 26.0, *)
private final class RaftIosServerSwitcherCell: UITableViewCell {
    private let avatarView = UIImageView()
    private let titleLabel = UILabel()
    private let publicGlobeView = UIImageView()
    private let slugLabel = UILabel()
    private let badgeLabel = UILabel()
    private let checkView = UIImageView()
    private let gripView = UIImageView()
    private let highlightBackdrop = UIView()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        contentView.backgroundColor = .clear
        selectionStyle = .none

        highlightBackdrop.layer.cornerRadius = 12
        highlightBackdrop.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(highlightBackdrop)

        avatarView.contentMode = .scaleAspectFill
        avatarView.translatesAutoresizingMaskIntoConstraints = false

        titleLabel.lineBreakMode = .byTruncatingTail
        titleLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        publicGlobeView.image = RaftIosServerSwitcherGeometry.iconImage(assetName: "lucide-globe", systemSymbol: "globe", pointSize: 12)
        publicGlobeView.tintColor = .secondaryLabel
        publicGlobeView.contentMode = .scaleAspectFit
        publicGlobeView.translatesAutoresizingMaskIntoConstraints = false

        slugLabel.font = UIFont(name: "SpaceMono-Regular", size: 11)
            ?? UIFont.monospacedSystemFont(ofSize: 11, weight: .regular)
        slugLabel.textColor = .secondaryLabel
        slugLabel.lineBreakMode = .byTruncatingTail

        let titleLine = UIStackView(arrangedSubviews: [titleLabel, publicGlobeView])
        titleLine.axis = .horizontal
        titleLine.alignment = .center
        titleLine.spacing = 4

        let textColumn = UIStackView(arrangedSubviews: [titleLine, slugLabel])
        textColumn.axis = .vertical
        textColumn.alignment = .leading
        textColumn.spacing = 1
        textColumn.translatesAutoresizingMaskIntoConstraints = false

        badgeLabel.font = UIFont(name: "SpaceMono-Bold", size: 11)
            ?? UIFont.monospacedSystemFont(ofSize: 11, weight: .bold)
        badgeLabel.textAlignment = .center
        badgeLabel.layer.cornerRadius = 9
        badgeLabel.clipsToBounds = true
        badgeLabel.setContentHuggingPriority(.required, for: .horizontal)
        badgeLabel.setContentCompressionResistancePriority(.required, for: .horizontal)
        badgeLabel.translatesAutoresizingMaskIntoConstraints = false

        checkView.image = RaftIosServerSwitcherGeometry.iconImage(assetName: "lucide-check", systemSymbol: "checkmark", pointSize: 15)
        checkView.tintColor = .label
        checkView.contentMode = .scaleAspectFit
        checkView.translatesAutoresizingMaskIntoConstraints = false

        gripView.image = RaftIosServerSwitcherGeometry.iconImage(assetName: "lucide-grip-vertical", systemSymbol: "line.3.horizontal", pointSize: 14)
        gripView.tintColor = .tertiaryLabel
        gripView.contentMode = .scaleAspectFit
        gripView.translatesAutoresizingMaskIntoConstraints = false

        let rowStack = UIStackView(arrangedSubviews: [avatarView, textColumn, badgeLabel, checkView, gripView])
        rowStack.axis = .horizontal
        rowStack.alignment = .center
        rowStack.spacing = 10
        rowStack.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(rowStack)

        NSLayoutConstraint.activate([
            highlightBackdrop.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 2),
            highlightBackdrop.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -2),
            highlightBackdrop.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 2),
            highlightBackdrop.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -2),

            rowStack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 10),
            rowStack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -8),
            rowStack.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),

            avatarView.widthAnchor.constraint(equalToConstant: 35),
            avatarView.heightAnchor.constraint(equalToConstant: 35),
            publicGlobeView.widthAnchor.constraint(equalToConstant: 13),
            publicGlobeView.heightAnchor.constraint(equalToConstant: 13),
            badgeLabel.heightAnchor.constraint(equalToConstant: 18),
            badgeLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 22),
            checkView.widthAnchor.constraint(equalToConstant: 16),
            checkView.heightAnchor.constraint(equalToConstant: 16),
            gripView.widthAnchor.constraint(equalToConstant: 16),
            gripView.heightAnchor.constraint(equalToConstant: 16),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func setHighlighted(_ highlighted: Bool, animated: Bool) {
        super.setHighlighted(highlighted, animated: animated)
        highlightBackdrop.backgroundColor = highlighted
            ? UIColor.label.withAlphaComponent(0.10)
            : (checkView.isHidden ? .clear : UIColor.label.withAlphaComponent(0.06))
    }

    func configure(row: RaftIosServerSwitcherRowSpec, showDragHandle: Bool, accentPink: UIColor) {
        avatarView.image = RaftIosServerSwitcherGeometry.roundedSquareAvatar(
            row.avatarImage,
            fallbackLabel: row.avatarFallbackLabel
        )
        titleLabel.text = row.name
        titleLabel.textColor = .label
        titleLabel.font = row.selected
            ? (UIFont(name: "SpaceGrotesk-Bold", size: 15) ?? UIFont.systemFont(ofSize: 15, weight: .bold))
            : (UIFont(name: "SpaceGrotesk-Medium", size: 15) ?? UIFont.systemFont(ofSize: 15, weight: .medium))

        publicGlobeView.isHidden = !row.publicPageLive
        if let slug = row.slug, !slug.isEmpty {
            slugLabel.text = "/\(slug)"
            slugLabel.isHidden = false
        } else {
            slugLabel.text = nil
            slugLabel.isHidden = true
        }

        if let badge = row.badgeLabel, !badge.isEmpty {
            badgeLabel.text = " \(badge) "
            badgeLabel.isHidden = false
            if row.badgeMuted {
                badgeLabel.backgroundColor = UIColor.secondaryLabel.withAlphaComponent(0.22)
                badgeLabel.textColor = .secondaryLabel
            } else {
                badgeLabel.backgroundColor = accentPink
                badgeLabel.textColor = .white
            }
        } else {
            badgeLabel.text = nil
            badgeLabel.isHidden = true
        }

        checkView.isHidden = !row.selected
        gripView.isHidden = !showDragHandle
        highlightBackdrop.backgroundColor = row.selected ? UIColor.label.withAlphaComponent(0.06) : .clear

        var parts = [row.name]
        if let badge = row.badgeLabel, !badge.isEmpty {
            parts.append(row.badgeMuted ? "\(badge), \(row.mutedAccessibilityLabel)" : badge)
        }
        accessibilityLabel = parts.joined(separator: ", ")
        accessibilityTraits = row.selected ? [.button, .selected] : [.button]
        accessibilityIdentifier = "server-switcher-row-\(row.id)"
    }
}
