import Foundation

public struct JavaInstallation: Sendable {
    public let path: String
    public let versionString: String
    public let majorVersion: Int?
    public let isCompatible: Bool // >= 21
}

public final class JavaResolver {
    public static func resolve(customPath: String? = nil) -> JavaInstallation? {
        if let custom = customPath?.trimmingCharacters(in: .whitespacesAndNewlines), !custom.isEmpty {
            let expanded = NSString(string: custom).expandingTildeInPath
            if FileManager.default.isExecutableFile(atPath: expanded),
               let info = probeJava(at: expanded) {
                return info
            }
        }

        let candidates = findCandidatePaths()
        for candidate in candidates {
            if let info = probeJava(at: candidate), info.isCompatible {
                return info
            }
        }

        // If no >= 21 candidate was found, return any found Java if present
        for candidate in candidates {
            if let info = probeJava(at: candidate) {
                return info
            }
        }

        return nil
    }

    public static func findCandidatePaths() -> [String] {
        var paths = [String]()

        // 1. Check JAVA_HOME
        if let javaHome = ProcessInfo.processInfo.environment["JAVA_HOME"], !javaHome.isEmpty {
            let p = (javaHome as NSString).appendingPathComponent("bin/java")
            paths.append(p)
        }

        // 2. Query /usr/libexec/java_home
        if let jhome = queryJavaHome(args: ["-v", "21"]) ?? queryJavaHome(args: []) {
            let p = (jhome as NSString).appendingPathComponent("bin/java")
            paths.append(p)
        }

        // 3. Known Homebrew paths
        let homebrewCandidates = [
            "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java",
            "/opt/homebrew/opt/openjdk@21/bin/java",
            "/opt/homebrew/opt/openjdk/bin/java",
            "/opt/homebrew/bin/java",
            "/usr/local/opt/openjdk@21/bin/java",
            "/usr/local/opt/openjdk/bin/java",
            "/usr/local/bin/java"
        ]
        paths.append(contentsOf: homebrewCandidates)

        // 4. Look in /Library/Java/JavaVirtualMachines/
        let jvmDir = "/Library/Java/JavaVirtualMachines"
        if let subdirs = try? FileManager.default.contentsOfDirectory(atPath: jvmDir) {
            for sub in subdirs {
                let p = "\(jvmDir)/\(sub)/Contents/Home/bin/java"
                paths.append(p)
            }
        }

        // 5. Look in user home for SDKMAN / asdf / homebrew
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        let userCandidates = [
            "\(home)/.sdkman/candidates/java/current/bin/java",
            "\(home)/.asdf/shims/java",
            "\(home)/.jenv/shims/java"
        ]
        paths.append(contentsOf: userCandidates)

        // 6. System fallback
        paths.append("/usr/bin/java")

        // Deduplicate and filter to existing executable files
        var unique = [String]()
        var seen = Set<String>()
        for p in paths {
            let expanded = NSString(string: p).expandingTildeInPath
            if !seen.contains(expanded) && FileManager.default.isExecutableFile(atPath: expanded) {
                seen.insert(expanded)
                unique.append(expanded)
            }
        }
        return unique
    }

    private static func queryJavaHome(args: [String]) -> String? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/libexec/java_home")
        process.arguments = args
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = Pipe() // suppress stderr

        do {
            try process.run()
            process.waitUntilExit()
            if process.terminationStatus == 0 {
                let data = pipe.fileHandleForReading.readDataToEndOfFile()
                if let str = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
                   !str.isEmpty {
                    return str
                }
            }
        } catch {
            return nil
        }
        return nil
    }

    public static func probeJava(at path: String) -> JavaInstallation? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = ["-version"]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe

        do {
            try process.run()
            process.waitUntilExit()
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            let output = String(data: data, encoding: .utf8) ?? ""

            // java -version output typically starts with:
            // openjdk version "21.0.12.1" or java version "21.0.2"
            let lines = output.components(separatedBy: .newlines)
            let firstLine = lines.first(where: { $0.contains("version") }) ?? lines.first ?? "Unknown version"
            let cleanFirstLine = firstLine.trimmingCharacters(in: .whitespacesAndNewlines)

            let major = parseMajorVersion(from: cleanFirstLine)
            let isCompatible = (major ?? 0) >= 21

            return JavaInstallation(
                path: path,
                versionString: cleanFirstLine,
                majorVersion: major,
                isCompatible: isCompatible
            )
        } catch {
            return nil
        }
    }

    private static func parseMajorVersion(from versionLine: String) -> Int? {
        // Look for pattern "XX." or "XX"
        guard let quoteStart = versionLine.firstIndex(of: "\"") else { return nil }
        let afterQuote = versionLine[versionLine.index(after: quoteStart)...]
        guard let quoteEnd = afterQuote.firstIndex(of: "\"") else { return nil }
        let verStr = String(afterQuote[..<quoteEnd])

        let parts = verStr.split(separator: ".")
        if let first = parts.first, let num = Int(first) {
            // Java 1.8 style
            if num == 1 && parts.count > 1, let second = Int(parts[1]) {
                return second
            }
            return num
        }
        return nil
    }
}
