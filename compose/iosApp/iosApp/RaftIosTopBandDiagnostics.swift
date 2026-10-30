import UIKit

/// Diagnostics for a thin grey line under the status bar seen on an iOS 27 device on every Raft
/// page (never in system apps, never on a CI simulator). Writes what UIKit draws around the
/// top safe-area edge into the app diagnostics log (tag `ios-topband`), so the line's owner can
/// be read from a feedback attachment.
///
/// One dump runs ~0.6s after the app becomes active, and after a tab switch or a page appear at
/// most once per 30s. Each dump walks every visible window of the scene and logs:
/// - `topband_context`: device, OS, safe area, scale, status bar frame, accessibility flags;
/// - `topband_view` / `topband_layer`: every view or non-view layer whose frame (window
///   coordinates) meets the band `safeAreaInsets.top ± 4pt` and is wider than half the window;
/// - `topband_navbar`, `topband_barbg`, `topband_tabbar`, `topband_floatingbar`,
///   `topband_scrollview`: those bars / containers wherever they are, with appearance flags
///   (iOS 26+: the scroll view's top edge effect);
/// - `topband_end`: counts, and whether the walk hit its node or line cap.
/// Read-only: nothing is changed in the hierarchy.
@objc final class RaftIosTopBandDiagnostics: NSObject {
    static let tag = "ios-topband"
    private static let throttleInterval: TimeInterval = 30
    private static let settleDelay: TimeInterval = 0.6
    private static let bandHalfHeight: CGFloat = 4
    private static let maxNodes = 4000
    private static let maxLines = 160
    private static let maxLayerDepth = 6

    private static var lastDumpAt: TimeInterval?
    private static var pending = false
    private static var activeObserver: NSObjectProtocol?

    /// Once per process (the scene delegate calls it on connect).
    static func install() {
        guard activeObserver == nil else { return }
        activeObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { _ in
            schedule(trigger: "became_active", throttled: false)
        }
    }

    static func noteTabSwitch() {
        schedule(trigger: "tab_switch", throttled: true)
    }

    @objc static func notePageAppear() {
        schedule(trigger: "page_appear", throttled: true)
    }

