import AppKit
import Combine
import SwiftUI

@MainActor
public final class MenuController: NSObject, NSMenuDelegate {
    public static let shared = MenuController()

    private var statusItem: NSStatusItem!
    private var menu: NSMenu!

    private var statusHeaderItem: NSMenuItem!
    private var openAdminItem: NSMenuItem!
    private var copyMcpUrlItem: NSMenuItem!
    private var startItem: NSMenuItem!
    private var stopItem: NSMenuItem!
    private var restartItem: NSMenuItem!

    private var settingsWindowController: NSWindowController?
    private var cancellables = Set<AnyCancellable>()

    private let server = ServerManager.shared
    private let settings = SettingsStore.shared

    private override init() {
        super.init()
    }

    public func setup() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)

        if let button = statusItem.button {
            button.image = statusIcon(for: .stopped)
            button.imagePosition = .imageLeft
        }

        buildMenu()
        statusItem.menu = menu

        // Observe server status changes
        server.$status
            .receive(on: DispatchQueue.main)
            .sink { [weak self] newStatus in
                self?.updateStatusUI(newStatus)
            }
            .store(in: &cancellables)

        // Observe settings changes
        settings.$port
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.updateStatusUI(self?.server.status ?? .stopped)
            }
            .store(in: &cancellables)
    }

    private func buildMenu() {
        menu = NSMenu()
        menu.delegate = self

        // 1. Status header
        statusHeaderItem = NSMenuItem(title: "mjdbcmcp: Stopped", action: nil, keyEquivalent: "")
        statusHeaderItem.isEnabled = false
        menu.addItem(statusHeaderItem)

        menu.addItem(NSMenuItem.separator())

        // 2. Quick actions
        openAdminItem = NSMenuItem(title: "Open Admin Interface", action: #selector(openAdminInterface), keyEquivalent: "o")
        openAdminItem.target = self
        menu.addItem(openAdminItem)

        copyMcpUrlItem = NSMenuItem(title: "Copy MCP URL", action: #selector(copyMcpUrl), keyEquivalent: "c")
        copyMcpUrlItem.target = self
        menu.addItem(copyMcpUrlItem)

        menu.addItem(NSMenuItem.separator())

        // 3. Settings
        let settingsItem = NSMenuItem(title: "Settings…", action: #selector(openSettings), keyEquivalent: ",")
        settingsItem.target = self
        menu.addItem(settingsItem)

        menu.addItem(NSMenuItem.separator())

        // 4. Server controls
        startItem = NSMenuItem(title: "Start Server", action: #selector(startServer), keyEquivalent: "s")
        startItem.target = self
        menu.addItem(startItem)

        stopItem = NSMenuItem(title: "Stop Server", action: #selector(stopServer), keyEquivalent: "")
        stopItem.target = self
        menu.addItem(stopItem)

        restartItem = NSMenuItem(title: "Restart Server", action: #selector(restartServer), keyEquivalent: "r")
        restartItem.target = self
        menu.addItem(restartItem)

        menu.addItem(NSMenuItem.separator())

        // 5. Utilities
        let configDirItem = NSMenuItem(title: "Open Configuration Directory", action: #selector(openConfigDir), keyEquivalent: "")
        configDirItem.target = self
        menu.addItem(configDirItem)

        let logsItem = NSMenuItem(title: "View Server Logs", action: #selector(openLogs), keyEquivalent: "l")
        logsItem.target = self
        menu.addItem(logsItem)

        menu.addItem(NSMenuItem.separator())

        // 6. Quit
        let quitItem = NSMenuItem(title: "Quit mjdbcmcp", action: #selector(quitApp), keyEquivalent: "q")
        quitItem.target = self
        menu.addItem(quitItem)

        updateStatusUI(server.status)
    }

    private func updateStatusUI(_ status: ServerStatus) {
        statusHeaderItem.title = "mjdbcmcp: \(status.displayText)"

        if let button = statusItem?.button {
            button.image = statusIcon(for: status)
        }

        switch status {
        case .running:
            openAdminItem.isEnabled = true
            startItem.isHidden = true
            stopItem.isHidden = false
            restartItem.isEnabled = true
        case .starting, .stopping:
            openAdminItem.isEnabled = false
            startItem.isHidden = true
            stopItem.isHidden = false
            stopItem.isEnabled = false
            restartItem.isEnabled = false
        case .stopped, .error:
            openAdminItem.isEnabled = false
            startItem.isHidden = false
            startItem.isEnabled = true
            stopItem.isHidden = true
            restartItem.isEnabled = false
        }
    }

    private func statusIcon(for status: ServerStatus) -> NSImage {
        let symbolName: String
        switch status {
        case .running:
            symbolName = "cylinder.split.1x2.fill"
        case .starting:
            symbolName = "hourglass"
        case .stopping:
            symbolName = "cylinder.split.1x2"
        case .stopped:
            symbolName = "cylinder.split.1x2"
        case .error:
            symbolName = "exclamationmark.triangle.fill"
        }

        let config = NSImage.SymbolConfiguration(pointSize: 14, weight: .regular)
        let image = NSImage(systemSymbolName: symbolName, accessibilityDescription: "mjdbcmcp status")?
            .withSymbolConfiguration(config) ?? NSImage()
        image.isTemplate = !status.isError
        return image
    }

    // MARK: - Actions

    @objc private func openAdminInterface() {
        let urlStr = "http://localhost:\(settings.port)"
        if let url = URL(string: urlStr) {
            NSWorkspace.shared.open(url)
        }
    }

    @objc private func copyMcpUrl() {
        let urlStr = "http://localhost:\(settings.port)/mcp"
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(urlStr, forType: .string)
    }

    @objc private func openSettings() {
        if let controller = settingsWindowController {
            controller.window?.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            return
        }

        let view = SettingsView(onDismiss: { [weak self] in
            self?.settingsWindowController?.close()
            self?.settingsWindowController = nil
        })
        let hostingController = NSHostingController(rootView: view)

        let window = NSWindow(contentViewController: hostingController)
        window.title = "mjdbcmcp Settings"
        window.styleMask = [.titled, .closable, .miniaturizable]
        window.setContentSize(NSSize(width: 580, height: 460))
        window.isReleasedWhenClosed = false
        window.center()

        let controller = NSWindowController(window: window)
        self.settingsWindowController = controller

        controller.showWindow(nil)
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc private func startServer() {
        server.start()
    }

    @objc private func stopServer() {
        server.stop()
    }

    @objc private func restartServer() {
        server.restart()
    }

    @objc private func openConfigDir() {
        let dir = settings.resolvedConfigDir
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        NSWorkspace.shared.open(URL(fileURLWithPath: dir))
    }

    @objc private func openLogs() {
        let path = server.logFilePath
        if !FileManager.default.fileExists(atPath: path) {
            try? FileManager.default.createDirectory(atPath: settings.resolvedConfigDir, withIntermediateDirectories: true)
            FileManager.default.createFile(atPath: path, contents: nil)
        }
        NSWorkspace.shared.open(URL(fileURLWithPath: path))
    }

    @objc private func quitApp() {
        server.stop()
        NSApp.terminate(nil)
    }
}
