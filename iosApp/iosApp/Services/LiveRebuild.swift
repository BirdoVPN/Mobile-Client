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
    /// Response, beside `success:false`: the machine-readable reason a
    /// `rebuild: true` connect was REFUSED before anything was touched
    /// (`RebuildRefusal`). Twin of birdo-web `ConnectionResult.rebuildRefused`.
    static let rebuildRefusedField = "rebuildRefused"
    /// The fields an older backend rejects; the fallback keys on exactly these,
    /// each matched on its own, in whichever order the body lists them.
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
    ///
    /// MATCHED BY FRAGMENT, one field at a time — never the whole string, and
    /// never by position. Both shapes list the unknown keys in the order they
    /// appear in the REQUEST BODY (class-validator walks the object's own keys;
    /// zod's `unrecognized_keys` issue lists the input's keys), so the
    /// "rebuild, currentKeyId" order quoted above — and pinned in birdo-web's
    /// vpn-rebuild.wire.spec.ts — is an artefact of the body that was sent.
    /// This client's `ConnectBody` / `MultiHopBody` happen to encode the two
    /// fields in that order; neither side may rely on it, and a body that
    /// names only one of them still classifies (`isRebuildFieldRejection`
    /// needs any one).
    /// Empty for any other body, for a 400 that names neither field, and for
    /// any status other than 400.
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

/// A `rebuild: true` connect the server REFUSED before touching anything: 2xx
/// `{ success:false, message, rebuildRefused }` (birdo-web
/// `VpnService.rebuildRefusal`, `MultiHopService.connectMultiHop`). No
/// eviction, no mint — the caller's tunnel is exactly as it was. Raw values
/// are the wire codes; `event(forCode:)` is the only way one becomes a
/// `LiveRebuildEvent`.
enum RebuildRefusal: String, Equatable {
    /// No `deviceId` / `currentKeyId` in the request — nothing to defer FOR.
    case deviceIdentity = "device-identity"
    /// `currentKeyId` is not an active key of (userId, deviceId): the server
    /// holds NO live session under the handle this client rides, so there is
    /// nothing it could defer and nothing a blackhole could hit — and every
    /// later rebuild would be refused the same way.
    case unknownCurrentKey = "unknown-current-key"
    /// The ownership read errored; the server refused rather than evict blind.
    case lookupFailed = "lookup-failed"
    /// Same node + same client public key as the deferred key (UNIQUE row).
    case keyReuse = "key-reuse"
    /// Multi-hop, same entry + different exit: the node-agent's
    /// one-exit-per-entry guard would 409 the install while this device's own
    /// deferred session still routes via the old exit, so the server asks for
    /// a full reconnect (birdo-web MultiHopService).
    case sameEntryExitChange = "same-entry-exit-change"

    /// The event for a code off the wire. A code this build does not know is
    /// `.configRequestFailed` — keep the session, show the server's words: a
    /// signal we cannot read must never stop the tunnel.
    static func event(forCode code: String?) -> LiveRebuildEvent {
        guard let code, let refusal = RebuildRefusal(rawValue: code) else { return .configRequestFailed }
        return .rebuildRefused(refusal)
    }
}

