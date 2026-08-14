import AppKit
import QuartzCore

final class GlyphButton: NSButton {
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}

final class IslandView: NSView {
    var onClick: (() -> Void)?
    var onHover: ((Bool) -> Void)?

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        for area in trackingAreas { removeTrackingArea(area) }
        addTrackingArea(NSTrackingArea(
            rect: .zero,
            options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect],
            owner: self, userInfo: nil))
    }

    override func mouseEntered(with event: NSEvent) { onHover?(true) }
    override func mouseExited(with event: NSEvent) { onHover?(false) }
    override func mouseUp(with event: NSEvent) { onClick?() }
}

final class IslandPanel: NSPanel {
    private enum Mode { case pill, expanded }

    private static let ink = NSColor(srgbRed: 242 / 255, green: 242 / 255, blue: 244 / 255, alpha: 1)
    private static let accent = NSColor(srgbRed: 255 / 255, green: 159 / 255, blue: 10 / 255, alpha: 1)

    private static let hhmm: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f
    }()
    private static let longDate: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .full
        f.timeStyle = .none
        return f
    }()

    private let container = IslandView()
    private let pillLabel = NSTextField(labelWithString: "")
    private let artView = NSImageView()
    private let titleLabel = NSTextField(labelWithString: "")
    private let artistLabel = NSTextField(labelWithString: "")
    private let bigClock = NSTextField(labelWithString: "")
    private let dateLabel = NSTextField(labelWithString: "")
    private var buttons: [GlyphButton] = []

    private var mode = Mode.pill
    private var minuteTimer: Timer?
    private var collapseTimer: Timer?

    init() {
        super.init(contentRect: NSRect(x: 0, y: 0, width: 120, height: 34),
                   styleMask: [.borderless, .nonactivatingPanel],
                   backing: .buffered, defer: false)
        isOpaque = false
        backgroundColor = .clear
        hasShadow = true
        level = .statusBar
        collectionBehavior = [.canJoinAllSpaces, .stationary]
        hidesOnDeactivate = false
        isMovable = false
        isReleasedWhenClosed = false

        setupViews()
        refresh()
        apply(animated: false)
        startClock()

        NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification,
            object: nil, queue: .main) { [weak self] _ in self?.apply(animated: false) }
    }

    // MARK: - Views

    private func setupViews() {
        container.wantsLayer = true
        if let layer = container.layer {
            layer.backgroundColor = NSColor(srgbRed: 12 / 255, green: 12 / 255, blue: 14 / 255,
                                            alpha: 0.97).cgColor
            layer.cornerRadius = 26
            layer.borderWidth = 1
            layer.borderColor = NSColor.white.withAlphaComponent(0.09).cgColor
            layer.masksToBounds = true
        }
        contentView = container
        container.onClick = { [weak self] in self?.expand() }
        container.onHover = { [weak self] inside in self?.hoverChanged(inside) }

        pillLabel.alignment = .center
        pillLabel.lineBreakMode = .byTruncatingTail

        artView.wantsLayer = true
        artView.layer?.cornerRadius = 20
        artView.layer?.masksToBounds = true
        artView.imageScaling = .scaleProportionallyUpOrDown

        titleLabel.font = .boldSystemFont(ofSize: 16)
        titleLabel.textColor = Self.ink
        titleLabel.lineBreakMode = .byTruncatingTail

        artistLabel.font = .systemFont(ofSize: 13)
        artistLabel.textColor = Self.ink.withAlphaComponent(0.7)
        artistLabel.lineBreakMode = .byTruncatingTail

        bigClock.font = .monospacedDigitSystemFont(ofSize: 44, weight: .bold)
        bigClock.textColor = Self.ink
        bigClock.alignment = .center

        dateLabel.font = .systemFont(ofSize: 14)
        dateLabel.textColor = Self.ink.withAlphaComponent(0.65)
        dateLabel.alignment = .center

        for label in [pillLabel, artView, titleLabel, artistLabel, bigClock, dateLabel] as [NSView] {
            container.addSubview(label)
        }

        let controls: [(String, Selector)] = [
            ("\u{23EE}", #selector(prevTapped)),
            ("\u{23EF}", #selector(playTapped)),
            ("\u{23ED}", #selector(nextTapped)),
        ]
        for (glyph, action) in controls {
            let b = GlyphButton(title: "", target: self, action: action)
            b.isBordered = false
            b.attributedTitle = NSAttributedString(string: glyph, attributes: [
                .font: NSFont.systemFont(ofSize: 20),
                .foregroundColor: NSColor.white,
            ])
            container.addSubview(b)
            buttons.append(b)
        }
    }

    @objc private func prevTapped() { NowPlaying.shared.previousTrack() }
    @objc private func playTapped() { NowPlaying.shared.playPause() }
    @objc private func nextTapped() { NowPlaying.shared.nextTrack() }

    // MARK: - State

    func refresh() {
        let np = NowPlaying.shared
        pillLabel.attributedStringValue = pillContent()
        titleLabel.stringValue = np.track?.title ?? ""
        artistLabel.stringValue = np.track?.artist ?? ""
        artView.image = np.artwork
        bigClock.stringValue = Self.hhmm.string(from: Date())
        dateLabel.stringValue = Self.longDate.string(from: Date())
        apply(animated: mode == .pill)
    }

    private func expand() {
        guard mode == .pill else { return }
        mode = .expanded
        collapseTimer?.invalidate()
        apply(animated: true)
    }

    private func collapse() {
        guard mode == .expanded else { return }
        mode = .pill
        apply(animated: true)
    }

    private func hoverChanged(_ inside: Bool) {
        collapseTimer?.invalidate()
        guard mode == .expanded, !inside else { return }
        collapseTimer = Timer.scheduledTimer(withTimeInterval: 0.7, repeats: false) { [weak self] _ in
            self?.collapse()
        }
    }

    private func apply(animated: Bool) {
        let frame = targetFrame()
        container.layer?.cornerRadius = mode == .pill ? 26 : 34
        layoutContent(for: frame.size)
        if animated {
            NSAnimationContext.runAnimationGroup({ ctx in
                ctx.duration = 0.25
                ctx.timingFunction = CAMediaTimingFunction(name: .easeOut)
                animator().setFrame(frame, display: true)
            }, completionHandler: { [weak self] in self?.invalidateShadow() })
        } else {
            setFrame(frame, display: true)
        }
    }

    // MARK: - Layout

    private func targetFrame() -> NSRect {
        let size: NSSize
        switch mode {
        case .pill:
            let text = min(pillLabel.attributedStringValue.size().width, 320)
            size = NSSize(width: ceil(text) + 40, height: 34)
        case .expanded:
            size = NSSize(width: 400, height: 184)
        }
        guard let screen = NSScreen.main else { return NSRect(origin: .zero, size: size) }
        let vf = screen.visibleFrame
        return NSRect(x: (vf.midX - size.width / 2).rounded(),
                      y: vf.maxY - 6 - size.height,
                      width: size.width, height: size.height)
    }

    private func layoutContent(for size: NSSize) {
        let expanded = mode == .expanded
        let hasTrack = NowPlaying.shared.track != nil

        pillLabel.isHidden = expanded
        for v in [artView, titleLabel, artistLabel] as [NSView] { v.isHidden = !(expanded && hasTrack) }
        for b in buttons { b.isHidden = !(expanded && hasTrack) }
        bigClock.isHidden = !(expanded && !hasTrack)
        dateLabel.isHidden = bigClock.isHidden

        let w = size.width
        let h = size.height
        if !expanded {
            pillLabel.frame = NSRect(x: 20, y: (h - 18) / 2, width: w - 40, height: 18)
            return
        }
        if hasTrack {
            let hasArt = NowPlaying.shared.artwork != nil
            artView.isHidden = !hasArt
            artView.frame = NSRect(x: 20, y: h - 112, width: 92, height: 92)
            let tx: CGFloat = hasArt ? 124 : 20
            titleLabel.frame = NSRect(x: tx, y: h - 42, width: w - tx - 20, height: 22)
            artistLabel.frame = NSRect(x: tx, y: h - 62, width: w - tx - 20, height: 18)
            let bw: CGFloat = 44
            let gap: CGFloat = 18
            var x = (w - bw * 3 - gap * 2) / 2
            for b in buttons {
                b.frame = NSRect(x: x, y: 16, width: bw, height: 36)
                x += bw + gap
            }
        } else {
            bigClock.frame = NSRect(x: 0, y: h / 2 - 8, width: w, height: 56)
            dateLabel.frame = NSRect(x: 0, y: h / 2 - 40, width: w, height: 20)
        }
    }

    private func pillContent() -> NSAttributedString {
        let np = NowPlaying.shared
        guard np.playing, let track = np.track else {
            return NSAttributedString(string: Self.hhmm.string(from: Date()), attributes: [
                .font: NSFont.boldSystemFont(ofSize: 14),
                .foregroundColor: Self.ink,
            ])
        }
        let s = NSMutableAttributedString(string: "\u{25CF}  ", attributes: [
            .font: NSFont.systemFont(ofSize: 9, weight: .bold),
            .foregroundColor: Self.accent,
            .baselineOffset: 1.5,
        ])
        s.append(NSAttributedString(string: "\(track.artist) \u{2014} \(track.title)", attributes: [
            .font: NSFont.boldSystemFont(ofSize: 14),
            .foregroundColor: Self.ink,
        ]))
        return s
    }

    // MARK: - Clock

    private func startClock() {
        // Fire on minute boundaries; a 1 s timer would be pure waste for HH:mm.
        let now = Date()
        let delay = 60 - now.timeIntervalSince1970.truncatingRemainder(dividingBy: 60)
        let t = Timer(fire: now.addingTimeInterval(delay), interval: 60, repeats: true) { [weak self] _ in
            self?.refresh()
        }
        RunLoop.main.add(t, forMode: .common)
        minuteTimer = t
    }
}
