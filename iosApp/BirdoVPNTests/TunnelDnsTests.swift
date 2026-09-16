import XCTest

/// D18 BirdoShield — the RESPONSE side of the toggle (`TunnelDns.swift`),
/// compiled directly into this un-hosted bundle.
///
/// Mobile-Client #403 sent `dnsFiltering: true` and the backend answered with
/// `dns: ["10.13.13.1"]`, the node's own tunnel-gateway resolver. The
/// validator then threw it away (`(host >> 24) == 10`), so a device with
/// BirdoShield ON built the same `DNS = 1.1.1.1, 1.0.0.1` line as one with it
/// OFF — on every dial path, with no error. The adversarial review of #403
/// found it; nothing here could have, because the predicate lived in
/// VPNManager.swift, which this bundle cannot compile.
///
/// These pin the narrow exception: a SERVER-provided IPv4 entry in the same /24
/// as an assigned tunnel address survives; every other private address does
/// not; and the exception is never extended to user-typed custom DNS.
final class TunnelDnsTests: XCTestCase {

    private let tunnel = ["10.13.13.7/32"]
    private let dualStackTunnel = ["10.13.13.7/32", "fd00:b1d0::7/128"]

    // MARK: - The filtering resolver survives

    func testTheTunnelGatewayResolverTheServerHandsOutSurvives() {
        // The exact response the backend's FILTERED_DNS_SERVERS produces.
        XCTAssertEqual(
            TunnelDns.resolve(serverDns: ["10.13.13.1"], tunnelAddresses: tunnel,
                              customDnsEnabled: false, customPrimary: "", customSecondary: ""),
            ["10.13.13.1"]
        )
    }

    func testTheGatewayResolverSurvivesOnADualStackTunnel() {
        XCTAssertEqual(
            TunnelDns.resolve(serverDns: ["10.13.13.1"], tunnelAddresses: dualStackTunnel,
                              customDnsEnabled: false, customPrimary: "", customSecondary: ""),
            ["10.13.13.1"]
        )
    }

    func testTheOffCaseIsUnchanged() {
        XCTAssertEqual(
            TunnelDns.resolve(serverDns: ["1.1.1.1", "1.0.0.1"], tunnelAddresses: tunnel,
                              customDnsEnabled: false, customPrimary: "", customSecondary: ""),
            ["1.1.1.1", "1.0.0.1"]
        )
    }

    func testAPublicResolverBesideTheGatewayIsKeptAndOrderIsPreserved() {
        XCTAssertEqual(
            TunnelDns.resolve(serverDns: ["10.13.13.1", "1.1.1.1", "10.0.0.53"], tunnelAddresses: tunnel,
                              customDnsEnabled: false, customPrimary: "", customSecondary: ""),
            ["10.13.13.1", "1.1.1.1"]
        )
    }

    // MARK: - Everything else private is still rejected

    func testALanResolverInTenSlashEightIsStillRejected() {
        // Not in the client's /24: a home router, a captive portal, anything a
        // shaped response might point at on the LAN — falls back to Cloudflare.
        XCTAssertEqual(
            TunnelDns.resolve(serverDns: ["10.0.0.53"], tunnelAddresses: tunnel,
                              customDnsEnabled: false, customPrimary: "", customSecondary: ""),
            TunnelDns.fallback
        )
    }

    func testTheOtherPrivateAndLinkLocalRangesAreStillRejected() {
        for addr in ["192.168.1.1", "172.16.0.1", "169.254.169.254", "127.0.0.1", "0.0.0.0",
                     "fd00::1", "fe80::1", "::1", "::", "ff02::1", "dns.birdo.app", ""] {
            XCTAssertEqual(
                TunnelDns.resolve(serverDns: [addr], tunnelAddresses: tunnel,
                                  customDnsEnabled: false, customPrimary: "", customSecondary: ""),
                TunnelDns.fallback, "\(addr) must not be usable"
            )
        }
    }

    func testTheGatewayIsAdmittedOnlyForTheClientsOwnSubnet() {
        // Same literal, different tunnel: a client on 10.100.0.2 has no business
        // sending DNS to 10.13.13.1 — it would blackhole resolution.
        XCTAssertEqual(
            TunnelDns.resolve(serverDns: ["10.13.13.1"], tunnelAddresses: ["10.100.0.2/32"],
                              customDnsEnabled: false, customPrimary: "", customSecondary: ""),
            TunnelDns.fallback
        )
        // No IPv4 tunnel address at all: nothing to be the gateway of.
        XCTAssertEqual(
            TunnelDns.resolve(serverDns: ["10.13.13.1"], tunnelAddresses: [],
                              customDnsEnabled: false, customPrimary: "", customSecondary: ""),
            TunnelDns.fallback
        )
        XCTAssertEqual(
            TunnelDns.resolve(serverDns: ["10.13.13.1"], tunnelAddresses: ["fd00:b1d0::7/128"],
                              customDnsEnabled: false, customPrimary: "", customSecondary: ""),
            TunnelDns.fallback
        )
    }

    // MARK: - Custom DNS never gets the exception