/// The live rebuild's probe-then-revert window: how long the client waits for
/// the NEW peer's first inbound bytes before it swaps the previous profile
/// back. Pure so both properties below are pinned by tests.
///
///   * NOT a multiple of WireGuard's REKEY_TIMEOUT. A peer that has not
///     completed a handshake re-sends its initiation every 5 s (wireguard-go
///     device/constants.go `RekeyTimeout`), so initiations leave at 0, 5, 10,
///     15, 20 s… A window that ENDS on one of those instants gives that
///     initiation's response no time to be counted, and 15 s — the fresh-dial
///     window this used to share (`VpnViewModel.HANDSHAKE_POLLS`) — is the
///     worst value: it throws the whole fourth attempt away. 18 s ends 3 s
///     after the fourth initiation, so a peer that answers on its fourth try
///     still commits.
///   * INSIDE the server's grace, which is measured from the SERVER mint:
///     birdo-web `VpnService.SUPERSEDE_GRACE_MS` starts when the new key is
///     minted — before the response has left the server — and the sweeper
///     judges the pair the moment it lapses; a reverted phone judged before
///     its OLD peer has re-handshaked would lose that peer. So everything
///     the client does AROUND the window counts against the grace too: the
///     response's transit and the in-place swap before the probe, the restore
///     and the old peer's re-handshake after it (`surroundingWorkAllowanceMs`).
enum LiveRebuildProbe {
    /// WireGuard REKEY_TIMEOUT (wireguard-go `device.RekeyTimeout`).
    static let wireGuardRekeyTimeoutMs = 5_000
    /// Poll cadence — the same 500 ms the fresh-dial arm uses. This is the gap
    /// BETWEEN probes, not the cost of one: a probe also pays an
    /// `NETunnelProviderSession.sendProviderMessage` round trip to the tunnel
    /// extension (`VPNManager.currentStats`), so `polls × pollMs` is a FLOOR on
    /// the elapsed time, never the elapsed time itself.
    static let pollMs = 500
    /// 36 × 500 ms = 18 s. The cap on the WORK: at most this many probes,
    /// whatever the clock says. It is kept ALONGSIDE the `windowMs` clock bound,
    /// not replaced by it — it is what stops the loop spinning if `Task.sleep`
    /// ever returns without consuming time (a cancelled task, whose
    /// `CancellationError` the caller's `try?` swallows).
    static let polls = 36
    /// The cap on the TIME. The probe stops at 18 s of wall clock even when the
    /// passes were slower than `pollMs` each. Both caps are enforced, together,
    /// by `shouldProbeAgain`.
    static var windowMs: Int { polls * pollMs }
    /// Twin of birdo-web `VpnService.SUPERSEDE_GRACE_MS`, from the server mint.
    static let serverGraceMs = 30_000
    /// Budget for the work around the window that also counts against the
    /// grace: response transit + in-place swap before the probe (about 2 s on
    /// a slow link), restore + re-handshake of the old peer after it (about
    /// 2 s — a re-added peer with PersistentKeepalive initiates at once),
    /// doubled for a bad day. 18 s + 8 s leaves 4 s of the 30 s unspent.
    ///
    /// A BUDGET, not an enforced bound — read it as the assumption the probe's
    /// own bound is only as good as. `mintedAtMs` is stamped SERVER-side
    /// immediately before the response returns (birdo-web `VpnService.connect`
    /// → `writeSupersedeHandle`), so the response's DOWNSTREAM transit spends
    /// this allowance and nothing on this side clamps it: `APIClient`'s session
    /// allows `timeoutIntervalForResource = 60`. Overrunning it produces the
    /// same outcome `shouldProbeAgain` bounds the probe against. Clamping it is
    /// #351's, not this file's.
    static let surroundingWorkAllowanceMs = 8_000

    /// Should the probe make another pass? BOTH caps, and PURE so both can be
    /// asserted: `VpnViewModel` is not one of BirdoVPNTests' sources
    /// (`project.yml`), so this is the only place the rule is testable at all.
    ///
    /// The clock cap is the one that was missing. `polls` alone counts
    /// ITERATIONS, while every claim beside these constants is about ELAPSED
    /// TIME measured against a grace the SERVER started before this client even
    /// had the response. A loaded extension stretching 36 probes past 18 s
    /// silently spends the allowance the revert needs — and it does so on
    /// exactly the case this window exists for: a NEW peer that completed a
    /// handshake but carries nothing back. There the sweeper sees
    /// `newHs != null` and, until the restored OLD peer has re-handshaked, an
    /// `oldHs` older than it, which is `retire-old` (birdo-web
    /// `VpnService.decideSupersede`) — the server tearing down the very peer
    /// this client is reverting TO, leaving it on a peer that no longer exists
    /// under an armed kill switch.
    ///
    /// It bounds the loop BETWEEN passes only. One `currentStats()` is a
    /// continuation around `sendProviderMessage` with NO timeout, so a WEDGED
    /// extension still overruns whatever this returns; that residual is #351's.
    static func shouldProbeAgain(passesDone: Int, remaining: Duration) -> Bool {
        passesDone < polls && remaining > .zero
    }

