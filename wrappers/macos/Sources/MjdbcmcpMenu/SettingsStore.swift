import Foundation

@MainActor
public final class SettingsStore: ObservableObject {
    public static let shared = SettingsStore()

    private enum Keys {
        static let port = "mjdbcmcp.port"
        static let configDir = "mjdbcmcp.configDir"
        static let customJavaPath = "mjdbcmcp.customJavaPath"
        static let customJarPath = "mjdbcmcp.customJarPath"
        static let autostartOnLaunch = "mjdbcmcp.autostartOnLaunch"
    }

    @Published public var port: Int {
        didSet {
            UserDefaults.standard.set(port, forKey: Keys.port)
        }
    }

    @Published public var configDir: String {
        didSet {
            UserDefaults.standard.set(configDir, forKey: Keys.configDir)
        }
    }

    @Published public var customJavaPath: String {
        didSet {
            UserDefaults.standard.set(customJavaPath, forKey: Keys.customJavaPath)
        }
    }

    @Published public var customJarPath: String {
        didSet {
            UserDefaults.standard.set(customJarPath, forKey: Keys.customJarPath)
        }
    }

    @Published public var autostartOnLaunch: Bool {
        didSet {
            UserDefaults.standard.set(autostartOnLaunch, forKey: Keys.autostartOnLaunch)
        }
    }

    public init() {
        let defaultPort = 8080
        let defaultDir = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".mjdbcmcp").path

        if UserDefaults.standard.object(forKey: Keys.port) != nil {
            self.port = UserDefaults.standard.integer(forKey: Keys.port)
        } else {
            self.port = defaultPort
        }

        self.configDir = UserDefaults.standard.string(forKey: Keys.configDir) ?? defaultDir
        self.customJavaPath = UserDefaults.standard.string(forKey: Keys.customJavaPath) ?? ""
        self.customJarPath = UserDefaults.standard.string(forKey: Keys.customJarPath) ?? ""

        if UserDefaults.standard.object(forKey: Keys.autostartOnLaunch) != nil {
            self.autostartOnLaunch = UserDefaults.standard.bool(forKey: Keys.autostartOnLaunch)
        } else {
            self.autostartOnLaunch = true
        }
    }

    public var resolvedConfigDir: String {
        NSString(string: configDir).expandingTildeInPath
    }

    public func resetToDefaults() {
        port = 8080
        configDir = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".mjdbcmcp").path
        customJavaPath = ""
        customJarPath = ""
        autostartOnLaunch = true
    }
}
