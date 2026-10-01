import Foundation

/// LIVE-PORT53: the WireGuard port is not a setting any more.
///
/// The relays accept WireGuard on 51820 only. Checked on all ten on
/// 2026-10-01: nothing listens on a public 53, there is no DNAT from it, and
/// ufw opens only 51820. So the "53" preset never connected, a custom port
/// never connected either, and the "51820" preset was the same as Automatic.
/// Builds up to 1.4.32 offered all four. This build offers none and dials the
/// port the server's connect response names.
///
/// An older build left two kinds of state behind, and this file handles both:
///   1. The setting itself, `wg_port` in UserDefaults. `retireStoredChoice`
///      rewrites anything but "auto" to "auto", once. Nothing reads the key to
///      build an endpoint any more; this keeps the stored state honest.
///   2. The tunnel profile in the system VPN preferences. An older build
///      baked the chosen port into its wg-quick `Endpoint` line, and iOS
///      starts that profile without asking the app (an on-demand re-dial, the
///      VPN switch in the Settings app). The PacketTunnel extension passes
///      every config it starts or reconfigures through `dialableConfig`.
///
/// Foundation-only, compiled into the app, the PacketTunnel extension and the
/// unit-test bundle (WireGuardPortTests).
///
/// Twin of Android's `AppPreferences.retireWireGuardPortChoice` and
/// `WireGuardConfigBuilder.portOverride` (LIVE-PORT53).
enum WireGuardPort {
    /// The one port the relays accept WireGuard on.
    static let relayPort = 51820

    /// The UserDefaults key the retired setting was stored under.
    static let legacyDefaultsKey = "wg_port"

    /// The only value `legacyDefaultsKey` holds after `retireStoredChoice`.
    static let automatic = "auto"

    /// The providerConfiguration entry a profile written by this build
    /// carries: its `Endpoint` port is the server's own, from the connect
    /// response, and is dialled as it is. A profile without it was written by
    /// a build that put the user's port choice into the `Endpoint` line.
    static let endpointPortSourceKey = "wg-endpoint-port"
    static let endpointPortFromServer = "server"

    /// Rewrite a saved "53", custom port or "51820" preset to "auto". Runs on
    /// every launch; once the value is "auto" (or was never set) it writes
    /// nothing.
    ///
    /// - Returns: true when it rewrote the stored value.
    @discardableResult
    static func retireStoredChoice(in defaults: UserDefaults) -> Bool {
        guard let stored = defaults.object(forKey: legacyDefaultsKey) else { return false }
        if let text = stored as? String, text == automatic { return false }
        defaults.set(automatic, forKey: legacyDefaultsKey)
        return true
    }

    /// The wg-quick config the extension may dial for a profile (or a
    /// reconfigure message) carrying `providerConfiguration`.
    ///
    /// A profile this build wrote names the server's port and is used as it
    /// is, so the server stays the authority on where its WireGuard listens.
    /// A profile an older build wrote cannot say what the server's port was,
    /// only what the user chose, so its `Endpoint` is pinned to `relayPort`:
    /// the one port every relay accepts. The app rewrites the profile on its
    /// next connect, so this applies only until then.
    static func dialableConfig(_ config: String, providerConfiguration: [String: Any]?) -> String {
        if providerConfiguration?[endpointPortSourceKey] as? String == endpointPortFromServer {
            return config
        }
        return pinEndpointPort(in: config)
    }

    /// Rewrite every `Endpoint = host:port` line whose port is not `port`.
    ///
    /// Matches the vendored wg-quick parser: key case-insensitive, anything
    /// after `#` a comment. Only a bare host or a bracketed IPv6 literal is
    /// rewritten. An unbracketed IPv6 literal has no port that can be told
    /// apart from the address (and WireGuard rejects it anyway), so it is left
    /// alone rather than turned into a different wrong address. The app has
    /// always bracketed IPv6.
    static func pinEndpointPort(in config: String, to port: Int = relayPort) -> String {
        var lines = config.components(separatedBy: "\n")
        for index in lines.indices {
            let line = lines[index]
            let content = line.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)
                .first.map { String($0) } ?? ""
            guard let equals = content.firstIndex(of: "=") else { continue }
            let key = content[..<equals].trimmingCharacters(in: .whitespacesAndNewlines)
            guard key.lowercased() == "endpoint" else { continue }
            let value = content[content.index(after: equals)...]
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard let colon = value.lastIndex(of: ":") else { continue }
            let host = value[..<colon]
            let isBracketed = host.hasPrefix("[") && host.hasSuffix("]")
            guard !host.isEmpty, isBracketed || !host.contains(":") else { continue }
            guard let current = Int(value[value.index(after: colon)...]), current != port else { continue }
            lines[index] = "Endpoint = \(host):\(port)"
        }
        return lines.joined(separator: "\n")
    }
}