    /// How long the next pass may sleep — never past the end of the window,
    /// since an overshoot on the last pass is the same overrun the caps exist
    /// to avoid.
    static func nextSleep(remaining: Duration) -> Duration {
        min(remaining, .milliseconds(pollMs))
    }
}

/// What the rebuild observed at the point it has to decide what happens to the
/// LIVE session. Every case is terminal for one rebuild attempt; a refusal is
/// one too.
enum LiveRebuildEvent: Equatable, CaseIterable {
    /// `/connect` was refused because the server does not know the rebuild
    /// fields (`RebuildFieldRejection`). No new key was minted.
    case serverDoesNotKnowRebuild
    /// `/connect` answered 2xx `{ success:false, rebuildRefused }`: the server
    /// refused the rebuild BEFORE touching anything (`RebuildRefusal`). No
    /// eviction, no mint; the old tunnel is exactly as it was.
    case rebuildRefused(RebuildRefusal)
    /// `/connect` succeeded, but `deferredKeyId` did not name the key we ride:
    /// the old peer may already be gone. A new key WAS minted.
    case deferralNotHonoured
    /// `/connect` failed for any other reason (transport, refusal, 5xx). Nothing
    /// was minted; the old tunnel was never touched.
    case configRequestFailed
    /// Multi-Hop: the server confirmed a different route than requested. A new
    /// key WAS minted; the old tunnel was never touched.
    case routeNotConfirmed
    /// The session was gone by the time the swap ran (user disconnect, OS
    /// drop, heartbeat revoke): `swapConfig` refused with `noLiveSession`
    /// BEFORE writing anything, so the keychain and the persisted profile are
    /// still the OLD ones and an armed rule re-dials the OLD peer. A new key
    /// WAS minted and nothing rides it.
    case sessionGoneBeforeSwap
    /// The in-place swap to the NEW peer was refused (keychain, preferences,
    /// the tunnel extension). The old peer is still installed on its node —
    /// but the keychain and the PERSISTED profile may already name the new
    /// one, so this is a revert, never a bare release.
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
        .serverDoesNotKnowRebuild,
        .rebuildRefused(.deviceIdentity), .rebuildRefused(.unknownCurrentKey), .rebuildRefused(.lookupFailed),
        .rebuildRefused(.keyReuse), .rebuildRefused(.sameEntryExitChange),
        .deferralNotHonoured, .configRequestFailed, .routeNotConfirmed, .sessionGoneBeforeSwap,
        .swapFailed, .newPeerHandshaked, .newPeerSilent, .sessionDroppedWhileProbing,
        .revertFailed(persistedProfileIsOld: true), .revertFailed(persistedProfileIsOld: false),
    ]
}

/// What to do with the live session — the tunnel is never stopped by any of these.
enum LiveRebuildFollowUp: Equatable {
    /// Nothing was minted, nothing changed: put the selection back, report.
    case nothing
    /// A key was minted but nothing was swapped locally (the route check
    /// refused the minted config before the swap): DELETE the NEW key, stay
    /// on the old peer.
    case releaseNewKey
    /// The NEW peer is the session now; the OLD key may be released.
    case commitNew
    /// The NEW peer was installed, or its install was attempted, and it is
    /// not the session: put the previous profile back — its peer is still on
    /// its node — so `activeKeyId`, the extension's heartbeat and the
    /// PERSISTED profile (what the armed on-demand rule re-dials) all name
    /// the OLD key again, and only THEN DELETE the NEW key. A restore that
    /// fails is `LiveRebuildEvent.revertFailed`, never a release: the
    /// persisted profile may still name the new peer, and the armed rule
    /// must never be left re-dialling a peer this client deleted.
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
    /// The events allowed to reach the disconnect-first path. Each means the
    /// SERVER did not, or will not, defer the key we ride: it does not know
    /// the fields, it did not echo the key (the old peer may be gone
    /// already), it holds no live session under that handle, or it refuses
    /// the route change in place. Nothing else — no transport error, no
    /// refused swap, no silent peer — may stop the tunnel.
    static let eventsThatMayStopTheTunnel: [LiveRebuildEvent] = [
        .serverDoesNotKnowRebuild,
        .deferralNotHonoured,
        .rebuildRefused(.unknownCurrentKey),
        .rebuildRefused(.sameEntryExitChange),
    ]

