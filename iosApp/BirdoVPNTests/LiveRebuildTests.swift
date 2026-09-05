import XCTest

/// Mobile-Client #159 — the pure half of the in-place live rebuild
/// (`LiveRebuild.swift`), compiled directly into this un-hosted bundle (no app
/// host, no KMP framework, no WireGuardKit).
///
/// These pin the decisions that decide whether a live, kill-switched session
/// is ever stopped — and, since the follow-up review of the shipped set, the
/// server-refusal routing, the probe window and the single revert path. The
/// bug they guard needs a second node and a peer removed by hand on the first
/// to observe, so nobody re-runs that by hand.
///
/// On the pre-#159 code this file does not compile ("cannot find
/// 'RebuildFieldRejection' in scope", and the same for every type below): the
/// policy did not exist — both rebuild paths called `VPNManager.disconnect()`
/// unconditionally and no 400 was ever classified.
final class LiveRebuildTests: XCTestCase {

    // MARK: - "The server does not know `rebuild`" is read from the error's words

    func testClassValidatorRejectionNamesBothRebuildFields() {
        // POST /vpn/connect under forbidNonWhitelisted: class-validator's array
        // is joined into one string by GlobalExceptionFilter.
        let fields = RebuildFieldRejection.rejectedFields(
            status: 400,
            message: "property rebuild should not exist, property currentKeyId should not exist",
            formErrors: nil
        )
        XCTAssertEqual(fields, ["rebuild", "currentKeyId"])
    }