    private static func schedule(trigger: String, throttled: Bool) {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { schedule(trigger: trigger, throttled: throttled) }
            return
        }
        let now = ProcessInfo.processInfo.systemUptime
        if pending { return }
        if throttled, let last = lastDumpAt, now - last < throttleInterval { return }
        pending = true
        DispatchQueue.main.asyncAfter(deadline: .now() + settleDelay) {
            pending = false
            lastDumpAt = ProcessInfo.processInfo.systemUptime
            dump(trigger: trigger)
        }
    }

    // MARK: - Dump

    /// Shared by every window of one dump.
    private final class Budget {
        var nodes = 0
        var lines = 0
        var capped = false
    }

    private final class Walk {
        let windowIndex: Int
        let window: UIWindow
        let band: CGRect
        let minWidth: CGFloat
        let budget: Budget

        init(windowIndex: Int, window: UIWindow, band: CGRect, minWidth: CGFloat, budget: Budget) {
            self.windowIndex = windowIndex
            self.window = window
            self.band = band
            self.minWidth = minWidth
            self.budget = budget
        }
    }

    private static func dump(trigger: String) {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first else {
            record("topband_skip trigger=\(trigger) reason=no_scene")
            return
        }
        let windows = scene.windows.filter { !$0.isHidden }
        guard let keyWindow = windows.first(where: { $0.isKeyWindow }) ?? windows.first else {
            record("topband_skip trigger=\(trigger) reason=no_window")
            return
        }
        recordContext(trigger: trigger, scene: scene, keyWindow: keyWindow, windows: windows)
        let budget = Budget()
        for (index, window) in windows.enumerated() {
            let top = keyWindow.safeAreaInsets.top
            let band = CGRect(
                x: window.bounds.minX,
                y: top - bandHalfHeight,
                width: window.bounds.width,
                height: bandHalfHeight * 2
            )
            let walk = Walk(
                windowIndex: index,
                window: window,
                band: band,
                minWidth: window.bounds.width * 0.5,
                budget: budget
            )
            visit(window, depth: 0, hiddenAncestor: false, alphaProduct: 1, walk: walk)
        }
        record(
            "topband_end trigger=\(trigger) windows=\(windows.count) nodes=\(budget.nodes) " +
                "lines=\(budget.lines) capped=\(budget.capped)"
        )
    }

    private static func recordContext(trigger: String, scene: UIWindowScene, keyWindow: UIWindow, windows: [UIWindow]) {
        let screen = scene.screen
        let statusBar = scene.statusBarManager
        let windowList = windows.enumerated().map { index, window in
            "\(index):\(className(window))@\(fmt(window.windowLevel.rawValue))\(window.isKeyWindow ? "*" : "")"
        }.joined(separator: ",")
        let root = keyWindow.rootViewController
        let accessibility = [
            "reduceTransparency=\(UIAccessibility.isReduceTransparencyEnabled)",
            "increaseContrast=\(UIAccessibility.isDarkerSystemColorsEnabled)",
            "boldText=\(UIAccessibility.isBoldTextEnabled)",
            "reduceMotion=\(UIAccessibility.isReduceMotionEnabled)",
            "grayscale=\(UIAccessibility.isGrayscaleEnabled)",
            "invert=\(UIAccessibility.isInvertColorsEnabled)",
            "buttonShapes=\(UIAccessibility.buttonShapesEnabled)",
        ]
        let rootSafe: String = root.map { insets($0.view.safeAreaInsets) } ?? "-"
        let rootAdditional: String = root.map { insets($0.additionalSafeAreaInsets) } ?? "-"
        let statusBarFrame: String = statusBar.map { rect($0.statusBarFrame) } ?? "-"
        let traits = keyWindow.traitCollection
        var parts: [String] = []
        parts.append("topband_context trigger=\(trigger)")
        parts.append("os=\(UIDevice.current.systemVersion) model=\(machineName())")
        parts.append("idiom=\(UIDevice.current.userInterfaceIdiom.rawValue)")
        parts.append("window=\(rect(keyWindow.bounds)) safe=\(insets(keyWindow.safeAreaInsets))")
        parts.append("rootSafe=\(rootSafe) rootAdditional=\(rootAdditional)")
        parts.append("scale=\(fmt(screen.scale)) nativeScale=\(fmt(screen.nativeScale))")
        parts.append("screen=\(rect(screen.bounds)) native=\(rect(screen.nativeBounds))")
        parts.append("statusBar=\(statusBarFrame) statusBarHidden=\(statusBar?.isStatusBarHidden ?? false)")
        parts.append("statusBarStyle=\(statusBar?.statusBarStyle.rawValue ?? -1)")
        parts.append("style=\(traits.userInterfaceStyle.rawValue) override=\(keyWindow.overrideUserInterfaceStyle.rawValue)")
        parts.append("contrast=\(traits.accessibilityContrast.rawValue) sizeCategory=\(traits.preferredContentSizeCategory.rawValue)")
        parts.append(contentsOf: accessibility)
        parts.append("windowBg=\(color(keyWindow.backgroundColor, keyWindow)) windows=\(windowList)")
        record(parts.joined(separator: " "))
    }

    private static func visit(_ view: UIView, depth: Int, hiddenAncestor: Bool, alphaProduct: CGFloat, walk: Walk) {
        if walk.budget.nodes >= maxNodes {
            walk.budget.capped = true
            return
        }
        walk.budget.nodes += 1
        let effHidden = hiddenAncestor || view.isHidden
        let effAlpha = alphaProduct * view.alpha
        let frame = view.convert(view.bounds, to: walk.window)
        let name = className(view)
        let common = "w=\(walk.windowIndex) d=\(depth) cls=\(name) frame=\(rect(frame)) hidden=\(view.isHidden) " +
            "effHidden=\(effHidden) alpha=\(fmt(view.alpha)) effAlpha=\(fmt(effAlpha))"

        if inBand(frame, walk) {
            var extra = ""
            if let effectView = view as? UIVisualEffectView {
                extra = " effect=\(effectView.effect.map { className($0) } ?? "-")"
            }
            emit(
                "topband_view \(common) edge=\(edge(frame, walk)) bg=\(color(view.backgroundColor, view)) " +
                    "clips=\(view.clipsToBounds) \(layerProps(view.layer, owner: view))\(extra)",
                walk
            )
        }

        if let bar = view as? UINavigationBar {
            emit(
                "topband_navbar \(common) translucent=\(bar.isTranslucent) " +
                    "barTint=\(color(bar.barTintColor, bar)) style=\(bar.barStyle.rawValue) " +
                    "standard=\(appearance(bar.standardAppearance, bar)) " +
                    "scrollEdge=\(bar.scrollEdgeAppearance.map { appearance($0, bar) } ?? "nil") " +
                    "shadowImage=\(bar.shadowImage != nil)",
                walk
            )
        } else if let bar = view as? UITabBar {
            let scrollEdge = bar.scrollEdgeAppearance.map { appearance($0, bar) } ?? "nil"
            emit(
                "topband_tabbar \(common) translucent=\(bar.isTranslucent) " +
                    "barTint=\(color(bar.barTintColor, bar)) style=\(bar.barStyle.rawValue) " +
                    "override=\(bar.overrideUserInterfaceStyle.rawValue) " +
                    "standard=\(appearance(bar.standardAppearance, bar)) scrollEdge=\(scrollEdge) " +
                    "shadowImage=\(bar.shadowImage != nil) \(layerProps(bar.layer, owner: bar))",
                walk
            )
        } else if name.contains("BarBackground") {
            let parts = view.subviews.prefix(6).map { sub in
                "\(className(sub))[\(rect(sub.convert(sub.bounds, to: walk.window))) h=\(sub.isHidden) a=\(fmt(sub.alpha)) " +
                    "bg=\(color(sub.backgroundColor, sub))]"
            }.joined(separator: ",")
            emit("topband_barbg \(common) bg=\(color(view.backgroundColor, view)) subviews=\(parts)", walk)
        } else if name.contains("FloatingBar") {
            emit("topband_floatingbar \(common) bg=\(color(view.backgroundColor, view)) \(layerProps(view.layer, owner: view))", walk)
        }

        if let scrollView = view as? UIScrollView {
            var edgeEffect = "n/a"
            if #available(iOS 26.0, *) {
                edgeEffect = "topEdgeHidden=\(scrollView.topEdgeEffect.isHidden) topEdgeStyle=\(edgeStyle(scrollView.topEdgeEffect.style))"
            }
            emit(
                "topband_scrollview \(common) insetAdjust=\(scrollView.contentInsetAdjustmentBehavior.rawValue) " +
                    "adjustedTop=\(fmt(scrollView.adjustedContentInset.top)) offsetY=\(fmt(scrollView.contentOffset.y)) " +
                    edgeEffect,
                walk
            )
        }

        visitLayers(of: view.layer, owner: view, depth: 0, walk: walk)
        for sub in view.subviews {
            visit(sub, depth: depth + 1, hiddenAncestor: effHidden, alphaProduct: effAlpha, walk: walk)
        }
    }

    /// Layers that are not a view's own layer (those are visited with their view).
    private static func visitLayers(of layer: CALayer, owner: UIView, depth: Int, walk: Walk) {
        guard depth < maxLayerDepth, let sublayers = layer.sublayers else { return }
        for sub in sublayers where !(sub.delegate is UIView) {
            if walk.budget.nodes >= maxNodes {
                walk.budget.capped = true
                return
            }
            walk.budget.nodes += 1
            let frame = layer.convert(sub.frame, to: walk.window.layer)
            if inBand(frame, walk) {
                emit(
                    "topband_layer w=\(walk.windowIndex) owner=\(className(owner)) ld=\(depth) cls=\(className(sub)) " +
                        "name=\(sub.name ?? "-") frame=\(rect(frame)) edge=\(edge(frame, walk)) hidden=\(sub.isHidden) " +
                        "opacity=\(fmt(CGFloat(sub.opacity))) bg=\(cgColor(sub.backgroundColor)) " +
                        "\(layerProps(sub, owner: nil))",
                    walk
                )
            }
            visitLayers(of: sub, owner: owner, depth: depth + 1, walk: walk)
        }
    }

    // MARK: - Formatting

    private static func inBand(_ frame: CGRect, _ walk: Walk) -> Bool {
        frame.width > walk.minWidth && frame.intersects(walk.band)
    }

    /// Which of the frame's horizontal edges falls inside the band: a line drawn at an edge.
    private static func edge(_ frame: CGRect, _ walk: Walk) -> String {
        let top = frame.minY >= walk.band.minY && frame.minY <= walk.band.maxY
        let bottom = frame.maxY >= walk.band.minY && frame.maxY <= walk.band.maxY
        switch (top, bottom) {
        case (true, true): return "both"
        case (true, false): return "top"
        case (false, true): return "bottom"
        default: return "none"
        }
    }

    private static func layerProps(_ layer: CALayer, owner: UIView?) -> String {
        var parts = [
            "layer=\(className(layer))",
            "border=\(fmt(layer.borderWidth))/\(cgColor(layer.borderColor))",
            "shadow=\(fmt(CGFloat(layer.shadowOpacity)))/r\(fmt(layer.shadowRadius))" +
                "/o\(fmt(layer.shadowOffset.width)),\(fmt(layer.shadowOffset.height))/\(cgColor(layer.shadowColor))" +
                "/path=\(layer.shadowPath != nil)",
            "mask=\(layer.mask != nil)",
        ]
        if let filters = layer.filters, !filters.isEmpty {
            parts.append("filters=\(filters.count)")
        }
        if let compositing = layer.compositingFilter {
            parts.append("compositing=\(String(describing: compositing))")
        }
        if owner == nil, layer.contents != nil {
            parts.append("contents=true")
        }
        return parts.joined(separator: " ")
    }

    private static func appearance(_ appearance: UIBarAppearance, _ view: UIView) -> String {
        "{shadow=\(color(appearance.shadowColor, view)) shadowImage=\(appearance.shadowImage != nil) " +
            "bg=\(color(appearance.backgroundColor, view)) " +
            "effect=\(appearance.backgroundEffect.map { String(describing: $0) } ?? "nil") " +
            "bgImage=\(appearance.backgroundImage != nil)}"
    }

    @available(iOS 26.0, *)
    private static func edgeStyle(_ style: UIScrollEdgeEffect.Style) -> String {
        if style == .hard { return "hard" }
        if style == .soft { return "soft" }
        if style == .automatic { return "automatic" }
        return String(describing: style)
    }

    private static func color(_ color: UIColor?, _ view: UIView) -> String {
        guard let color = color else { return "-" }
        return cgColor(color.resolvedColor(with: view.traitCollection).cgColor)
    }

    private static func cgColor(_ color: CGColor?) -> String {
        guard let color = color else { return "-" }
        guard let srgb = CGColorSpace(name: CGColorSpace.sRGB),
              let converted = color.converted(to: srgb, intent: .defaultIntent, options: nil),
              let components = converted.components, components.count >= 4 else {
            return "?"
        }
        let bytes = components.prefix(4).map { Int(($0 * 255).rounded()).clamped(0, 255) }
        return String(format: "#%02X%02X%02X%02X", bytes[0], bytes[1], bytes[2], bytes[3])
    }

    private static func rect(_ rect: CGRect) -> String {
        "(\(fmt(rect.minX)),\(fmt(rect.minY)),\(fmt(rect.width)),\(fmt(rect.height)))"
    }

    private static func insets(_ insets: UIEdgeInsets) -> String {
        "(t\(fmt(insets.top)),l\(fmt(insets.left)),b\(fmt(insets.bottom)),r\(fmt(insets.right)))"
    }

    private static func fmt(_ value: CGFloat) -> String {
        String(format: "%.2f", Double(value))
    }

    private static func className(_ object: AnyObject) -> String {
        NSStringFromClass(type(of: object))
    }

    private static func machineName() -> String {
        var info = utsname()
        uname(&info)
        return withUnsafePointer(to: &info.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: MemoryLayout.size(ofValue: info.machine)) {
                String(cString: $0)
            }
        }
    }

    private static func emit(_ message: String, _ walk: Walk) {
        guard walk.budget.lines < maxLines else {
            walk.budget.capped = true
            return
        }
        walk.budget.lines += 1
        record(message)
    }

    private static func record(_ message: String) {
        RaftIosDiagnosticsRecord("info", tag, message)
    }
}

private extension Int {
    func clamped(_ low: Int, _ high: Int) -> Int {
        Swift.min(Swift.max(self, low), high)
    }
}