    static func directive(for event: LiveRebuildEvent) -> LiveRebuildDirective {
        switch event {
        case .serverDoesNotKnowRebuild:
            return .legacyDisconnectFirst(releaseNewKeyFirst: false)
        // The server holds no live session under the handle we ride
        // (`unknown-current-key`), or will not defer it for this route change
        // (`same-entry-exit-change`): an in-place rebuild can never succeed
        // here, and keeping the tunnel would leave the user unable to switch
        // or change a setting until they disconnect by hand. Nothing was
        // minted, so there is nothing to release first.
        case .rebuildRefused(.unknownCurrentKey), .rebuildRefused(.sameEntryExitChange):
            return .legacyDisconnectFirst(releaseNewKeyFirst: false)
        // The request was incomplete, could not be judged, or reused a key:
        // the old tunnel is exactly as it was — keep it, show the server's words.
        case .rebuildRefused(.deviceIdentity), .rebuildRefused(.lookupFailed), .rebuildRefused(.keyReuse):
            return .keepSession(.nothing)
        case .deferralNotHonoured:
            return .legacyDisconnectFirst(releaseNewKeyFirst: true)
        case .configRequestFailed:
            return .keepSession(.nothing)
        // Minted, but nothing swapped locally — the route check refused the
        // config, or the session was already gone when the swap ran: release
        // the NEW key, the OLD persisted profile is what any re-dial starts.
        case .routeNotConfirmed, .sessionGoneBeforeSwap:
            return .keepSession(.releaseNewKey)
        case .newPeerHandshaked:
            return .keepSession(.commitNew)
        // ONE revert path for a refused swap and a silent new peer alike: the
        // keychain and the persisted profile may name the new peer in both,
        // so both must restore the snapshot before anything is released.
        case .swapFailed, .newPeerSilent:
            return .keepSession(.revertToOld)
        case .sessionDroppedWhileProbing:
            return .keepSession(.handOffToOnDemandRedial)
        case .revertFailed(let persistedProfileIsOld):
            return .keepSession(.stayFailedClosed(persistedProfileIsOld: persistedProfileIsOld))
        }
    }
}

/// The FRESH dial's post-`.connected` handshake window, and the one decision
/// that can undo a live session's protection.
///
/// `VpnViewModel.armKillSwitchAfterHandshake` waits here for the first inbound
/// byte before arming the on-demand rule. Its NO-handshake exit is the only
/// place in this client that stops a tunnel because a peer stayed silent, and
/// what it does is a deliberate fail-OPEN: `VPNManager.disconnect()` persists
/// on-demand OFF *before* stopping, and the server-side key is DELETEd. That is
/// right for exactly one input — a FRESH dial, whose rule was never armed and
/// which would otherwise become the re-dial blackhole the circuit breaker
/// exists to escape (see the doc comment on `armKillSwitchAfterHandshake`).
///
/// #351: it was also reached with two inputs it is wrong for, and on both the
/// session was ALREADY protected, so stopping it took the protection away:
///
///   * the OLD peer a live rebuild's revert put back on the RUNNING session
///     (`.revertToOld`, and `.stayFailedClosed` when that restore failed). The
///     `.reasserting → .connected` the restore raises is handled after
///     `rebuildLive`'s `defer` has cleared `isRebuildingLive` — there is no
///     suspension point between `restoreProfile` returning and that `defer` —
///     so the generic arm's guard no longer suppressed it, and a restored peer
///     that had not re-handshaked inside 15 s (old node down, or the server
///     sweeper's retire-old race) was torn down and its key deleted;
///   * a probe that never actually spent its window. `try? await Task.sleep`
///     on a CANCELLED task returns instantly and swallows the error, so the
///     loop can reach the exit in microseconds having proved nothing — the
///     same shape that once spun `awaitNewPeerHandshake` 7.7 M times in 2 s,
///     except that here the cost is not a spin but a teardown.
///
/// The window is measured on a `ContinuousClock`, not counted in passes: passes
/// are what a cancelled sleep makes worthless.
enum HandshakeArm {
    /// 30 × 500 ms = 15 s. Covers a slow mobile handshake — WireGuard retries
    /// its handshake every 5 s — without leaving the user staring at a tunnel
    /// that is never coming up.
    ///
    /// Declared HERE, beside `LiveRebuildProbe`, rather than in `VpnViewModel`:
    /// the two windows are deliberately different (the rebuild's 18 s must not
    /// land on a REKEY_TIMEOUT boundary and must fit inside the server's
    /// deferral grace), they were once the same value — bug (B) of #350's
    /// review — and `VpnViewModel` is not one of BirdoVPNTests' sources, so
    /// side by side here is the only place the difference can be asserted.
    static let polls = 30
    /// Poll cadence. A FLOOR on elapsed time, never the elapsed time itself:
    /// each pass also pays a `sendProviderMessage` round trip to the extension.
    static let pollMs = 500
    /// 15 s. The wall clock the no-handshake verdict requires to have passed.
    static var windowMs: Int { polls * pollMs }

