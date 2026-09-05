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

    // MARK: - #351 (1) The no-handshake exit is a fail-OPEN; only a fresh dial may reach it

    func testTheFreshDialWindowAndTheRebuildProbeAreDifferentWindows() {
        // Both windows now live in LiveRebuild.swift, which is why this can be
        // asserted at all: `VpnViewModel` is not one of this bundle's sources
        // (project.yml), so while the fresh dial's 15 s lived there as a private
        // constant, nothing could compare the two. They were ONE value on the
        // #350 branch — bug (B) of its review.
        XCTAssertEqual(HandshakeArm.polls, 30)
        XCTAssertEqual(HandshakeArm.pollMs, 500)
        XCTAssertEqual(HandshakeArm.windowMs, 15_000)
        XCTAssertNotEqual(HandshakeArm.windowMs, LiveRebuildProbe.windowMs,
                          "the rebuild probe must not share the fresh dial's REKEY_TIMEOUT-multiple window")
    }

    func testAPeerTheRevertRestoredIsNeverTornDownForNotHandshaking() {
        // THE #351 FIX. `armKillSwitchAfterHandshake`'s no-handshake exit calls
        // `VPNManager.disconnect()` (which persists on-demand OFF *before*
        // stopping) and DELETEs the server-side key. On a peer this client just
        // restored on a LIVE session that is the reverse of what the revert is
        // for: the rule has been armed the whole time and the key is the one the
        // user is still riding.
        let window = Duration.milliseconds(HandshakeArm.windowMs)
        XCTAssertEqual(HandshakeArm.noHandshakeOutcome(peerWasRestoredInPlace: true,
                                                       elapsed: window, window: window),
                       .keepSessionFailClosed)
        XCTAssertEqual(HandshakeArm.noHandshakeOutcome(peerWasRestoredInPlace: true,
                                                       elapsed: .seconds(600), window: window),
                       .keepSessionFailClosed,
                       "however long it waited, a restored peer is still not a fresh dial")
        // ...and the deliberate fail-open is untouched for the input it exists
        // for: a FRESH dial that spent its whole window in silence. Removing
        // that would trade this bug for the re-dial blackhole loop.
        XCTAssertEqual(HandshakeArm.noHandshakeOutcome(peerWasRestoredInPlace: false,
                                                       elapsed: window, window: window),
                       .tearDownFailOpen)
        XCTAssertEqual(HandshakeArm.noHandshakeOutcome(peerWasRestoredInPlace: false,
                                                       elapsed: .seconds(600), window: window),
                       .tearDownFailOpen)
    }

    func testAWindowThatWasNeverSpentDecidesNothing() {
        // `try? await Task.sleep` on a cancelled task returns instantly and eats
        // the CancellationError — the shape that ran 7.7 M iterations in 2 s in
        // the rebuild probe. Here the loop is bounded by its pass count, so it
        // does not spin; it arrives at the exit having proved nothing, and that
        // exit STOPS THE TUNNEL. Elapsed time is the only evidence that the peer
        // was actually given its window.
        let window = Duration.milliseconds(HandshakeArm.windowMs)
        XCTAssertEqual(HandshakeArm.noHandshakeOutcome(peerWasRestoredInPlace: false,
                                                       elapsed: .zero, window: window),
                       .inconclusive)
        XCTAssertEqual(HandshakeArm.noHandshakeOutcome(peerWasRestoredInPlace: false,
                                                       elapsed: .milliseconds(14_999), window: window),
                       .inconclusive,
                       "one millisecond short is still short — this exit disarms a live kill switch")
        XCTAssertEqual(HandshakeArm.noHandshakeOutcome(peerWasRestoredInPlace: true,
                                                       elapsed: .zero, window: window),
                       .inconclusive,
                       "no verdict beats a fail-closed message the probe did not earn")
    }

    func testEveryFollowUpThatRestoresAPeerInPlaceMustFailClosed() {
        // The enumeration #336 asks for: not "does the revert path fail closed?"
        // but "for every outcome the policy can produce, is the one that leaves
        // a restored peer on a live session marked as such?" A follow-up added
        // later cannot compile in `HandshakeArm.restoresPeerInPlace` (no
        // `default`) until someone has answered this for it.
        let restoring = LiveRebuildEvent.allCases.filter { event in
            guard case .keepSession(let followUp) = LiveRebuildPolicy.directive(for: event) else { return false }
            return HandshakeArm.restoresPeerInPlace(followUp)
        }
        XCTAssertEqual(restoring, [
            .swapFailed,                                    // .revertToOld
            .newPeerSilent,                                 // .revertToOld
            .revertFailed(persistedProfileIsOld: true),     // .stayFailedClosed
            .revertFailed(persistedProfileIsOld: false),    // .stayFailedClosed
        ])
        for event in restoring {
            XCTAssertEqual(HandshakeArm.noHandshakeOutcome(
                peerWasRestoredInPlace: true,
                elapsed: .milliseconds(HandshakeArm.windowMs),
                window: .milliseconds(HandshakeArm.windowMs)), .keepSessionFailClosed,
                "\(event) leaves the OLD peer on a live, armed session")
        }
    }

    func testAFollowUpThatSwappedNothingLocallyIsNotARestore() {
        // The other half of the enumeration: a marker raised for these would
        // suppress the fresh-dial teardown on a tunnel that genuinely never
        // came up, which is the trap the teardown exists to escape.
        XCTAssertFalse(HandshakeArm.restoresPeerInPlace(.nothing))
        XCTAssertFalse(HandshakeArm.restoresPeerInPlace(.releaseNewKey))
        XCTAssertFalse(HandshakeArm.restoresPeerInPlace(.commitNew))
        XCTAssertFalse(HandshakeArm.restoresPeerInPlace(.handOffToOnDemandRedial))
        XCTAssertTrue(HandshakeArm.restoresPeerInPlace(.revertToOld))
        XCTAssertTrue(HandshakeArm.restoresPeerInPlace(.stayFailedClosed(persistedProfileIsOld: true)))
        XCTAssertTrue(HandshakeArm.restoresPeerInPlace(.stayFailedClosed(persistedProfileIsOld: false)))
    }

    func testNoEventBothStopsTheTunnelAndRestoresAPeerInPlace() {
        // The two are contradictory by construction — the legacy path stops the
        // session it would be restoring a peer onto — so a policy edit that made
        // one event both is a bug in the edit, not a new state.
        for event in LiveRebuildEvent.allCases {
            let directive = LiveRebuildPolicy.directive(for: event)
            guard case .keepSession(let followUp) = directive else {
                XCTAssertTrue(directive.stopsTheTunnel)
                continue
            }
            if HandshakeArm.restoresPeerInPlace(followUp) {
                XCTAssertFalse(directive.stopsTheTunnel, "\(event)")
            }
        }
    }

    // MARK: - #351 (2) The teardown window is a state of its own

    func testTheTeardownWindowRefusesADialThatIsConnectingCannotSee() {
        // THE #351 item-2 FIX. `handleStatusChange` clears `isConnecting` on
        // `.disconnecting` and `.disconnected` — the very transitions
        // `awaitTeardown()` waits for — so on the branch code the ~5 s between
        // `vpnManager.disconnect()` and the redial read "idle" at every entry
        // point, and a second dial in it minted a second server-side peer.
        XCTAssertNil(TunnelDialGate.block(isConnecting: false, isRebuildingLive: false,
                                          isTearingDownForRedial: false))
        XCTAssertTrue(TunnelDialGate.mayStartDial(isConnecting: false, isRebuildingLive: false,
                                                  isTearingDownForRedial: false))
        XCTAssertEqual(TunnelDialGate.block(isConnecting: false, isRebuildingLive: false,
                                            isTearingDownForRedial: true),
                       .tearingDownForRedial)
        XCTAssertFalse(TunnelDialGate.mayStartDial(isConnecting: false, isRebuildingLive: false,
                                                   isTearingDownForRedial: true),
                       "the teardown window is exactly the state isConnecting cannot express")
    }

    func testEveryBlockingStateRefusesADialOnItsOwn() {
        // Enumerated, not spot-checked: a fourth blocking state cannot be added
        // without this switch failing to compile, and each state is asserted to
        // refuse on its own rather than only in combination with another.
        func flags(for block: TunnelDialGate.Block) -> (Bool, Bool, Bool) {
            switch block {
            case .dialInFlight:         return (true, false, false)
            case .liveRebuildInFlight:  return (false, true, false)
            case .tearingDownForRedial: return (false, false, true)
            }
        }
        for block in TunnelDialGate.Block.allCases {
            let (connecting, rebuilding, tearingDown) = flags(for: block)
            XCTAssertEqual(TunnelDialGate.block(isConnecting: connecting,
                                                isRebuildingLive: rebuilding,
                                                isTearingDownForRedial: tearingDown), block)
            XCTAssertFalse(TunnelDialGate.mayStartDial(isConnecting: connecting,
                                                       isRebuildingLive: rebuilding,
                                                       isTearingDownForRedial: tearingDown),
                           "\(block) must refuse a dial on its own")
        }
        XCTAssertEqual(TunnelDialGate.Block.allCases.count, 3,
                       "branch code had two states; the teardown window was the missing third")
    }

    func testTheDialEntryPointsAndTeardownSitesAreEnumerated() {
        // #336's remedy 2, applied to this policy: a list a sixth path cannot be
        // added to without editing a test, which is the moment to ask whether it
        // calls `TunnelDialGate`. A comment naming the other paths is
        // demonstrably not protection — the Quick Settings tile proved that.
        XCTAssertEqual(TunnelDialEntryPoint.allCases.map(\.rawValue), [
            "connect",
            "connectMultiHop",
            "reapplySettings",
            "selectServerLive",
            "autoConnectIfEnabled",
        ])
        // The twin: the guard has to cover BOTH places that stop a live tunnel
        // and then re-dial it. #350's review named only the multi-hop one.
        XCTAssertEqual(RedialTeardownSite.allCases.map(\.rawValue), [
            "connectMultiHopOverALiveSession",
            "legacyRebuild",
        ])
    }
}
