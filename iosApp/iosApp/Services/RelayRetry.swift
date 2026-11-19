import Foundation

/// A-H15 / MR-2212: when the first WireGuard handshake never completes, the
/// client makes ONE automatic attempt on another relay, then tells the user
/// what is true (`RelayProbeVerdict`). The API already supports it:
/// `/vpn/servers` lists every node with country, city, `isOnline` and this
/// plan's `accessible`, and `/vpn/connect` takes an explicit `serverNodeId`
/// (plan access and the ONLINE status are enforced server-side).
///
/// #549 retried only on a relay in the SAME city. The fleet has one relay per
/// city, so that retry never fired. The owner's rule (2026-11-19) widens it:
///   * a server the client chose (`SelectionOrigin.automatic`) may move to the
///     best other relay, in any country;
///   * a location the USER picked never leaves its country: the same city
///     first, then another relay in the same country, else nothing;
///   * only relays the server lists as online and open to this plan (the
///     QuickSelect filter). A node held for a jurisdiction mismatch fails its
///     health check and is depooled, so it is never listed as online;
///   * ONE retry per connect (`RelayRetryBudget`);
///   * the log says a count and a reach class only, never a server's id,
///     name, city, country or address (`logLine`).
///
/// Foundation-only so RelayRetryTests can pin the choice; `VpnViewModel`, its
/// caller, cannot be compiled into the unit-test bundle.
protocol RelayRetryCandidate: QuickSelectCandidate {
    var id: String { get }
    var countryCode: String { get }
    var city: String { get }
}

/// MR-2212: who chose the server the next dial goes to. iOS does not persist
/// the selection, so this lives in memory beside it (`VpnViewModel`).
enum SelectionOrigin: Equatable, Sendable {
    /// The client chose it: the pre-select when the list loads, or the launch
    /// auto-connect. Nobody asked for that location, so a retry may leave it.
    case automatic
    /// The user picked it, or anything other than the client's own pick set
    /// it. The safe default: a retry never takes it out of its country.
    case user
}

/// MR-2212: the one automatic retry per connect. Refilled by a connect the
/// user asked for and by a proven handshake; taken by the retry itself. A
/// retry's own dial refills nothing, so a failed retry can never loop.
struct RelayRetryBudget: Equatable, Sendable {
    private(set) var spent = false

    init() {}

    /// A connect the user asked for, or a handshake proven on the session.
    mutating func refill() { spent = false }

    /// Take the retry. False, changing nothing, when it is already spent.
    mutating func take() -> Bool {
        guard !spent else { return false }
        spent = true
        return true
    }
}

enum RelayRetry {
    /// How far a retry moves from the relay that did not answer: the only
    /// thing about the target the log may say.
    enum Reach: String, Equatable, Sendable {
        case sameCity = "same-city"
        case sameCountry = "same-country"
        case otherCountry = "other-country"
    }

    struct Choice<S: RelayRetryCandidate> {
        let server: S
        let reach: Reach
    }

    /// The relay to try after `failedId` did not answer, or nil. The first
    /// rule that applies wins:
    ///   1. another relay in the SAME city and country (#549's rule);
    ///   2. `.automatic`: the best of all the other relays, in any country;
    ///   3. `.user`: the best other relay in the SAME country;
    ///   4. nothing; the caller then says what is true instead.
    /// "Best" is QuickSelect's rule: online, accessible to this plan, least
    /// loaded. nil when `failedId` is not in `list`: without the failed
    /// relay's country the user's choice cannot be kept, so nothing moves.
    static func next<S: RelayRetryCandidate>(after failedId: String, in list: [S],
                                             origin: SelectionOrigin) -> Choice<S>? {
        guard let failed = list.first(where: { $0.id == failedId }) else { return nil }
        if let sibling = sibling(of: failedId, in: list) {
            return Choice(server: sibling, reach: .sameCity)
        }
        let others = list.filter { $0.id != failedId }
        switch origin {
        case .automatic:
            guard let best = QuickSelect.bestServer(in: others) else { return nil }
            return Choice(server: best, reach: reach(from: failed, to: best))
        case .user:
            let country = normalized(failed.countryCode)
            guard !country.isEmpty else { return nil }
            let sameCountry = others.filter { normalized($0.countryCode) == country }
            guard let best = QuickSelect.bestServer(in: sameCountry) else { return nil }
            return Choice(server: best, reach: .sameCountry)
        }
    }

    /// `next`, behind the budget: the choice with the retry taken, or nil
    /// with the budget untouched (already spent, or no relay qualifies).
    static func plan<S: RelayRetryCandidate>(after failedId: String, in list: [S],
                                             origin: SelectionOrigin,
                                             budget: inout RelayRetryBudget) -> Choice<S>? {
        guard !budget.spent, let choice = next(after: failedId, in: list, origin: origin) else { return nil }
        _ = budget.take()
        return choice
    }

    /// Another node in the SAME location as `failedId` (same country code and
    /// same non-empty city, compared case-insensitively), online and
    /// accessible to this plan, least loaded. nil when there is none.
    static func sibling<S: RelayRetryCandidate>(of failedId: String, in list: [S]) -> S? {
        guard let failed = list.first(where: { $0.id == failedId }) else { return nil }
        let city = normalized(failed.city)
        let country = normalized(failed.countryCode)
        guard !city.isEmpty, !country.isEmpty else { return nil }
        let siblings = list.filter {
            $0.id != failedId
                && normalized($0.city) == city
                && normalized($0.countryCode) == country
        }
        return QuickSelect.bestServer(in: siblings)
    }

