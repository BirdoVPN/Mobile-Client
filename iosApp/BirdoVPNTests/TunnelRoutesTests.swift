import XCTest

/// MR-953 — the iOS half of the route contract: what the tunnel profile's
/// AllowedIPs (and so WireGuardKit's includedRoutes) can be, for every shape of
/// server list. The review is docs/ROUTE-CONTRACT-REVIEW.md.
///
/// The failure these guard against is silent: a tunnel that comes up, passes
/// the handshake proof and says Protected while some traffic never enters it.
final class TunnelRoutesTests: XCTestCase {

    func testNoServerEntriesIsTheFullTunnel() {
        XCTAssertEqual(TunnelRoutes.allowedIPs([]), ["0.0.0.0/0", "::/0"])
    }

    func testTheFullTunnelPairIsKeptAsSent() {
        XCTAssertEqual(TunnelRoutes.allowedIPs(["0.0.0.0/0", "::/0"]), ["0.0.0.0/0", "::/0"])
    }

    /// The usual answer from a node without IPv6: IPv6 is still captured, so it
    /// is dropped in the tunnel instead of leaving on Wi-Fi or cellular.
    func testAnIPv4OnlyListStillCapturesIPv6() {
        XCTAssertEqual(TunnelRoutes.allowedIPs(["0.0.0.0/0"]), ["0.0.0.0/0", "::/0"])
    }

    /// iOS has no split tunnelling: a list of prefixes can only be a silent
    /// split tunnel, so the IPv4 default is always added.
    func testSpecificPrefixesNeverMakeASplitTunnel() {
        XCTAssertEqual(TunnelRoutes.allowedIPs(["10.0.0.0/8"]), ["10.0.0.0/8", "::/0", "0.0.0.0/0"])
        XCTAssertEqual(TunnelRoutes.allowedIPs(["::/0"]), ["::/0", "0.0.0.0/0"])
    }

    /// The shape Android's addRoute rejects ("Bad address"): WireGuardKit would
    /// hand it to includedRoutes as written.
    func testHostBitsAreCleared() {
        XCTAssertEqual(TunnelRoutes.networkAddress("10.0.0.1/8"), "10.0.0.0/8")
        XCTAssertEqual(TunnelRoutes.networkAddress("192.168.77.130/25"), "192.168.77.128/25")
        XCTAssertEqual(TunnelRoutes.networkAddress("10.13.13.1/32"), "10.13.13.1/32")
        XCTAssertEqual(TunnelRoutes.networkAddress("fd00::1/64"), "fd00::/64")
        XCTAssertEqual(TunnelRoutes.networkAddress("2001:db8::1:2/112"), "2001:db8::1:0/112")
        XCTAssertEqual(TunnelRoutes.networkAddress(" 0.0.0.0/0 "), "0.0.0.0/0")
        XCTAssertEqual(TunnelRoutes.allowedIPs(["10.0.0.1/8"]), ["10.0.0.0/8", "::/0", "0.0.0.0/0"])
    }

    /// The step before: an entry that is not a CIDR never reaches a profile,
    /// so `TunnelRoutes` only ever sees parseable prefixes.
    func testValidationRefusesAnInvalidAllowedIP() throws {
        let key = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="
        for bad in ["10.0.0.0/33", "not-a-cidr", "10.0.0.0", " 0.0.0.0/0"] {
            var config = try JSONDecoder().decode(VPNConnectionConfig.self, Data("""
            {"endpoint": "203.0.113.7:51820", "serverPublicKey": "\(key)",
             "assignedIp": "10.100.0.2", "allowedIps": ["\(bad)"]}
            """.utf8))
            config.privateKey = key
            XCTAssertThrowsError(try config.validate(), bad) { error in
                XCTAssertEqual(error as? VPNConfigValidationError, .invalidAllowedIPs, bad)
            }
        }
        var good = try JSONDecoder().decode(VPNConnectionConfig.self, Data("""
        {"endpoint": "203.0.113.7:51820", "serverPublicKey": "\(key)",
         "assignedIp": "10.100.0.2", "allowedIps": ["10.0.0.1/8"]}
        """.utf8))
        good.privateKey = key
        XCTAssertNoThrow(try good.validate(), "host bits are valid input; TunnelRoutes clears them")
    }

    func testANonCIDREntryIsLeftAlone() {
        // ConnectWire's validation refuses these before a profile is built.
        XCTAssertEqual(TunnelRoutes.networkAddress("not-a-cidr"), "not-a-cidr")
        XCTAssertEqual(TunnelRoutes.networkAddress("10.0.0.0/33"), "10.0.0.0/33")
    }

    func testDefaultRouteSpellings() {
        XCTAssertTrue(TunnelRoutes.isIPv6DefaultRoute("::/0"))
        XCTAssertTrue(TunnelRoutes.isIPv6DefaultRoute(" 0::/0 "))
        XCTAssertFalse(TunnelRoutes.isIPv6DefaultRoute("::/1"))
        XCTAssertFalse(TunnelRoutes.isIPv6DefaultRoute("0.0.0.0/0"))
        XCTAssertTrue(TunnelRoutes.isIPv4DefaultRoute("0.0.0.0/0"))
        XCTAssertTrue(TunnelRoutes.isIPv4DefaultRoute(" 0.0.0.0/0"))
        XCTAssertFalse(TunnelRoutes.isIPv4DefaultRoute("0/0"))
        XCTAssertFalse(TunnelRoutes.isIPv4DefaultRoute("10.0.0.0/0"), "host bits on a /0 are not the default as written")
        XCTAssertFalse(TunnelRoutes.isIPv4DefaultRoute("::/0"))
        // ...but cleared first, they are: allowedIPs does not add a duplicate.
        XCTAssertEqual(TunnelRoutes.allowedIPs(["10.0.0.0/0", "::/0"]), ["0.0.0.0/0", "::/0"])
    }

    /// Every route the profile can carry is one WireGuardKit can turn into an
    /// includedRoute: a parseable CIDR with no host bits.
    func testEveryRouteIsANetworkAddress() {
        let lists: [[String]] = [[], ["0.0.0.0/0"], ["10.0.0.1/8", "fd00::1/64"], ["::/0"], ["192.168.1.7/24", "0.0.0.0/0"]]
        for list in lists {
            for route in TunnelRoutes.allowedIPs(list) {
                XCTAssertEqual(TunnelRoutes.networkAddress(route), route, "\(route) from \(list) still has host bits")
            }
        }
    }
}
