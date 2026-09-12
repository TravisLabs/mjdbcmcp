import Foundation

public final class JarResolver {
    public static func resolve(customPath: String? = nil) -> String? {
        // 1. Explicit path in settings
        if let custom = customPath?.trimmingCharacters(in: .whitespacesAndNewlines), !custom.isEmpty {
            let expanded = NSString(string: custom).expandingTildeInPath
            if FileManager.default.fileExists(atPath: expanded) {
                return expanded
            }
        }

        // 2. In app bundle resources
        if let bundleResources = Bundle.main.resourcePath {
            let candidate = (bundleResources as NSString).appendingPathComponent("mjdbcmcp.jar")
            if FileManager.default.fileExists(atPath: candidate) {
                return candidate
            }
            // Check for pattern mjdbcmcp-*.jar in resources
            if let files = try? FileManager.default.contentsOfDirectory(atPath: bundleResources) {
                if let jar = files.first(where: { $0.hasPrefix("mjdbcmcp") && $0.hasSuffix(".jar") && !$0.contains("-plain") }) {
                    return (bundleResources as NSString).appendingPathComponent(jar)
                }
            }
        }

        // 3. Look relative to executable path or working directory
        let searchBases = [
            Bundle.main.bundlePath,
            CommandLine.arguments.first.flatMap { URL(fileURLWithPath: $0).deletingLastPathComponent().path } ?? "",
            FileManager.default.currentDirectoryPath
        ].filter { !$0.isEmpty }

        for base in searchBases {
            var current = URL(fileURLWithPath: base)
            for _ in 0..<6 {
                let buildLibs = current.appendingPathComponent("build/libs")
                if let files = try? FileManager.default.contentsOfDirectory(atPath: buildLibs.path) {
                    if let jar = files.first(where: { $0.hasPrefix("mjdbcmcp") && $0.hasSuffix(".jar") && !$0.contains("-plain") }) {
                        return buildLibs.appendingPathComponent(jar).path
                    }
                }
                current = current.deletingLastPathComponent()
            }
        }

        return nil
    }
}
