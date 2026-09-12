import SwiftUI
import AppKit

public struct SettingsView: View {
    @ObservedObject var settings = SettingsStore.shared
    @ObservedObject var server = ServerManager.shared

    @State private var tempPort: Int = 8080
    @State private var tempConfigDir: String = ""
    @State private var tempJavaPath: String = ""
    @State private var tempJarPath: String = ""
    @State private var tempAutostart: Bool = true

    @State private var detectedJava: JavaInstallation?
    @State private var detectedJar: String?
    @State private var showingSaveAlert: Bool = false

    var onDismiss: (() -> Void)?

    public init(onDismiss: (() -> Void)? = nil) {
        self.onDismiss = onDismiss
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            // Server section
            VStack(alignment: .leading, spacing: 10) {
                Text("Server Configuration")
                    .font(.subheadline)
                    .fontWeight(.semibold)

                HStack {
                    Text("Port:")
                        .frame(width: 140, alignment: .trailing)
                    TextField("8080", value: $tempPort, format: .number.grouping(.never))
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 100)
                    Text("(Default: 8080)")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                HStack {
                    Text("Config Directory:")
                        .frame(width: 140, alignment: .trailing)
                    TextField("~/.mjdbcmcp", text: $tempConfigDir)
                        .textFieldStyle(.roundedBorder)
                    Button("Browse…") {
                        browseDirectory()
                    }
                }
                HStack {
                    Spacer().frame(width: 140)
                    Text("Holds SQLite application DB, drivers directory, and encryption key.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            Divider()

            // Runtime section
            VStack(alignment: .leading, spacing: 10) {
                Text("Java 21 & JAR Runtime")
                    .font(.subheadline)
                    .fontWeight(.semibold)

                HStack {
                    Text("Java 21 Executable:")
                        .frame(width: 140, alignment: .trailing)
                    TextField("Auto-detect", text: $tempJavaPath)
                        .textFieldStyle(.roundedBorder)
                    Button("Browse…") {
                        browseJava()
                    }
                    Button("Auto-detect") {
                        tempJavaPath = ""
                        refreshDetection()
                    }
                }
                HStack {
                    Spacer().frame(width: 140)
                    if let java = detectedJava {
                        Label(
                            java.isCompatible ? java.versionString : "Incompatible: \(java.versionString)",
                            systemImage: java.isCompatible ? "checkmark.circle.fill" : "exclamationmark.triangle.fill"
                        )
                        .font(.caption)
                        .foregroundColor(java.isCompatible ? .green : .orange)
                    } else {
                        Label("Java 21+ not found on system", systemImage: "xmark.circle.fill")
                            .font(.caption)
                            .foregroundColor(.red)
                    }
                }

                HStack {
                    Text("Application JAR:")
                        .frame(width: 140, alignment: .trailing)
                    TextField("Auto-detect", text: $tempJarPath)
                        .textFieldStyle(.roundedBorder)
                    Button("Browse…") {
                        browseJar()
                    }
                    Button("Auto-detect") {
                        tempJarPath = ""
                        refreshDetection()
                    }
                }
                HStack {
                    Spacer().frame(width: 140)
                    if let jar = detectedJar {
                        Label(URL(fileURLWithPath: jar).lastPathComponent, systemImage: "shippingbox.fill")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    } else {
                        Label("mjdbcmcp.jar not found", systemImage: "exclamationmark.triangle.fill")
                            .font(.caption)
                            .foregroundColor(.orange)
                    }
                }
            }

            Divider()

            Toggle("Launch server automatically when wrapper starts", isOn: $tempAutostart)
                .font(.subheadline)

            Spacer()

            // Buttons
            HStack {
                Button("Reset Defaults") {
                    tempPort = 8080
                    tempConfigDir = FileManager.default.homeDirectoryForCurrentUser
                        .appendingPathComponent(".mjdbcmcp").path
                    tempJavaPath = ""
                    tempJarPath = ""
                    tempAutostart = true
                    refreshDetection()
                }

                Spacer()

                Button("Cancel") {
                    onDismiss?()
                }

                Button("Save & Apply") {
                    saveSettings()
                }
                .keyboardShortcut(.defaultAction)
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(20)
        .frame(width: 580, height: 460)
        .onAppear {
            tempPort = settings.port
            tempConfigDir = settings.configDir
            tempJavaPath = settings.customJavaPath
            tempJarPath = settings.customJarPath
            tempAutostart = settings.autostartOnLaunch
            refreshDetection()
        }
    }

    private func refreshDetection() {
        detectedJava = JavaResolver.resolve(customPath: tempJavaPath.isEmpty ? nil : tempJavaPath)
        detectedJar = JarResolver.resolve(customPath: tempJarPath.isEmpty ? nil : tempJarPath)
    }

    private func browseDirectory() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.canCreateDirectories = true
        panel.allowsMultipleSelection = false
        panel.directoryURL = URL(fileURLWithPath: settings.resolvedConfigDir)

        if panel.runModal() == .OK, let url = panel.url {
            tempConfigDir = url.path
        }
    }

    private func browseJava() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.resolvesAliases = true

        if panel.runModal() == .OK, let url = panel.url {
            tempJavaPath = url.path
            refreshDetection()
        }
    }

    private func browseJar() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.allowedContentTypes = []

        if panel.runModal() == .OK, let url = panel.url {
            tempJarPath = url.path
            refreshDetection()
        }
    }

    private func saveSettings() {
        let changed = (settings.port != tempPort ||
                       settings.configDir != tempConfigDir ||
                       settings.customJavaPath != tempJavaPath ||
                       settings.customJarPath != tempJarPath)

        settings.port = tempPort
        settings.configDir = tempConfigDir
        settings.customJavaPath = tempJavaPath
        settings.customJarPath = tempJarPath
        settings.autostartOnLaunch = tempAutostart

        if changed && (server.status.isRunning || server.status == .starting) {
            server.restart()
        }
        onDismiss?()
    }
}
