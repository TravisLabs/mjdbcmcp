import AppKit

import AppKit

@main
@MainActor
public final class AppDelegate: NSObject, NSApplicationDelegate {
    public static func main() {
        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.delegate = delegate
        app.run()
    }

    public func applicationDidFinishLaunching(_ notification: Notification) {
        // Run as an accessory app without a Dock icon
        NSApp.setActivationPolicy(.accessory)

        // Initialize status bar menu
        MenuController.shared.setup()

        // Autostart server if configured
        if SettingsStore.shared.autostartOnLaunch {
            ServerManager.shared.start()
        }
    }

    public func applicationWillTerminate(_ notification: Notification) {
        ServerManager.shared.stop()
    }
}
