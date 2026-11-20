import XCTest

/// MR-953 F3 — pins `TunnelIPv6Blackhole`, WireGuardKit local patch 6.
///
/// Before: on a node that gives the client no IPv6 address, the generated
/// `NEIPv6Settings` had NO address while `::/0` was in its includedRoutes, and
/// whether iOS installs a route into an interface with no IPv6 address was
/// left to the OS. Now the tunnel gets a placeholder ULA in that case.
/// TunnelRouteContractGuardTests checks the generator feeds this into the
/// NEIPv6Settings it builds; the device check (native IPv6 fails on a
/// dual-stack network with a v4-only node, kill switch off) is still owed.
final class TunnelIPv6BlackholeTests: XCTestCase {

    func testAV4OnlyTunnelWithIPv6RoutedIntoItGetsThePlaceholder() {
        let added = TunnelIPv6Blackhole.addressesToAdd(tunnelHasIPv6Address: false, ipv6RoutedIntoTunnel: true)
        XCTAssertEqual(added.count, 1)
        XCTAssertEqual(added.first?.address, TunnelIPv6Blackhole.placeholderAddress)
        XCTAssertEqual(added.first?.prefixLength, TunnelIPv6Blackhole.placeholderPrefixLength)
    }

    func testANodeThatAssignsIPv6KeepsExactlyWhatItAssigned() {
        XCTAssertTrue(TunnelIPv6Blackhole.addressesToAdd(tunnelHasIPv6Address: true,
                                                         ipv6RoutedIntoTunnel: true).isEmpty)
        XCTAssertTrue(TunnelIPv6Blackhole.addressesToAdd(tunnelHasIPv6Address: true,
                                                         ipv6RoutedIntoTunnel: false).isEmpty)
    }

    func testNoIPv6RoutedIntoTheTunnelAddsNothing() {
        XCTAssertTrue(TunnelIPv6Blackhole.addressesToAdd(tunnelHasIPv6Address: false,
                                                         ipv6RoutedIntoTunnel: false).isEmpty)
    }

    /// Every profile this app writes routes `::/0` into the tunnel
    /// (TunnelRoutes floors), so a v4-only node always gets the placeholder.
    func testEveryProfileThisAppWritesRoutesIPv6IntoTheTunnel() {
        for server in [[], ["10.0.0.0/8"], ["0.0.0.0/0"], ["0.0.0.0/0", "::/0"]] {
            XCTAssertTrue(TunnelRoutes.allowedIPs(server).contains(where: TunnelRoutes.isIPv6DefaultRoute),
                          "\(server)")
        }
    }

    /// A real IPv6 literal, a Unique Local Address (fc00::/7) that is routed
    /// nowhere on the internet, and not in fd00::/16, the block home routers
    /// hand out on real LANs.
    func testThePlaceholderIsAUniqueLocalAddressNoLANUses() throws {
        var bytes = [UInt8](repeating: 0, count: 16)
        let parsed = TunnelIPv6Blackhole.placeholderAddress.withCString { inet_pton(AF_INET6, $0, &bytes) }
        XCTAssertEqual(parsed, 1, "not an IPv6 literal")
        XCTAssertEqual(bytes[0] & 0xFE, 0xFC, "not in fc00::/7")
        XCTAssertEqual(bytes[0], 0xFD, "locally assigned ULAs are fd00::/8")
        XCTAssertFalse(bytes[0] == 0xFD && bytes[1] == 0x00, "fd00::/16 collides with home-router LANs")
    }

    /// The generator clamps every IPv6 interface address to /120 (upstream:
    /// the shortest prefix iOS honours); the placeholder already is one.
    func testThePlaceholderPrefixIsOneTheGeneratorKeeps() {
        XCTAssertEqual(min(120, TunnelIPv6Blackhole.placeholderPrefixLength),
                       TunnelIPv6Blackhole.placeholderPrefixLength)
        XCTAssertGreaterThanOrEqual(TunnelIPv6Blackhole.placeholderPrefixLength, 64,
                                    "a short prefix would capture other ULA networks")
    }
}
