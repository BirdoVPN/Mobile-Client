// SPDX-License-Identifier: MIT
// BIRDO LOCAL (MR-953 F3, review of #621). Not upstream; see iosApp/project.yml.

import NetworkExtension
import XCTest
@testable import WireGuardKit

/// Local patch 6 run through the REAL `PacketTunnelSettingsGenerator`, not
/// only through its Foundation-only half (TunnelIPv6BlackholeTests in
/// BirdoVPNTests, which cannot link WireGuardKit).
///
/// A tunnel whose node gives it no IPv6 address still routes `::/0` into the
/// tunnel (TunnelRoutes always adds it). Upstream built that `NEIPv6Settings`
/// with no address at all, and nothing guarantees the OS installs a route into
/// an interface with no IPv6 address. The generator now gives such a tunnel
/// the placeholder ULA, so IPv6 goes into the tunnel and dies there.
///
/// On the generator before patch 6, the first test fails: `addresses == []`.
final class PacketTunnelSettingsGeneratorTests: XCTestCase {

    private func settings(addresses: [String], allowedIPs: [String]) throws -> NEPacketTunnelNetworkSettings {
        var interface = InterfaceConfiguration(privateKey: PrivateKey())
        interface.addresses = try addresses.map { try XCTUnwrap(IPAddressRange(from: $0), $0) }
        var peer = PeerConfiguration(publicKey: PrivateKey().publicKey)
        peer.allowedIPs = try allowedIPs.map { try XCTUnwrap(IPAddressRange(from: $0), $0) }
        let tunnel = TunnelConfiguration(name: "generator-test", interface: interface, peers: [peer])
        return PacketTunnelSettingsGenerator(tunnelConfiguration: tunnel, resolvedEndpoints: [nil])
            .generateNetworkSettings()
    }

    /// The node assigns only an IPv4 address; the profile routes everything.
    func testAnIPv4OnlyTunnelThatRoutesIPv6GetsThePlaceholderAddress() throws {
        let generated = try settings(addresses: ["10.64.0.2/32"], allowedIPs: ["0.0.0.0/0", "::/0"])
        let v6 = try XCTUnwrap(generated.ipv6Settings)
        XCTAssertEqual(v6.addresses, [TunnelIPv6Blackhole.placeholderAddress])
        XCTAssertEqual(v6.addresses, ["fd8e:51b7:2c4a::1"])
        XCTAssertEqual(v6.networkPrefixLengths.map { $0.intValue }, [120])
        // ::/0 still goes INTO the tunnel; the placeholder adds no route.
        XCTAssertEqual(v6.includedRoutes?.map { $0.destinationAddress }, ["::"])
        XCTAssertEqual(v6.includedRoutes?.map { $0.destinationNetworkPrefixLength.intValue }, [0])
        // IPv4 is untouched.
        XCTAssertEqual(generated.ipv4Settings?.addresses, ["10.64.0.2"])
    }

    /// A node that assigns IPv6 keeps exactly what it assigned (clamped to
    /// /120 by upstream's generator, as before).
    func testADualStackTunnelKeepsOnlyItsOwnIPv6Address() throws {
        let generated = try settings(addresses: ["10.64.0.2/32", "fd00:1234::2/128"],
                                     allowedIPs: ["0.0.0.0/0", "::/0"])
        let v6 = try XCTUnwrap(generated.ipv6Settings)
        XCTAssertEqual(v6.addresses, ["fd00:1234::2"])
        XCTAssertEqual(v6.networkPrefixLengths.map { $0.intValue }, [120])
        XCTAssertFalse(v6.addresses.contains(TunnelIPv6Blackhole.placeholderAddress))
    }

    /// No IPv6 routed into the tunnel: nothing to blackhole, no placeholder.
    func testATunnelThatRoutesNoIPv6GetsNoPlaceholder() throws {
        let generated = try settings(addresses: ["10.64.0.2/32"], allowedIPs: ["0.0.0.0/0"])
        XCTAssertEqual(generated.ipv6Settings?.addresses ?? [], [])
        XCTAssertEqual(generated.ipv6Settings?.includedRoutes?.count ?? 0, 0)
    }
}
