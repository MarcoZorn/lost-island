import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var island: IslandPanel?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let panel = IslandPanel()
        panel.orderFrontRegardless()
        island = panel
        NowPlaying.shared.onChange = { [weak panel] in panel?.refresh() }
        NowPlaying.shared.start()
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let delegate = AppDelegate()
app.delegate = delegate
app.run()
