import Foundation
import AppKit

public enum ServerStatus: Equatable, Sendable {
    case stopped
    case starting
    case running(port: Int)
    case stopping
    case error(String)

    public var displayText: String {
        switch self {
        case .stopped:
            return "Stopped"
        case .starting:
            return "Starting…"
        case .running(let port):
            return "Running (:\(port))"
        case .stopping:
            return "Stopping…"
        case .error(let message):
            return "Error: \(message)"
        }
    }

    public var isRunning: Bool {
        if case .running = self { return true }
        return false
    }

    public var isError: Bool {
        if case .error = self { return true }
        return false
    }
}

@MainActor
public final class ServerManager: ObservableObject {
    public static let shared = ServerManager()

    @Published public private(set) var status: ServerStatus = .stopped
    @Published public private(set) var currentJavaInfo: JavaInstallation?
    @Published public private(set) var currentJarPath: String?

    private var process: Process?
    private var healthTimer: Timer?
    private var logFileHandle: FileHandle?
    private let settings = SettingsStore.shared

    private init() {}

    public var logFilePath: String {
        let dir = settings.resolvedConfigDir
        return (dir as NSString).appendingPathComponent("server.log")
    }

    public func start() {
        guard process == nil else { return }

        // 1. Resolve Java
        guard let java = JavaResolver.resolve(customPath: settings.customJavaPath) else {
            status = .error("Java 21 or higher not found")
            return
        }
        guard java.isCompatible else {
            status = .error("Found \(java.versionString), but Java 21+ is required")
            return
        }
        self.currentJavaInfo = java

        // 2. Resolve JAR
        guard let jar = JarResolver.resolve(customPath: settings.customJarPath) else {
            status = .error("mjdbcmcp.jar not found")
            return
        }
        self.currentJarPath = jar

        status = .starting

        // 3. Ensure config directory exists
        let configDir = settings.resolvedConfigDir
        do {
            try FileManager.default.createDirectory(atPath: configDir, withIntermediateDirectories: true)
        } catch {
            status = .error("Cannot create config dir: \(error.localizedDescription)")
            return
        }

        // 4. Open / create log file
        let logPath = self.logFilePath
        if !FileManager.default.fileExists(atPath: logPath) {
            FileManager.default.createFile(atPath: logPath, contents: nil)
        }
        guard let handle = FileHandle(forWritingAtPath: logPath) else {
            status = .error("Cannot open log file for writing")
            return
        }
        _ = handle.seekToEndOfFile()
        self.logFileHandle = handle

        let timestamp = ISO8601DateFormatter().string(from: Date())
        let banner = "\n=== Starting mjdbcmcp at \(timestamp) on port \(settings.port) ===\n"
        if let data = banner.data(using: .utf8) {
            handle.write(data)
        }

        // 5. Setup Process
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: java.path)

        let port = settings.port
        proc.arguments = [
            "-jar", jar,
            "--server.port=\(port)",
            "--mjdbcmcp.mcp.allowed-origins=http://localhost:\(port),http://127.0.0.1:\(port)",
            "--mjdbcmcp.mcp.allowed-hosts=localhost:\(port),127.0.0.1:\(port)"
        ]

        var env = ProcessInfo.processInfo.environment
        env["MJDBCMCP_CONFIG_DIR"] = configDir
        proc.environment = env

        proc.standardOutput = handle
        proc.standardError = handle

        proc.terminationHandler = { [weak self] p in
            Task { @MainActor in
                self?.handleProcessTermination(exitCode: p.terminationStatus)
            }
        }

        do {
            try proc.run()
            self.process = proc
            startHealthCheck(port: port)
        } catch {
            status = .error("Failed to run Java process: \(error.localizedDescription)")
            try? handle.close()
            self.logFileHandle = nil
        }
    }

    public func stop() {
        stopHealthCheck()
        guard let proc = process, proc.isRunning else {
            process = nil
            status = .stopped
            closeLogHandle()
            return
        }

        status = .stopping
        proc.terminate() // SIGTERM for Spring Boot graceful shutdown

        // Give process up to 4 seconds to exit cleanly, then SIGKILL
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let deadline = Date().addingTimeInterval(4.0)
            while proc.isRunning && Date() < deadline {
                Thread.sleep(forTimeInterval: 0.2)
            }

            if proc.isRunning {
                kill(proc.processIdentifier, SIGKILL)
            }

            Task { @MainActor in
                self?.process = nil
                self?.status = .stopped
                self?.closeLogHandle()
            }
        }
    }

    public func restart() {
        stop()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
            self?.start()
        }
    }

    private func handleProcessTermination(exitCode: Int32) {
        stopHealthCheck()
        process = nil
        closeLogHandle()

        if case .stopping = status {
            status = .stopped
            return
        }

        if exitCode == 0 || exitCode == 130 || exitCode == 143 { // normal or SIGTERM
            status = .stopped
        } else {
            status = .error("Server exited (code \(exitCode)). Check logs.")
        }
    }

    private func startHealthCheck(port: Int) {
        stopHealthCheck()
        guard let healthUrl = URL(string: "http://127.0.0.1:\(port)/actuator/health") else { return }

        healthTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self = self, self.process != nil else { return }
                var req = URLRequest(url: healthUrl)
                req.timeoutInterval = 1.0

                do {
                    let (_, response) = try await URLSession.shared.data(for: req)
                    let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                    if code == 200 {
                        if case .starting = self.status {
                            self.status = .running(port: port)
                        }
                    }
                } catch {
                    // Still starting or server not responding yet
                    if case .running = self.status {
                        // If it was running but failed health check once, could be busy
                    }
                }
            }
        }
    }

    private func stopHealthCheck() {
        healthTimer?.invalidate()
        healthTimer = nil
    }

    private func closeLogHandle() {
        try? logFileHandle?.synchronize()
        try? logFileHandle?.close()
        logFileHandle = nil
    }
}
