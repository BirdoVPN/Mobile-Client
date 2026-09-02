import Foundation

/// Mobile-Client #159 — the PURE half of the in-place live rebuild.
///
/// This file deliberately imports nothing beyond Foundation: the BirdoVPNTests
/// unit bundle compiles it directly (un-hosted, no app host, no WireGuardKit),
/// so the three decisions that decide whether a live, kill-switched session is
/// ever stopped are pinned by tests rather than by a device run nobody repeats.
///
/// The leak this exists for: `VpnViewModel.reapplySettings()` and
/// `selectServerLive()` used to call `VPNManager.disconnect()` first, which
/// disarms on-demand and stops the session — and `includeAllNetworks` is a
/// property of a SESSION, so with no session nothing blocks during the gap.
/// The rebuild now keeps the session and swaps the peer in place. The only path
/// that may still stop the tunnel is the pre-#159 disconnect-first path, and it
/// is reached ONLY when the SERVER cannot defer eviction — never on an error.

/// Wire names shared with birdo-web (`ConnectDto` / `multiHopConnectSchema` /
/// `ConnectionResult`). A backend that predates them 400s the request fields.
enum LiveRebuildWire {
    /// Request: this connect rides the live tunnel it is about to replace.
    static let rebuildField = "rebuild"
    /// Request: the server-side key the live tunnel is riding — the ONE key the
    /// server defers instead of evicting inline (double tenancy bounded at two).
    static let currentKeyIdField = "currentKeyId"
    /// Response: echoes `currentKeyId` when — and only when — the deferral was
    /// honoured for exactly that key.
    static let deferredKeyIdField = "deferredKeyId"
    /// The fields an older backend rejects; the fallback keys on exactly these.
    static let requestFields: Set<String> = [rebuildField, currentKeyIdField]
}

/// "The server does not know `rebuild`" — read from the validation error's OWN
/// words, never from the bare status. A genuine 400 (a malformed field the
/// backend does know) must keep the live tunnel up and never trigger the
/// disconnect-first fallback.
enum RebuildFieldRejection {
    /// Which of the rebuild request fields the error body names as unknown.
    ///
    /// Two backend shapes, both verified against birdo-web main `f450e31`:
    ///   * `POST /vpn/connect` — class-validator `forbidNonWhitelisted`
    ///     (`backend/src/main.ts:225`); `GlobalExceptionFilter` joins the array
    ///     into one `message`: "property rebuild should not exist, property
    ///     currentKeyId should not exist".
    ///   * `POST /vpn/multi-hop/connect` — `ZodValidationPipe` on the `.strict()`
    ///     schema answers the FIXED `message` "Validation failed"; the key names
    ///     travel in `details.formErrors` as zod 4.3's
    ///     `Unrecognized keys: "rebuild", "currentKeyId"` (zod 3 spelled it
    ///     `Unrecognized key(s) in object: 'rebuild'`; both are accepted).
    /// Empty for any other body, and for any status other than 400.
    static func rejectedFields(status: Int?, message: String?, formErrors: [String]?) -> [String] {
        guard status == 400 else { return [] }
        var texts: [String] = []
        if let message { texts.append(message) }
        if let formErrors { texts.append(contentsOf: formErrors) }
        var found: [String] = []
        for field in orderedFields where texts.contains(where: { namesUnknownField(field, in: $0) }) {
            found.append(field)
        }
        return found
    }

    /// Deterministic order so callers and tests can compare arrays.
    private static let orderedFields = [LiveRebuildWire.rebuildField, LiveRebuildWire.currentKeyIdField]

    private static func namesUnknownField(_ field: String, in text: String) -> Bool {
        // class-validator: "property <name> should not exist".
        if text.contains("property \(field) should not exist") { return true }
        // zod: "Unrecognized key(s)[ in object]: <quoted names>" — the names are
        // quoted with " (zod 4) or ' (zod 3); require the quotes so `rebuildFoo`
        // can never read as `rebuild`.
        guard let range = text.range(of: "Unrecognized key") else { return false }
        let tail = text[range.upperBound...]
        return tail.contains("\"\(field)\"") || tail.contains("'\(field)'")
    }
}

/// Was the deferral honoured? Only an echo of the EXACT key we are riding counts.
/// `nil` (an older backend that accepts the flag but does not defer, or a key it
/// no longer holds) and any other id both mean the old peer was, or may have
/// been, evicted inline — the client must not assume it survived.
enum RebuildDeferral {
    static func honoured(currentKeyId: String, deferredKeyId: String?) -> Bool {
        guard let deferredKeyId, !deferredKeyId.isEmpty else { return false }
        return deferredKeyId == currentKeyId
    }
}

