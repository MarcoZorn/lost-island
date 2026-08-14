import AppKit

struct Track: Equatable {
    let title: String
    let artist: String
    let artURL: String?
}

/// Polls Spotify / Music over AppleScript. No private APIs, no MediaRemote.
final class NowPlaying {
    static let shared = NowPlaying()

    private(set) var track: Track?
    private(set) var playing = false
    private(set) var artwork: NSImage?
    var onChange: (() -> Void)?

    private var player: String?
    private var timer: Timer?
    private var artCache: [String: NSImage] = [:]

    func start() {
        poll()
        let t = Timer(timeInterval: 5, repeats: true) { [weak self] _ in self?.poll() }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    func playPause() { command("playpause") }
    func nextTrack() { command("next track") }
    func previousTrack() { command("previous track") }

    private func command(_ verb: String) {
        guard let player else { return }
        _ = run("tell application \"\(player)\" to \(verb)")
        poll()
    }

    private func isRunning(_ bundleID: String) -> Bool {
        !NSRunningApplication.runningApplications(withBundleIdentifier: bundleID).isEmpty
    }

    private func poll() {
        var found: (app: String, playing: Bool, track: Track)?
        if isRunning("com.spotify.client"), let (p, t) = query("Spotify", art: true) {
            found = ("Spotify", p, t)
        }
        // Music only wins over a paused Spotify if it is actually playing.
        if found?.playing != true, isRunning("com.apple.Music"),
           let (p, t) = query("Music", art: false), p || found == nil {
            found = ("Music", p, t)
        }

        let changed = found?.track != track || (found?.playing ?? false) != playing
        player = found?.app
        playing = found?.playing ?? false
        track = found?.track
        if changed {
            updateArtwork()
            onChange?()
        }
    }

    private func query(_ app: String, art: Bool) -> (Bool, Track)? {
        let fields = "name of current track & linefeed & artist of current track"
            + (art ? " & linefeed & artwork url of current track" : "")
        let out = run("""
            tell application "\(app)"
                if player state is playing then
                    return "playing" & linefeed & \(fields)
                else if player state is paused then
                    return "paused" & linefeed & \(fields)
                else
                    return "off"
                end if
            end tell
            """)
        guard let out, out != "off" else { return nil }
        let lines = out.components(separatedBy: "\n")
        guard lines.count >= 3 else { return nil }
        let track = Track(title: lines[1], artist: lines[2],
                          artURL: lines.count > 3 ? lines[3] : nil)
        return (lines[0] == "playing", track)
    }

    private func run(_ source: String) -> String? {
        guard let script = NSAppleScript(source: source) else { return nil }
        var err: NSDictionary?
        let desc = script.executeAndReturnError(&err)
        return err == nil ? desc.stringValue : nil
    }

    private func updateArtwork() {
        guard let url = track?.artURL, !url.isEmpty else {
            artwork = nil
            return
        }
        if let hit = artCache[url] {
            artwork = hit
            return
        }
        artwork = nil
        guard let u = URL(string: url) else { return }
        URLSession.shared.dataTask(with: u) { [weak self] data, _, _ in
            guard let data, let img = NSImage(data: data) else { return }
            DispatchQueue.main.async {
                guard let self else { return }
                if self.artCache.count > 40 { self.artCache.removeAll() } // ponytail: crude cap, LRU if it ever matters
                self.artCache[url] = img
                if self.track?.artURL == url {
                    self.artwork = img
                    self.onChange?()
                }
            }
        }.resume()
    }
}
