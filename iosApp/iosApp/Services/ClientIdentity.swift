import Foundation

/// How this build names itself to the Birdo API: the `User-Agent` product
/// token and the `X-Desktop-Client` value.
///
/// One target builds both the iOS app and the Mac App Store app, and until
/// owner item 96 (2026-10-01) BOTH sent `Birdo-iOS/<version> (iOS)` and
/// `X-Desktop-Client: birdo-ios`, so every Mac was counted, version-floored and
/// supported as an iPhone. The device BODY was fixed earlier (audit D-18:
/// DESKTOP / MACOS); this is the header half.
///
/// What the backend does with these (birdo-web, read 2026-10-01):
///   * `X-Desktop-Client` — its PRESENCE gates the JSON-token login routes and
///     the CSRF exemption, and selects the desktop attestation scheme. Only
///     `buildClientDeviceInfo` reads the VALUE, as a fallback guess when the
///     body has no platform; this app always sends one. `birdo-macos` contains
///     neither "ios" nor "desktop", so it can never be mis-guessed as either.
///   * `User-Agent` — `parseClientUserAgent` (version floor + telemetry). A
///     token it does not know parses to null, which every caller treats as
///     "unknown, allow": an older backend fails OPEN on `Birdo-macOS`, never
///     closed.
///
/// Foundation-only and compiled into the app, the PacketTunnel extension
/// (heartbeats send the same headers) and the unit-test bundle, so the two
/// request sites cannot drift and the strings are pinned by a test.
enum ClientIdentity {
    #if os(macOS)
    /// Product token after `Birdo-` and inside the trailing comment.
    static let platformToken = "macOS"
    /// `X-Desktop-Client` value.
    static let clientHeaderValue = "birdo-macos"
    #else
    static let platformToken = "iOS"
    static let clientHeaderValue = "birdo-ios"
    #endif

    /// `Birdo-iOS/1.4.32 (iOS)` on iOS, `Birdo-macOS/1.4.32 (macOS)` on a Mac.
    static func userAgent(version: String) -> String {
        "Birdo-\(platformToken)/\(version) (\(platformToken))"
    }
}
