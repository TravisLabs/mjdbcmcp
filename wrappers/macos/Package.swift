// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MjdbcmcpMenu",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(name: "MjdbcmcpMenu", targets: ["MjdbcmcpMenu"])
    ],
    targets: [
        .executableTarget(
            name: "MjdbcmcpMenu",
            path: "Sources/MjdbcmcpMenu"
        )
    ]
)
