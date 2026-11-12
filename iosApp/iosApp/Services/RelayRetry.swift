import Foundation

/// A-H15 / MR-2212 (retry part): when the first WireGuard handshake never
/// completes, the client makes ONE automatic attempt on another relay in the
/// same location, then falls back to today's "couldn't establish a tunnel"
/// message. The API already supports it: `/vpn/servers` lists every node with
/// country and city, and `/vpn/connect` takes an explicit `serverNodeId`
/// (plan access is enforced server-side).
///
/// Foundation-only so RelayRetryTests can pin the choice; `VpnViewModel`, its
/// caller, cannot be compiled into the unit-test bundle.
protocol RelayRetryCandidate: QuickSelectCandidate {
    var id: String { get }
    var countryCode: String { get }
    var city: String { get }
}

enum RelayRetry {
    /// The relay to try after `failedId` did not answer: another node in the
    /// SAME location (same country code and same non-empty city, compared
    /// case-insensitively), online and accessible to this plan, least loaded.
    /// nil when the location has no such sibling -- the caller then shows the
    /// existing message rather than silently moving the user to another place.
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

    private static func normalized(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}