    func testZodStrictRejectionOnTheMultiHopTwinIsReadFromFormErrors() {
        // POST /vpn/multi-hop/connect: ZodValidationPipe answers the FIXED
        // message "Validation failed" — the key names travel in
        // details.formErrors. Matching on `message` alone would miss the twin.
        let zod4 = RebuildFieldRejection.rejectedFields(
            status: 400,
            message: "Validation failed",
            formErrors: [#"Unrecognized keys: "rebuild", "currentKeyId""#]
        )
        XCTAssertEqual(zod4, ["rebuild", "currentKeyId"])

        let zod3 = RebuildFieldRejection.rejectedFields(
            status: 400,
            message: "Validation failed",
            formErrors: ["Unrecognized key(s) in object: 'rebuild'"]
        )
        XCTAssertEqual(zod3, ["rebuild"])
    }

    func testAGenuine400IsNotARebuildRejection() {
        // A malformed field the backend DOES know must keep the live tunnel up:
        // it is not "the server does not know rebuild".
        XCTAssertEqual(RebuildFieldRejection.rejectedFields(
            status: 400, message: "serverNodeId must be a string", formErrors: nil), [])
        // Someone else's unknown field is someone else's drift, not ours.
        XCTAssertEqual(RebuildFieldRejection.rejectedFields(
            status: 400, message: "property serverId should not exist", formErrors: nil), [])
        XCTAssertEqual(RebuildFieldRejection.rejectedFields(
            status: 400, message: "Validation failed", formErrors: [#"Unrecognized key: "integrityToken""#]), [])
        // A prefix of the field name is not the field name.
        XCTAssertEqual(RebuildFieldRejection.rejectedFields(
            status: 400, message: "Validation failed", formErrors: [#"Unrecognized key: "rebuildFoo""#]), [])
        XCTAssertEqual(RebuildFieldRejection.rejectedFields(status: 400, message: nil, formErrors: nil), [])
    }

    func testOnlyA400Counts() {
        let text = "property rebuild should not exist"
        XCTAssertEqual(RebuildFieldRejection.rejectedFields(status: 422, message: text, formErrors: nil), [])
        XCTAssertEqual(RebuildFieldRejection.rejectedFields(status: 500, message: text, formErrors: nil), [])
        XCTAssertEqual(RebuildFieldRejection.rejectedFields(status: nil, message: text, formErrors: nil), [])
    }

    func testTheWireNamesAreTheBackendsNames() {
        // These strings are the twin of birdo-web's ConnectDto /
        // multiHopConnectSchema / ConnectionResult. Renaming one side silently
        // turns every rebuild into the legacy path.
        XCTAssertEqual(LiveRebuildWire.rebuildField, "rebuild")
        XCTAssertEqual(LiveRebuildWire.currentKeyIdField, "currentKeyId")
        XCTAssertEqual(LiveRebuildWire.deferredKeyIdField, "deferredKeyId")
        XCTAssertEqual(LiveRebuildWire.requestFields, ["rebuild", "currentKeyId"])
    }

    // MARK: - deferredKeyId: the deferral counts only when it names the key we ride

    func testDeferralIsHonouredOnlyForTheExactCurrentKey() {
        XCTAssertTrue(RebuildDeferral.honoured(currentKeyId: "key-old", deferredKeyId: "key-old"))
        XCTAssertFalse(RebuildDeferral.honoured(currentKeyId: "key-old", deferredKeyId: nil),
                       "an older backend that accepts the flag but never defers")
        XCTAssertFalse(RebuildDeferral.honoured(currentKeyId: "key-old", deferredKeyId: ""),
                       "an empty echo is no echo")
        XCTAssertFalse(RebuildDeferral.honoured(currentKeyId: "key-old", deferredKeyId: "key-other"),
                       "some other key was deferred — ours may already be gone")
    }

    // MARK: - Never stop the tunnel on an error path

    func testOnlyTheServerCannotOrWillNotDeferEventsMayStopTheTunnel() {
        for event in LiveRebuildEvent.allCases {
            let directive = LiveRebuildPolicy.directive(for: event)
            let mayStop = LiveRebuildPolicy.eventsThatMayStopTheTunnel.contains(event)
            XCTAssertEqual(directive.stopsTheTunnel, mayStop,
                           "\(event) must \(mayStop ? "" : "NOT ")reach the disconnect-first path")
        }
        // Branch code: the list had two entries — a refusal for a key the server
        // will not defer stayed on a tunnel that could never be rebuilt again.
        XCTAssertEqual(LiveRebuildPolicy.eventsThatMayStopTheTunnel, [
            .serverDoesNotKnowRebuild,
            .deferralNotHonoured,
            .rebuildRefused(.unknownCurrentKey),
            .rebuildRefused(.sameEntryExitChange),
        ])
    }

    func testEveryFailureKeepsTheSessionAndReleasesOnlyWhatWasMinted() {
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .configRequestFailed), .keepSession(.nothing))
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .routeNotConfirmed), .keepSession(.releaseNewKey))
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .sessionGoneBeforeSwap), .keepSession(.releaseNewKey))
        // Branch code: `.swapFailed` → `.releaseNewKey` — the NEW key was deleted
        // while the keychain and the persisted profile could still name it.
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .swapFailed), .keepSession(.revertToOld))
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .newPeerSilent), .keepSession(.revertToOld))
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .sessionDroppedWhileProbing),
                       .keepSession(.handOffToOnDemandRedial))
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .revertFailed(persistedProfileIsOld: true)),
                       .keepSession(.stayFailedClosed(persistedProfileIsOld: true)))
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .revertFailed(persistedProfileIsOld: false)),
                       .keepSession(.stayFailedClosed(persistedProfileIsOld: false)))
    }

    func testSuccessCommitsTheNewPeer() {
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .newPeerHandshaked), .keepSession(.commitNew))
    }

    func testTheLegacyPathReleasesTheMintedKeyOnlyWhenOneExists() {
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .serverDoesNotKnowRebuild),
                       .legacyDisconnectFirst(releaseNewKeyFirst: false))
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .deferralNotHonoured),
                       .legacyDisconnectFirst(releaseNewKeyFirst: true))
    }

    // MARK: - (D) Old-backend detection is by fragment, in either order

    func testRejectionMatchingDoesNotDependOnTheOrderTheBodyListedTheKeys() {
        // Both backends list unknown keys in the REQUEST BODY's order; the
        // pinned strings show "rebuild, currentKeyId" only because that is how
        // this client's bodies happen to encode them. Reverse it, and match
        // each field alone. (Passes on the branch code too — a regression pin.)
        XCTAssertEqual(RebuildFieldRejection.rejectedFields(
            status: 400,
            message: "property currentKeyId should not exist, property rebuild should not exist",
            formErrors: nil), ["rebuild", "currentKeyId"])
        XCTAssertEqual(RebuildFieldRejection.rejectedFields(
            status: 400, message: "Validation failed",
            formErrors: [#"Unrecognized keys: "currentKeyId", "rebuild""#]), ["rebuild", "currentKeyId"])
        // Other unknown keys around ours, in any order.
        XCTAssertEqual(RebuildFieldRejection.rejectedFields(
            status: 400,
            message: "property foo should not exist, property currentKeyId should not exist, property bar should not exist",
            formErrors: nil), ["currentKeyId"])
        // class-validator's raw ARRAY form (before GlobalExceptionFilter joins it)
        // arrives joined by a space in APIErrorBody — still a fragment match.
        XCTAssertEqual(RebuildFieldRejection.rejectedFields(
            status: 400,
            message: "property currentKeyId should not exist property rebuild should not exist",
            formErrors: nil), ["rebuild", "currentKeyId"])
    }

    // MARK: - (A) A refusal the server sends with success:false

    func testRefusalCodesAreTheBackendsCodes() {
        // Twin of birdo-web `RebuildRefusal` (vpn.service.ts). Renaming one
        // side turns that refusal into "unknown code" — keep-session — silently.
        XCTAssertEqual(RebuildRefusal.deviceIdentity.rawValue, "device-identity")
        XCTAssertEqual(RebuildRefusal.unknownCurrentKey.rawValue, "unknown-current-key")
        XCTAssertEqual(RebuildRefusal.lookupFailed.rawValue, "lookup-failed")
        XCTAssertEqual(RebuildRefusal.keyReuse.rawValue, "key-reuse")
        XCTAssertEqual(RebuildRefusal.sameEntryExitChange.rawValue, "same-entry-exit-change")
        XCTAssertEqual(LiveRebuildWire.rebuildRefusedField, "rebuildRefused")
    }

    func testARefusalForAKeyTheServerWillNotDeferTakesTheLegacyPath() {
        // unknown-current-key: the server holds no live session under the
        // handle we ride — nothing to defer, nothing to blackhole, and every
        // later rebuild would be refused the same way. same-entry-exit-change:
        // the server asks for a full reconnect. Both: nothing was minted.
        XCTAssertEqual(RebuildRefusal.event(forCode: "unknown-current-key"),
                       .rebuildRefused(.unknownCurrentKey))
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .rebuildRefused(.unknownCurrentKey)),
                       .legacyDisconnectFirst(releaseNewKeyFirst: false))
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .rebuildRefused(.sameEntryExitChange)),
                       .legacyDisconnectFirst(releaseNewKeyFirst: false))
    }

    func testTheOtherRefusalsKeepTheSessionAndShowTheServersWords() {
        for refusal in [RebuildRefusal.deviceIdentity, .lookupFailed, .keyReuse] {
            XCTAssertEqual(LiveRebuildPolicy.directive(for: .rebuildRefused(refusal)), .keepSession(.nothing),
                           "\(refusal) leaves the old tunnel exactly as it was")
        }
    }

    func testAnUnreadableRefusalCodeNeverStopsTheTunnel() {
        // A code a future backend adds, an empty one, none at all: keep the
        // session, show the message. Only a code we can read may route to the
        // disconnect-first path.
        XCTAssertEqual(RebuildRefusal.event(forCode: "some-future-reason"), .configRequestFailed)
        XCTAssertEqual(RebuildRefusal.event(forCode: ""), .configRequestFailed)
        XCTAssertEqual(RebuildRefusal.event(forCode: nil), .configRequestFailed)
        XCTAssertFalse(LiveRebuildPolicy.directive(for: RebuildRefusal.event(forCode: "unknown-current-key ")).stopsTheTunnel,
                       "a near-miss is not the code")
    }

    // MARK: - (B) The probe-then-revert window

    func testTheRebuildProbeIsNotARekeyMultipleAndEndsInsideTheServerGrace() {
        // Branch code: the rebuild probe shared the fresh dial's 15 s — a
        // multiple of REKEY_TIMEOUT, ending exactly when the fourth handshake
        // initiation leaves.
        XCTAssertEqual(LiveRebuildProbe.polls, 36)
        XCTAssertEqual(LiveRebuildProbe.pollMs, 500)
        XCTAssertEqual(LiveRebuildProbe.windowMs, 18_000)
        XCTAssertEqual(LiveRebuildProbe.wireGuardRekeyTimeoutMs, 5_000)
        XCTAssertNotEqual(LiveRebuildProbe.windowMs % LiveRebuildProbe.wireGuardRekeyTimeoutMs, 0,
                          "a window ending on a REKEY_TIMEOUT boundary throws a whole handshake attempt away")
        // Measured from the SERVER mint, so the round trip and the swap before
        // the probe, and the restore and re-handshake after it, all count.
        XCTAssertEqual(LiveRebuildProbe.serverGraceMs, 30_000, "twin of birdo-web VpnService.SUPERSEDE_GRACE_MS")
        XCTAssertLessThan(LiveRebuildProbe.windowMs + LiveRebuildProbe.surroundingWorkAllowanceMs,
                          LiveRebuildProbe.serverGraceMs)
    }

    func testTheProbeIsBoundedByTheClockAndNotOnlyByThePassCount() {
        // Branch code counted ITERATIONS. `polls × pollMs` is only a FLOOR on
        // elapsed time — every pass also pays a sendProviderMessage round trip
        // — so 36 probes can outlast 18 s while the server's grace, started at
        // the mint, runs out and the sweeper retires the peer the client is
        // about to revert TO. Time spent has to end the probe even with passes
        // left in the budget.
        XCTAssertFalse(LiveRebuildProbe.shouldProbeAgain(passesDone: 0, remaining: .zero),
                       "the window is spent; unused passes are not a reason to keep probing")
        XCTAssertFalse(LiveRebuildProbe.shouldProbeAgain(passesDone: 1, remaining: .milliseconds(-3_600)),
                       "already past the window: 35 unused passes must not extend it")
        XCTAssertTrue(LiveRebuildProbe.shouldProbeAgain(passesDone: 35, remaining: .milliseconds(500)),
                      "time and passes both left: probe")
        // ...and the pass cap still holds, so a `Task.sleep` that returns
        // without consuming time (a cancelled task, whose error `try?` eats)
        // can never spin this loop for the whole 18 s.
        XCTAssertFalse(LiveRebuildProbe.shouldProbeAgain(passesDone: LiveRebuildProbe.polls,
                                                         remaining: .milliseconds(5_000)),
                       "the work cap bounds a sleep that consumes no time")
    }

    func testTheLastPassNeverSleepsPastTheEndOfTheWindow() {
        // An overshoot on the last pass is the same overrun the caps exist to
        // avoid, so the final sleep is the remainder, not a whole cadence.
        XCTAssertEqual(LiveRebuildProbe.nextSleep(remaining: .milliseconds(120)), .milliseconds(120))
        XCTAssertEqual(LiveRebuildProbe.nextSleep(remaining: .milliseconds(9_000)),
                       .milliseconds(LiveRebuildProbe.pollMs))
    }

    // MARK: - (C) One revert path, and nothing is released before the restore

    func testARefusedSwapRevertsThroughTheSamePathAsASilentPeer() {
        // Branch code: `.swapFailed` → `.releaseNewKey`, which DELETEd the new
        // key regardless of whether the best-effort restore in rebuildLive had
        // succeeded — so a failed restore left `activeKeyId`, the keychain and
        // the persisted profile on a peer this client had just deleted, and
        // the armed on-demand rule re-dialling it.
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .swapFailed), .keepSession(.revertToOld))
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .swapFailed),
                       LiveRebuildPolicy.directive(for: .newPeerSilent),
                       "two revert paths are the twin-drift shape this exists to remove")
    }

    func testOnlyARouteRefusalReleasesWithoutRestoringTheProfile() {
        // `.releaseNewKey` is correct exactly when nothing was swapped locally,
        // which is only the route check refusing the minted config before the
        // swap. Every other minted-then-failed outcome restores first.
        let releasing = LiveRebuildEvent.allCases.filter {
            LiveRebuildPolicy.directive(for: $0) == .keepSession(.releaseNewKey)
        }
        XCTAssertEqual(releasing, [.routeNotConfirmed, .sessionGoneBeforeSwap])
    }

    func testAFailedRestoreNeverReleasesAKey() {
        // The persisted profile may still name the NEW peer; deleting it would
        // strand the armed rule's re-dial. Both failure shapes fail closed.
        for persistedIsOld in [true, false] {
            let directive = LiveRebuildPolicy.directive(for: .revertFailed(persistedProfileIsOld: persistedIsOld))
            XCTAssertEqual(directive, .keepSession(.stayFailedClosed(persistedProfileIsOld: persistedIsOld)))
            XCTAssertNotEqual(directive, .keepSession(.releaseNewKey))
            XCTAssertNotEqual(directive, .keepSession(.revertToOld))
        }
    }
}