    /// Does this follow-up leave a peer that this client RESTORED on the live
    /// session — i.e. one whose `.connected` is a reconfigure of a tunnel that
    /// never dropped and whose on-demand rule has been armed throughout?
    ///
    /// Exhaustive on purpose (no `default`): a follow-up added later cannot
    /// compile until someone has decided which side of the fail-open it is on.
    static func restoresPeerInPlace(_ followUp: LiveRebuildFollowUp) -> Bool {
        switch followUp {
        // `.revertToOld` restores the snapshot; `.stayFailedClosed` is only
        // reachable from that same restore having FAILED, which can still have
        // reconfigured the running tunnel (and is the case that most needs the
        // session left alone — the breaker owns the recovery there).
        case .revertToOld, .stayFailedClosed:
            return true
        // Nothing local was swapped (`.nothing`, `.releaseNewKey`), the NEW
        // peer is the session and handshaked (`.commitNew`), or the session is
        // already down and the armed rule is re-dialling it
        // (`.handOffToOnDemandRedial`).
        case .nothing, .releaseNewKey, .commitNew, .handOffToOnDemandRedial:
            return false
        }
    }

    /// The verdict when the probe ends with no inbound bytes. The order of the
    /// tests is the order of the evidence: a window that was not spent proves
    /// nothing at all, so it is answered before anything is decided about the
    /// peer.
    static func noHandshakeOutcome(peerWasRestoredInPlace: Bool,
                                   elapsed: Duration,
                                   window: Duration) -> HandshakeArmOutcome {
        if elapsed < window { return .inconclusive }
        if peerWasRestoredInPlace { return .keepSessionFailClosed }
        return .tearDownFailOpen
    }
}

/// What the handshake arm may do when its window ends with no inbound bytes.
/// Pure so the one decision that can disarm a live kill switch is asserted in
/// BirdoVPNTests rather than on a device with a peer removed by hand.
enum HandshakeArmOutcome: Equatable {
    /// FRESH dial, window actually spent, still nothing received: stop the
    /// tunnel and release the server-side key. A fail-OPEN, and the deliberate
    /// one — nothing re-dials a tunnel that was never armed, and the user is
    /// told which server did not answer.
    case tearDownFailOpen
    /// The peer under this `.connected` was RESTORED in place (#351). Keep the
    /// session and the armed on-demand rule exactly as they are and tell the
    /// user traffic is blocked: the extension's liveness check, the armed
    /// rule's re-dial and then the circuit breaker are the recovery, which is
    /// the same escape `LiveRebuildFollowUp.stayFailedClosed` relies on.
    case keepSessionFailClosed
    /// The probe returned without spending its window (a cancelled task, whose
    /// `CancellationError` the caller's `try?` eats). It has proved nothing, so
    /// it decides nothing: no teardown, and no message claiming a verdict.
    case inconclusive
}