    /// The one log line for a retry decision: the count and the reach class.
    /// It takes the whole choice so that no caller needs the server in hand to
    /// write a line; RelayRetryTests feeds it marked servers and checks that
    /// none of their fields comes out.
    /// - Parameter alreadySpent: the budget was spent before this decision
    ///   (the retry's own dial did not answer either).
    static func logLine<S: RelayRetryCandidate>(for choice: Choice<S>?, alreadySpent: Bool) -> String {
        if let choice {
            return "[RelayRetry] no handshake: automatic retry 1 of 1 (\(choice.reach.rawValue))"
        }
        return alreadySpent
            ? "[RelayRetry] no handshake: the automatic retry is spent (1 of 1)"
            : "[RelayRetry] no handshake: no relay qualifies for a retry (0 of 1)"
    }

    private static func reach<S: RelayRetryCandidate>(from failed: S, to target: S) -> Reach {
        let country = normalized(failed.countryCode)
        guard !country.isEmpty, country == normalized(target.countryCode) else { return .otherCountry }
        let city = normalized(failed.city)
        return !city.isEmpty && city == normalized(target.city) ? .sameCity : .sameCountry
    }

    private static func normalized(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}

/// MR-2212: what Home says while the app recovers from a dial whose handshake
/// never came. Cleared by a proven handshake, the probe's verdict, a refused
/// dial, or anything the user does.
enum RelayRecovery: Equatable, Sendable {
    /// The one automatic retry is under way (#549 was silent about it).
    case tryingAnotherServer
    /// The tunnel is down and the TCP probe is out (`RelayProbe`).
    case checkingNetwork

    var label: String {
        switch self {
        case .tryingAnotherServer: return "Trying another server…"
        case .checkingNetwork: return "Checking the connection…"
        }
    }
}

/// MR-2212 (design §3.3): after a dial whose handshake never came, and only
/// once the tunnel is down, the app opens a plain TCP connection to the
/// relay's Stealth port (no data, a 3 s cap). The answer picks the message:
///   * it connects: the relay is up and this network drops WireGuard's UDP;
///   * it does not: the relay is not reachable from here.
/// This replaces "Try another location", which is wrong when the network
/// filters the protocol itself (report 05 §3b). The probe tells the relay
/// nothing new: this device has just sent it WireGuard packets.
enum RelayProbeVerdict: Equatable, Sendable {
    case networkBlocksVpn
    case serverUnreachable

    /// The relays' REALITY listener (Xray): a real TCP socket on every node,
    /// and the port the server's own liveness check connects to.
    static let port: UInt16 = 8443
    static let timeout: Duration = .seconds(3)

    init(tcpConnected: Bool) {
        self = tcpConnected ? .networkBlocksVpn : .serverUnreachable
    }

    /// Plain words, no blame (design §3.3).
    var message: String {
        switch self {
        case .networkBlocksVpn:
            return "This network appears to block VPN connections. Try mobile data or another Wi-Fi."
        case .serverUnreachable:
            return "This server isn't reachable right now."
        }
    }

    /// A count only: never the relay's address or anything else that names it.
    var logLine: String {
        switch self {
        case .networkBlocksVpn: return "[RelayProbe] no handshake; relay TCP reachable=1"
        case .serverUnreachable: return "[RelayProbe] no handshake; relay TCP reachable=0"
        }
    }
}

// MARK: - MR-2493 and review L1 of #645: declared ahead of the change
//
// TEST-FIRST SCAFFOLD. These declarations carry TODAY's behaviour (no offer is
// ever made; the probe runs whatever the tunnel's state), so the new tests in
// RelayOfferTests compile and fail on what the app does now. The next commit
// of this branch gives them the new behaviour.

extension RelayRetry {
    /// MR-2493: the relay a one-tap "Try <location>" names. Today: none.
    static func offer<S: RelayRetryCandidate>(after failedId: String, in list: [S],
                                              origin: SelectionOrigin) -> S? {
        nil
    }

    /// MR-2493: the offered relay at the tap. Today: there is no tap.
    static func offered<S: RelayRetryCandidate>(_ id: String, in list: [S]) -> S? {
        nil
    }

    /// MR-2493: whether the offer stands beside the message. Today: never.
    static func offerFits(_ verdict: RelayProbeVerdict?) -> Bool {
        false
    }
}

/// MR-2493: the one tap offered beside a message. Today: never shown.
struct RelayOffer: Equatable, Sendable {
    let serverId: String
    let message: String
    let title: String
    let accessibilityLabel: String

    init(serverId: String, name: String, city: String, country: String, message: String) {
        self.serverId = serverId
        self.message = message
        title = ""
        accessibilityLabel = ""
    }

    func isShown(beside error: String?) -> Bool { false }
}

extension RelayProbeVerdict {
    /// Review L1 of #645. Today: the probe runs whatever the tunnel's state.
    static func probeHost(_ host: String?, tunnelStillUp: Bool) -> String? {
        host
    }

    /// The message when no probe runs (today's literal in VpnViewModel).
    static let unprobedMessage = "Couldn't establish a secure tunnel to this server. Try another location."
}