/// What the rebuild observed at the point it has to decide what happens to the
/// LIVE session. Every case is terminal for one rebuild attempt.
enum LiveRebuildEvent: Equatable, CaseIterable {
    /// `/connect` was refused because the server does not know the rebuild
    /// fields (`RebuildFieldRejection`). No new key was minted.
    case serverDoesNotKnowRebuild
    /// `/connect` succeeded, but `deferredKeyId` did not name the key we ride:
    /// the old peer may already be gone. A new key WAS minted.
    case deferralNotHonoured
    /// `/connect` failed for any other reason (transport, refusal, 5xx). Nothing
    /// was minted; the old tunnel was never touched.
    case configRequestFailed
    /// Multi-Hop: the server confirmed a different route than requested. A new
    /// key WAS minted; the old tunnel was never touched.
    case routeNotConfirmed
    /// The in-place swap to the NEW peer was refused (keychain, preferences,
    /// the tunnel extension). The old peer is still installed on its node.
    case swapFailed
    /// The NEW peer delivered bytes: the switch is real.
    case newPeerHandshaked
    /// The NEW peer never answered inside the probe window; the session is up.
    case newPeerSilent
    /// The session dropped while probing. The on-demand rule is still armed and
    /// the persisted profile is the NEW one, so the OS is re-dialling it.
    case sessionDroppedWhileProbing
    /// The NEW peer was silent AND swapping the previous profile back was
    /// refused. `persistedProfileIsOld` says which profile an OS re-dial would
    /// start (the restore persists before it reconfigures).
    case revertFailed(persistedProfileIsOld: Bool)

    static let allCases: [LiveRebuildEvent] = [
        .serverDoesNotKnowRebuild, .deferralNotHonoured, .configRequestFailed, .routeNotConfirmed,
        .swapFailed, .newPeerHandshaked, .newPeerSilent, .sessionDroppedWhileProbing,
        .revertFailed(persistedProfileIsOld: true), .revertFailed(persistedProfileIsOld: false),
    ]
}

/// What to do with the live session — the tunnel is never stopped by any of these.
enum LiveRebuildFollowUp: Equatable {
    /// Nothing was minted, nothing changed: put the selection back, report.
    case nothing
    /// The new peer was never used: DELETE the NEW key, stay on the old peer.
    case releaseNewKey
    /// The NEW peer is the session now; the OLD key may be released.
    case commitNew
    /// Swap the previous profile back (its peer is still on its node), then
    /// DELETE the NEW key.
    case revertToOld
    /// Leave everything to the armed on-demand rule and `.connected`.
    case handOffToOnDemandRedial
    /// FAIL CLOSED: keep the session and the armed rule; the heartbeat / breaker
    /// own recovery, the user is told traffic is blocked until then.
    case stayFailedClosed(persistedProfileIsOld: Bool)
}

enum LiveRebuildDirective: Equatable {
    /// The ONLY directive that may stop the tunnel: the pre-#159 disconnect-first
    /// rebuild. Reached solely when the SERVER cannot defer our key.
    case legacyDisconnectFirst(releaseNewKeyFirst: Bool)
    /// Keep the live session and its armed on-demand rule exactly as they are.
    case keepSession(LiveRebuildFollowUp)

    var stopsTheTunnel: Bool {
        if case .legacyDisconnectFirst = self { return true }
        return false
    }
}

/// THE decision table. `VpnViewModel.finishRebuild` is a thin interpreter of
/// it, so what the tests pin here is what the app does.
enum LiveRebuildPolicy {
    /// The two events allowed to reach the disconnect-first path — both mean the
    /// server did not defer our key, so the old peer is (or may be) gone already.
    static let eventsThatMayStopTheTunnel: [LiveRebuildEvent] = [.serverDoesNotKnowRebuild, .deferralNotHonoured]

    static func directive(for event: LiveRebuildEvent) -> LiveRebuildDirective {
        switch event {
        case .serverDoesNotKnowRebuild:
            return .legacyDisconnectFirst(releaseNewKeyFirst: false)
        case .deferralNotHonoured:
            return .legacyDisconnectFirst(releaseNewKeyFirst: true)
        case .configRequestFailed:
            return .keepSession(.nothing)
        case .routeNotConfirmed, .swapFailed:
            return .keepSession(.releaseNewKey)
        case .newPeerHandshaked:
            return .keepSession(.commitNew)
        case .newPeerSilent:
            return .keepSession(.revertToOld)
        case .sessionDroppedWhileProbing:
            return .keepSession(.handOffToOnDemandRedial)
        case .revertFailed(let persistedProfileIsOld):
            return .keepSession(.stayFailedClosed(persistedProfileIsOld: persistedProfileIsOld))
        }
    }
}
