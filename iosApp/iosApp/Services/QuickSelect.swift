import Foundation

/// OPEN-WORK K10 — the PURE half of "pick a server for the user".
///
/// This file deliberately imports nothing beyond Foundation: the BirdoVPNTests
/// unit bundle compiles it directly (un-hosted, no app host, no WireGuardKit),
/// so the choice is pinned by tests rather than by a device run nobody repeats.
/// `VpnViewModel.swift` cannot be compiled into that bundle — it pulls in the
/// whole app — which is why the logic does not live there.
///
/// What this replaces: both Apple pre-select paths (`loadServers()` and the
/// auto-connect-on-launch task in `VpnViewModel`) took
/// `list.first { $0.isOnline && $0.accessible }`. `/vpn/servers` is sorted by
/// name, so that was "the alphabetically-first node you may use" — Amsterdam,
/// for every user, every launch, whatever its load. Android has ranked its
/// quick-connect by `minByOrNull { it.load }` since it shipped
/// (`VpnManager.quickConnect()`); this is the Apple twin of that rule, and the
/// backend's `load` is becoming max(slot%, fresh cpu%) (K10 part A), so
/// choosing on it also steers away from a CPU-hot node with free slots.

/// The four fields the choice reads. A protocol rather than `ServerInfo`
/// itself because `ServerInfo` lives in a SwiftUI view file the test bundle
/// cannot compile; `ServerInfo` conforms next to its declaration.
protocol QuickSelectCandidate {
    var name: String { get }
    /// 0..100, as decoded from `/vpn/servers`.
    var load: Int32 { get }
    var isOnline: Bool { get }
    /// Server-computed: does THIS user's plan reach the node's `minPlan`?
    var accessible: Bool { get }
}

enum QuickSelect {
    /// The node a user who did not choose one should get.
    ///
    /// - Only online AND accessible nodes are candidates. Pre-selecting an
    ///   out-of-plan or offline node just moves the failure to the Connect
    ///   tap, where the backend refuses it.
    /// - Lowest `load` wins.
    /// - Equal loads fall back to the name, so a fresh fleet (every node at 0)
    ///   keeps the alphabetical pick users have always seen, and the answer
    ///   does not depend on the order the list arrived in. `min(by:)` keeps
    ///   the first of two elements it cannot order, so identical name AND load
    ///   resolves to list order — deterministic all the way down.
    static func bestServer<S: QuickSelectCandidate>(in list: [S]) -> S? {
        list
            .filter { $0.isOnline && $0.accessible }
            .min { a, b in
                if a.load != b.load { return a.load < b.load }
                return a.name < b.name
            }
    }
}
