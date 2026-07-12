// swift-tools-version:5.9
// PATCHED (Birdo): upstream shipped 5.3 while using .iOS(.v15)/.macOS(.v12),
// which require PackageDescription >= 5.5. That has made this package
// unconsumable via SwiftPM since WireGuard/wireguard-apple@901fe1c (Feb 2023);
// the official WireGuard app builds these sources as Xcode targets, so upstream
// never hit it. This is the ONLY change to the manifest.
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "WireGuardKit",
    platforms: [
        .macOS(.v12),
        .iOS(.v15)
    ],
    products: [
        .library(name: "WireGuardKit", targets: ["WireGuardKit"])
    ],
    dependencies: [],
    targets: [
        .target(
            name: "WireGuardKit",
            dependencies: ["WireGuardKitGo", "WireGuardKitC"]
        ),
        .target(
            name: "WireGuardKitC",
            dependencies: [],
            publicHeadersPath: "."
        ),
        .target(
            name: "WireGuardKitGo",
            dependencies: [],
            exclude: [
                "goruntime-boottime-over-monotonic.diff",
                "go.mod",
                "go.sum",
                "api-apple.go",
                "Makefile"
            ],
            publicHeadersPath: ".",
            linkerSettings: [.linkedLibrary("wg-go")]
        )
    ]
)