    func testCustomDnsNeverGetsTheGatewayException() {
        // The exception is for what the SERVER hands out. A user typing the
        // gateway address gets the same rejection as any private address —
        // and custom DNS also overrides the server's list.
        XCTAssertEqual(
            TunnelDns.resolve(serverDns: ["10.13.13.1"], tunnelAddresses: tunnel,
                              customDnsEnabled: true, customPrimary: "10.13.13.1", customSecondary: "9.9.9.9"),
            ["9.9.9.9"]
        )
        XCTAssertEqual(
            TunnelDns.resolve(serverDns: ["10.13.13.1"], tunnelAddresses: tunnel,
                              customDnsEnabled: true, customPrimary: "10.13.13.1", customSecondary: ""),
            TunnelDns.fallback
        )
    }

    // MARK: - The rule itself

    func testTheRuleIsIPv4LiteralSameSlash24ARealHostNotSelf() {
        XCTAssertTrue(TunnelDns.isTunnelGatewayResolver("10.13.13.1", tunnelAddresses: tunnel))
        XCTAssertTrue(TunnelDns.isTunnelGatewayResolver("10.13.13.254", tunnelAddresses: tunnel))
        // A bare (prefix-less) tunnel address is tolerated.
        XCTAssertTrue(TunnelDns.isTunnelGatewayResolver("10.13.13.1", tunnelAddresses: ["10.13.13.7"]))
        // Network and broadcast hosts are not resolvers.
        XCTAssertFalse(TunnelDns.isTunnelGatewayResolver("10.13.13.0", tunnelAddresses: tunnel))
        XCTAssertFalse(TunnelDns.isTunnelGatewayResolver("10.13.13.255", tunnelAddresses: tunnel))
        // The client's own address is not a resolver.
        XCTAssertFalse(TunnelDns.isTunnelGatewayResolver("10.13.13.7", tunnelAddresses: tunnel))
        // Adjacent /24s are someone else's network.
        XCTAssertFalse(TunnelDns.isTunnelGatewayResolver("10.13.12.1", tunnelAddresses: tunnel))
        XCTAssertFalse(TunnelDns.isTunnelGatewayResolver("10.13.14.1", tunnelAddresses: tunnel))
        XCTAssertFalse(TunnelDns.isTunnelGatewayResolver("10.0.0.53", tunnelAddresses: tunnel))
        // Never a hostname (no lookup), never IPv6, never garbage.
        XCTAssertFalse(TunnelDns.isTunnelGatewayResolver("dns.birdo.app", tunnelAddresses: tunnel))
        XCTAssertFalse(TunnelDns.isTunnelGatewayResolver("fd00:b1d0::1", tunnelAddresses: dualStackTunnel))
        XCTAssertFalse(TunnelDns.isTunnelGatewayResolver("10.13.13", tunnelAddresses: tunnel))
        XCTAssertFalse(TunnelDns.isTunnelGatewayResolver("10.13.13.1.1", tunnelAddresses: tunnel))
        XCTAssertFalse(TunnelDns.isTunnelGatewayResolver("10.13.13.256", tunnelAddresses: tunnel))
        XCTAssertFalse(TunnelDns.isTunnelGatewayResolver("", tunnelAddresses: tunnel))
        XCTAssertFalse(TunnelDns.isTunnelGatewayResolver("10.13.13.1", tunnelAddresses: ["not-an-ip/32"]))
    }

    // MARK: - The plain predicate is unchanged

    func testThePlainPredicateStillRejectsEveryPrivateRangeIncludingTheGateway() {
        // `isUsableDnsAddress` is ALSO the custom-DNS field validator in
        // SettingsViewModel; the gateway exception must not leak into it.
        XCTAssertFalse(TunnelDns.isUsableDnsAddress("10.13.13.1"))
        XCTAssertFalse(TunnelDns.isUsableDnsAddress("10.0.0.53"))
        XCTAssertFalse(TunnelDns.isUsableDnsAddress("192.168.1.1"))
        XCTAssertFalse(TunnelDns.isUsableDnsAddress("172.31.255.254"))
        XCTAssertFalse(TunnelDns.isUsableDnsAddress("169.254.1.1"))
        XCTAssertFalse(TunnelDns.isUsableDnsAddress("127.0.0.1"))
        XCTAssertFalse(TunnelDns.isUsableDnsAddress("0.0.0.0"))
        XCTAssertFalse(TunnelDns.isUsableDnsAddress("fc00::1"))
        XCTAssertFalse(TunnelDns.isUsableDnsAddress("fe80::1"))
        XCTAssertFalse(TunnelDns.isUsableDnsAddress("::1"))
        XCTAssertFalse(TunnelDns.isUsableDnsAddress("ff02::1"))
        XCTAssertFalse(TunnelDns.isUsableDnsAddress("one.one.one.one"))
        XCTAssertTrue(TunnelDns.isUsableDnsAddress("1.1.1.1"))
        XCTAssertTrue(TunnelDns.isUsableDnsAddress("9.9.9.9"))
        XCTAssertTrue(TunnelDns.isUsableDnsAddress("172.32.0.1"))
        XCTAssertTrue(TunnelDns.isUsableDnsAddress("2606:4700:4700::1111"))
    }
}
