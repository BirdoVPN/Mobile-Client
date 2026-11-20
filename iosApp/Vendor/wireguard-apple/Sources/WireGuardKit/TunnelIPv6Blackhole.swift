// SPDX-License-Identifier: MIT
// BIRDO LOCAL PATCH 6 (MR-953 F3). Not upstream; see iosApp/project.yml.

import Foundation

/// IPv6 on a node that gives the client no IPv6 address.
///
/// The profile's AllowedIPs always carry `::/0` (TunnelRoutes), so
/// PacketTunnelSettingsGenerator puts `::/0` into
/// `NEIPv6Settings.includedRoutes`. Upstream then built that NEIPv6Settings
/// with NO address, and nothing guarantees iOS or macOS installs a route into
/// an interface with no IPv6 address: if it does not, native IPv6 leaves on
/// the physical interface, and with the kill switch off nothing else stops it.
///
/// So the blackhole is made explicit instead of left to the OS: the tunnel
/// gets a placeholder Unique Local Address, the IPv6 default route has an
/// addressed interface to go into, and IPv6 goes into the tunnel. There it
/// dies: the node holds no IPv6 for this peer, so it drops packets from the
/// placeholder (the source is outside the peer's AllowedIPs on the server),
/// and no reply can ever come back to an address that exists nowhere else.
/// Nothing leaves outside the tunnel, and the server learns nothing it does
/// not already see on the IPv4 side. Happy Eyeballs falls back to IPv4.
///
/// The address is from a randomly chosen /48 (RFC 4193 3.2), not `fd00::/8`'s
/// first addresses, which home routers hand out on real LANs. The prefix is
/// /120 because the generator clamps every IPv6 interface address to /120
/// (the shortest prefix iOS honours, per upstream).
///
/// Foundation-only, so BirdoVPNTests compiles this file directly
/// (TunnelIPv6BlackholeTests).
enum TunnelIPv6Blackhole {
    static let placeholderAddress = "fd8e:51b7:2c4a::1"
    static let placeholderPrefixLength = 120

    /// The IPv6 interface addresses to add: the placeholder when IPv6 is routed
    /// into the tunnel but the tunnel has no IPv6 address of its own; nothing
    /// otherwise (a node that assigns IPv6 keeps exactly what it assigned).
    static func addressesToAdd(tunnelHasIPv6Address: Bool,
                               ipv6RoutedIntoTunnel: Bool) -> [(address: String, prefixLength: Int)] {
        guard ipv6RoutedIntoTunnel, !tunnelHasIPv6Address else { return [] }
        return [(placeholderAddress, placeholderPrefixLength)]
    }
}