/// #351 / #336 — the ONE "may a dial start right now?" decision, so the five
/// entry points that can start or change a tunnel cannot answer it five
/// different ways.
///
/// `isConnecting` alone CANNOT answer it, and that is the whole of #351 item 2:
/// `VpnViewModel.handleStatusChange` clears `isConnecting` on `.disconnecting`
/// and `.disconnected`, which are precisely the transitions the two
/// disconnect-first paths WAIT for. So from `vpnManager.disconnect()` until the
/// redial — a window `awaitTeardown()` bounds at about 5 s — every entry point
/// read "idle", and a second dial in it minted a second server-side peer for
/// the same device while the first was still tearing the tunnel down under it.
/// Raising `isConnecting` earlier does not work either: the status handler is
/// the thing that clears it.
///
/// `isTearingDownForRedial` is the flag that survives those transitions. It is
/// owned by the two teardown sites in `RedialTeardownSite` and read only here.
enum TunnelDialGate {
    /// Why a dial is refused. A raw-valued, `CaseIterable` enum rather than a
    /// bare Bool so BirdoVPNTests can enumerate the blocking states and a new
    /// one cannot be added without the enumeration failing to compile.
    enum Block: String, Equatable, CaseIterable {
        /// A dial is already in flight and has not resolved (`isConnecting`).
        case dialInFlight
        /// A live in-place rebuild owns the session; it commits or reverts on
        /// its own (`isRebuildingLive`).
        case liveRebuildInFlight
        /// #351: a disconnect-first path has asked for the teardown and is
        /// waiting for it to land. The state `isConnecting` cannot express.
        case tearingDownForRedial
    }

    /// The first reason this dial is refused, or `nil` when it may start.
    /// Order is severity-free — a caller that treats one reason differently
    /// (`selectServerLive` and `.dialInFlight`) switches on it explicitly, so
    /// the exception is written down rather than achieved by omission.
    static func block(isConnecting: Bool,
                      isRebuildingLive: Bool,
                      isTearingDownForRedial: Bool) -> Block? {
        if isConnecting { return .dialInFlight }
        if isRebuildingLive { return .liveRebuildInFlight }
        if isTearingDownForRedial { return .tearingDownForRedial }
        return nil
    }

    static func mayStartDial(isConnecting: Bool,
                             isRebuildingLive: Bool,
                             isTearingDownForRedial: Bool) -> Bool {
        block(isConnecting: isConnecting,
              isRebuildingLive: isRebuildingLive,
              isTearingDownForRedial: isTearingDownForRedial) == nil
    }
}

/// Every entry point in this client that can START or CHANGE a tunnel.
///
/// An inventory, pinned by a test, because #336's finding is that a guard
/// landing on some of N parallel paths is this estate's most common defect and
/// that a COMMENT naming the other paths is demonstrably not protection. A
/// sixth path cannot be added without this list — and therefore its test —
/// being edited, which is the moment to ask whether it calls `TunnelDialGate`.
enum TunnelDialEntryPoint: String, CaseIterable {
    /// `VpnViewModel.connect(userInitiated:)` — the Home CTA and every redial.
    case connect
    /// `VpnViewModel.connectMultiHop(entry:exit:)` — the Multi-Hop screen.
    case connectMultiHop
    /// `VpnViewModel.reapplySettings()` — the settings apply-on-change blip.
    case reapplySettings
    /// `VpnViewModel.selectServerLive(_:)` — a tap on a different node. Treats
    /// `.dialInFlight` as its switch-while-connecting case, refuses the rest.
    case selectServerLive
    /// `VpnViewModel.autoConnectIfEnabled()` — the launch auto-connect.
    case autoConnectIfEnabled
}

/// Every place that stops a LIVE tunnel and then re-dials it: the two sides of
/// the twin the #351 guard has to cover. Guarding one and not the other is the
/// #336 shape exactly — and the shape #350's own review noted was left open.
enum RedialTeardownSite: String, CaseIterable {
    /// `VpnViewModel.connectMultiHop` over a live session — the one live
    /// rebuild deliberately NOT done in place (the node-agent's
    /// one-exit-per-entry guard would 409 the install).
    case connectMultiHopOverALiveSession
    /// `VpnViewModel.legacyRebuild` — the pre-#159 disconnect-first rebuild,
    /// reached only when the SERVER cannot defer this device's key.
    case legacyRebuild
}
