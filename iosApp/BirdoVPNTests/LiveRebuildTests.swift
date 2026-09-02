import XCTest

/// Mobile-Client #159 — the pure half of the in-place live rebuild
/// (`LiveRebuild.swift`), compiled directly into this un-hosted bundle (no app
/// host, no KMP framework, no WireGuardKit).
///
/// These pin the three decisions that decide whether a live, kill-switched
/// session is ever stopped. The bug they guard needs a second node and a peer
/// removed by hand on the first to observe, so nobody re-runs that by hand.
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

    func testOnlyTheTwoServerCannotDeferEventsMayStopTheTunnel() {
        for event in LiveRebuildEvent.allCases {
            let directive = LiveRebuildPolicy.directive(for: event)
            let mayStop = LiveRebuildPolicy.eventsThatMayStopTheTunnel.contains(event)
            XCTAssertEqual(directive.stopsTheTunnel, mayStop,
                           "\(event) must \(mayStop ? "" : "NOT ")reach the disconnect-first path")
        }
        XCTAssertEqual(LiveRebuildPolicy.eventsThatMayStopTheTunnel,
                       [.serverDoesNotKnowRebuild, .deferralNotHonoured])
    }

    func testEveryFailureKeepsTheSessionAndReleasesOnlyWhatWasMinted() {
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .configRequestFailed), .keepSession(.nothing))
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .routeNotConfirmed), .keepSession(.releaseNewKey))
        XCTAssertEqual(LiveRebuildPolicy.directive(for: .swapFailed), .keepSession(.releaseNewKey))
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
}
