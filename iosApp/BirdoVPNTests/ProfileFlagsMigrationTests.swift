import XCTest

/// MR-2568 — `ProfileFlagsMigration`, the one-time rewrite of a VPN profile an
/// older build saved, driven against a fake of the system's VPN preferences.
///
/// Before: nothing touched the saved profile at launch. A profile saved before
/// MR-2340 kept `enforceRoutes = false` until an in-app connect or a
/// kill-switch toggle rewrote it, and a start from the Settings app or Control
/// Center uses the saved profile as it is.
final class ProfileFlagsMigrationTests: XCTestCase {

    // MARK: - Fakes

    /// A saved profile: the flags, plus the fields the migration must never
    /// touch (server config, On Demand, enabled state).
    private struct ProfileRecord: Equatable {
        var flags: KillSwitchProfileFlags
        var serverAddress = "198.51.100.7"
        var providerConfiguration = ["wg-config": "[Interface] fixture", "hb-key-id": "key-fixture"]
        var onDemandEnabled = true
        var onDemandRuleCount = 1
        var isEnabled = true
        var disconnectOnSleep = false
    }

    /// The profile one launch loaded: a copy of the system's, edited in place,
    /// field by field as the protocol object holds it. Every field write is
    /// recorded, even one that keeps the value, so a writer that touches
    /// anything but the six flags shows up in `written` (review of #664, L3).
    private final class LoadedProfile: SavedProfileFlags {
        static let flagFields: Set<String> = ["includeAllNetworks", "enforceRoutes", "excludeLocalNetworks",
                                              "excludeAPNs", "excludeDeviceCommunication",
                                              "excludeCellularServices"]
        private(set) var written: [String] = []

        var includeAllNetworks: Bool { didSet { written.append("includeAllNetworks") } }
        var enforceRoutes: Bool { didSet { written.append("enforceRoutes") } }
        var excludeLocalNetworks: Bool { didSet { written.append("excludeLocalNetworks") } }
        var excludeAPNs: Bool { didSet { written.append("excludeAPNs") } }
        var excludeDeviceCommunication: Bool { didSet { written.append("excludeDeviceCommunication") } }
        var excludeCellularServices: Bool { didSet { written.append("excludeCellularServices") } }
        var serverAddress: String { didSet { written.append("serverAddress") } }
        var providerConfiguration: [String: String] { didSet { written.append("providerConfiguration") } }
        var onDemandEnabled: Bool { didSet { written.append("onDemandEnabled") } }
        var onDemandRuleCount: Int { didSet { written.append("onDemandRuleCount") } }
        var isEnabled: Bool { didSet { written.append("isEnabled") } }
        var disconnectOnSleep: Bool { didSet { written.append("disconnectOnSleep") } }

        init(_ record: ProfileRecord) {
            includeAllNetworks = record.flags.includeAllNetworks
            enforceRoutes = record.flags.enforceRoutes
            excludeLocalNetworks = record.flags.excludeLocalNetworks
            excludeAPNs = record.flags.excludeAPNs
            excludeDeviceCommunication = record.flags.excludeDeviceCommunication
            excludeCellularServices = record.flags.excludeCellularServices
            serverAddress = record.serverAddress
            providerConfiguration = record.providerConfiguration
            onDemandEnabled = record.onDemandEnabled
            onDemandRuleCount = record.onDemandRuleCount
            isEnabled = record.isEnabled
            disconnectOnSleep = record.disconnectOnSleep
        }

        /// What a save hands the system: every field as it now stands.
        var record: ProfileRecord {
            ProfileRecord(flags: flags, serverAddress: serverAddress,
                          providerConfiguration: providerConfiguration,
                          onDemandEnabled: onDemandEnabled, onDemandRuleCount: onDemandRuleCount,
                          isEnabled: isEnabled, disconnectOnSleep: disconnectOnSleep)
        }

        /// Like the live `LoadedProfile`: the six flags, written one property
        /// at a time, as `applyKillSwitchFlags` does.
        var flags: KillSwitchProfileFlags {
            get {
                KillSwitchProfileFlags(includeAllNetworks: includeAllNetworks, enforceRoutes: enforceRoutes,
                                       excludeLocalNetworks: excludeLocalNetworks, excludeAPNs: excludeAPNs,
                                       excludeDeviceCommunication: excludeDeviceCommunication,
                                       excludeCellularServices: excludeCellularServices)
            }
            set {
                includeAllNetworks = newValue.includeAllNetworks
                enforceRoutes = newValue.enforceRoutes
                excludeLocalNetworks = newValue.excludeLocalNetworks
                excludeAPNs = newValue.excludeAPNs
                excludeDeviceCommunication = newValue.excludeDeviceCommunication
                excludeCellularServices = newValue.excludeCellularServices
            }
        }
    }

    /// The system's VPN preferences: `persisted` is what survives a relaunch,
    /// and only a successful save of the loaded profile changes it.
    private final class FakeProfileStore: SavedProfileStore {
        private(set) var persisted: ProfileRecord?
        private(set) var loaded: LoadedProfile?
        private(set) var saves = 0
        var saveSucceeds = true
        /// When set, saves wait for `completeHeldSave` instead of answering at once.
        var holdSaves = false
        private var held: [@Sendable (Bool) -> Void] = []

        init(persisted: ProfileRecord?) {
            self.persisted = persisted
            relaunch()
        }

        /// A new process loads the system's copy afresh.
        func relaunch() {
            loaded = persisted.map(LoadedProfile.init)
        }

        var profile: SavedProfileFlags? { loaded }

        func saveProfile(_ done: @escaping @Sendable (Bool) -> Void) {
            saves += 1
            if holdSaves {
                held.append(done)
            } else {
                finish(saveSucceeds, done)
            }
        }

        func completeHeldSave(_ succeeded: Bool) {
            guard !held.isEmpty else { return XCTFail("no save is waiting") }
            finish(succeeded, held.removeFirst())
        }

        private func finish(_ succeeded: Bool, _ done: @Sendable (Bool) -> Void) {
            if succeeded, let loaded { persisted = loaded.record }
            done(succeeded)
        }
    }

    private final class FakeMarker: ProfileFlagsMarker, @unchecked Sendable {
        private(set) var writes = 0
        var version: Int { didSet { writes += 1 } }
        init(version: Int = 0) { self.version = version }
    }

    private final class Outcomes: @unchecked Sendable {
        var all: [ProfileFlagsMigration.Outcome] = []
    }

    /// One launch: the migration runs once with what the store loaded.
    @discardableResult
    private func launch(_ store: FakeProfileStore, _ marker: FakeMarker) -> [ProfileFlagsMigration.Outcome] {
        let outcomes = Outcomes()
        ProfileFlagsMigration.run(store: store, marker: marker) { outcomes.all.append($0) }
        return outcomes.all
    }

    /// What builds before MR-2340 wrote: `enforceRoutes` followed the kill
    /// switch, `excludeLocalNetworks` followed Local Network Sharing.
    private func oldFlags(killSwitch: Bool, sharing: Bool) -> KillSwitchProfileFlags {
        KillSwitchProfileFlags(includeAllNetworks: killSwitch, enforceRoutes: killSwitch,
                               excludeLocalNetworks: sharing, excludeAPNs: !killSwitch,
                               excludeDeviceCommunication: !killSwitch, excludeCellularServices: true)
    }

    // MARK: - The rewrite

    func testAnOldFlagProfileIsRewrittenOnce() {
        let store = FakeProfileStore(persisted: ProfileRecord(flags: oldFlags(killSwitch: false, sharing: false)))
        let marker = FakeMarker()

        XCTAssertEqual(launch(store, marker), [.rewritten])
        XCTAssertEqual(store.saves, 1)
        XCTAssertEqual(store.persisted?.flags,
                       KillSwitchProfileFlags.forProfile(killSwitch: false, localNetworkSharing: false))
        XCTAssertEqual(store.persisted?.flags.enforceRoutes, true, "TunnelVision: routes enforced with the kill switch off")
        XCTAssertEqual(marker.version, ProfileFlagsMigration.currentVersion)
    }

    /// Every kill switch x Sharing state an older build could have saved comes
    /// out as `forProfile` decides for THAT state. With the kill switch on the
    /// old flags were already current: marked, no save.
    func testEveryOldStateComesOutAsTheCurrentDecisionForThatState() {
        for killSwitch in [false, true] {
            for sharing in [false, true] {
                let store = FakeProfileStore(persisted: ProfileRecord(flags: oldFlags(killSwitch: killSwitch, sharing: sharing)))
                let marker = FakeMarker()
                let label = "killSwitch=\(killSwitch) sharing=\(sharing)"

                XCTAssertEqual(launch(store, marker), [killSwitch ? .alreadyCurrent : .rewritten], label)
                XCTAssertEqual(store.saves, killSwitch ? 0 : 1, label)
                XCTAssertEqual(store.persisted?.flags,
                               .forProfile(killSwitch: killSwitch, localNetworkSharing: sharing), label)
                XCTAssertEqual(marker.version, ProfileFlagsMigration.currentVersion, label)
            }
        }
    }

    /// A profile from before MR-1728 (kill switch on, Apple's default
    /// exemptions) gets the whole current decision, not only enforceRoutes.
    func testTheRewriteGoesThroughTheOneDecision() {
        var preMR1728 = oldFlags(killSwitch: true, sharing: false)
        preMR1728.excludeAPNs = true
        preMR1728.excludeDeviceCommunication = true
        let store = FakeProfileStore(persisted: ProfileRecord(flags: preMR1728))

        XCTAssertEqual(launch(store, FakeMarker()), [.rewritten])
        XCTAssertEqual(store.persisted?.flags, .forProfile(killSwitch: true, localNetworkSharing: false))
    }

    // MARK: - The marker

    func testTheMarkerIsWrittenOnlyAfterTheSaveSucceeds() {
        let store = FakeProfileStore(persisted: ProfileRecord(flags: oldFlags(killSwitch: false, sharing: true)))
        store.holdSaves = true
        let marker = FakeMarker()

        XCTAssertEqual(launch(store, marker), [], "nothing is reported before the save answers")
        XCTAssertEqual(store.saves, 1)
        XCTAssertEqual(marker.version, 0, "the marker must wait for the save")
        XCTAssertEqual(marker.writes, 0)

        store.completeHeldSave(true)
        XCTAssertEqual(marker.version, ProfileFlagsMigration.currentVersion)
        XCTAssertEqual(marker.writes, 1)
    }

    func testAFailedSaveLeavesTheMarkerAndTheNextLaunchRetries() {
        let old = oldFlags(killSwitch: false, sharing: false)
        let store = FakeProfileStore(persisted: ProfileRecord(flags: old))
        store.saveSucceeds = false
        let marker = FakeMarker()

        XCTAssertEqual(launch(store, marker), [.saveFailed])
        XCTAssertEqual(store.saves, 1, "one try per launch, never a loop")
        XCTAssertEqual(marker.version, 0)
        XCTAssertEqual(marker.writes, 0)
        XCTAssertEqual(store.persisted?.flags, old, "the system kept the old profile")

        store.saveSucceeds = true
        store.relaunch()
        XCTAssertEqual(launch(store, marker), [.rewritten])
        XCTAssertEqual(store.saves, 2)
        XCTAssertEqual(marker.version, ProfileFlagsMigration.currentVersion)
    }

    func testTheSecondLaunchIsANoOp() {
        let store = FakeProfileStore(persisted: ProfileRecord(flags: oldFlags(killSwitch: false, sharing: false)))
        let marker = FakeMarker()
        launch(store, marker)
        let afterFirst = store.persisted

        store.relaunch()
        XCTAssertEqual(launch(store, marker), [.notNeeded])
        XCTAssertEqual(store.saves, 1, "no second save")
        XCTAssertEqual(marker.writes, 1, "no second marker write")
        XCTAssertEqual(store.persisted, afterFirst)
    }

    /// A profile this build's `connect()` saved already carries the flags:
    /// marked as current without a save.
    func testACurrentProfileIsMarkedWithoutASave() {
        let current = KillSwitchProfileFlags.forProfile(killSwitch: false, localNetworkSharing: true)
        let store = FakeProfileStore(persisted: ProfileRecord(flags: current))
        let marker = FakeMarker()

        XCTAssertEqual(launch(store, marker), [.alreadyCurrent])
        XCTAssertEqual(store.saves, 0)
        XCTAssertEqual(marker.version, ProfileFlagsMigration.currentVersion)
    }

    // MARK: - What it must never do

    func testNoProfileCreatesNothing() {
        let store = FakeProfileStore(persisted: nil)
        let marker = FakeMarker()

        XCTAssertEqual(launch(store, marker), [.noProfile])
        XCTAssertEqual(store.saves, 0, "a save with no profile would create one (and the VPN prompt)")
        XCTAssertNil(store.persisted)
        XCTAssertEqual(marker.writes, 0, "the next launch looks again")

        store.relaunch()
        XCTAssertEqual(launch(store, marker), [.noProfile])
        XCTAssertEqual(store.saves, 0)
    }

    /// Server config, On Demand and the enabled state come through untouched,
    /// and so do the profile's own kill switch and Sharing state: the decision
    /// is made from the profile, never from the current settings.
    func testEveryOtherFieldIsPreserved() {
        for killSwitch in [false, true] {
            for sharing in [false, true] {
                var original = ProfileRecord(flags: oldFlags(killSwitch: killSwitch, sharing: sharing))
                original.serverAddress = "203.0.113.44"
                original.providerConfiguration = ["wg-config": "[Interface] other fixture", "wg-private-key-ref": "wg_private_key"]
                original.onDemandEnabled = killSwitch
                original.onDemandRuleCount = killSwitch ? 1 : 0
                original.isEnabled = !sharing
                original.disconnectOnSleep = false
                // A profile whose Apple-default exemptions predate MR-1728, so
                // every row is rewritten and every row's other fields are checked.
                original.flags.excludeAPNs = true
                let store = FakeProfileStore(persisted: original)

                launch(store, FakeMarker())

                let label = "killSwitch=\(killSwitch) sharing=\(sharing)"
                var expected = original
                expected.flags = .forProfile(killSwitch: killSwitch, localNetworkSharing: sharing)
                XCTAssertEqual(store.persisted, expected, label)
                // Every write, not only every changed value: only the six flags.
                let written = Set(store.loaded?.written ?? [])
                XCTAssertFalse(written.isEmpty, "the fake recorded no write at all: \(label)")
                XCTAssertTrue(written.isSubset(of: LoadedProfile.flagFields),
                              "wrote \(written.subtracting(LoadedProfile.flagFields).sorted()): \(label)")
                XCTAssertEqual(store.persisted?.flags.includeAllNetworks, killSwitch, "kill switch kept: \(label)")
                XCTAssertEqual(store.persisted?.flags.excludeLocalNetworks, sharing, "Sharing kept: \(label)")
            }
        }
    }

    // MARK: - The live marker

    func testTheDefaultsMarkerRoundTrips() throws {
        let suite = "ProfileFlagsMigrationTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let marker = DefaultsProfileFlagsMarker(defaults: defaults)

        XCTAssertEqual(marker.version, 0, "a fresh install reads 0")
        marker.version = ProfileFlagsMigration.currentVersion
        XCTAssertEqual(defaults.integer(forKey: "vpn_profile_flags_version"), ProfileFlagsMigration.currentVersion)
        XCTAssertEqual(DefaultsProfileFlagsMarker(defaults: defaults).version, ProfileFlagsMigration.currentVersion)
    }

    // MARK: - Where it is wired (source pins: VPNManager cannot compile here)

    private func source(_ path: String) throws -> String {
        // .../iosApp/BirdoVPNTests/ProfileFlagsMigrationTests.swift -> .../iosApp
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let url = root.appendingPathComponent(path)
        guard FileManager.default.fileExists(atPath: url.path) else {
            XCTFail("\(path) not found under \(root.path): this pin would be vacuous")
            return ""
        }
        return try String(contentsOf: url, encoding: .utf8).replacingOccurrences(of: "\r\n", with: "\n")
    }

    private func slice(_ text: String, from start: String, to end: String,
                       file: StaticString = #filePath, line: UInt = #line) -> String {
        guard let a = text.range(of: start),
              let b = text.range(of: end, range: a.upperBound..<text.endIndex) else {
            XCTFail("not found: \(start) … \(end)", file: file, line: line)
            return ""
        }
        return String(text[a.lowerBound..<b.upperBound])
    }

    /// The launch hook: `loadManager()` (once per process, iOS and macOS)
    /// hands the manager it loaded, and it is the only caller.
    func testTheMigrationRunsFromLoadManagerOnTheLoadedManager() throws {
        let manager = try source("iosApp/Services/VPNManager.swift")
        let load = slice(manager, from: "private func loadManager() {", to: "\n    }\n")
        XCTAssertTrue(load.contains("self.migrateSavedProfileFlags(managers?.first)"))
        XCTAssertEqual(manager.components(separatedBy: "migrateSavedProfileFlags(").count - 1, 2,
                       "one definition and one call: no other path runs it")
    }

    /// The live store never makes a profile, never touches the tunnel, never
    /// reads the settings, and writes flags only through `applyKillSwitchFlags`.
    func testTheLiveStoreOnlyRewritesFlagsOnTheLoadedProfile() throws {
        let manager = try source("iosApp/Services/VPNManager.swift")
        let section = slice(manager, from: "// MARK: - Saved-profile flags (MR-2568)",
                            to: "// END saved-profile flags (MR-2568)")
        XCTAssertTrue(section.contains("ProfileFlagsMigration.run(store: LoadedProfileStore(mgr),"))
        XCTAssertTrue(section.contains("VPNManager.applyKillSwitchFlags(newValue, to: proto)"))
        XCTAssertTrue(section.contains("manager.saveToPreferences"))
        for forbidden in ["NETunnelProviderManager()", "NETunnelProviderProtocol()", "startVPNTunnel",
                          "stopVPNTunnel", "loadAllFromPreferences", "removeFromPreferences",
                          "UserDefaults.standard.bool", "\"kill_switch\"", "\"local_network_sharing\"",
                          "onDemandRules =", "isOnDemandEnabled =", "isEnabled =", "providerConfiguration",
                          "serverAddress =", "localizedDescription"] {
            XCTAssertFalse(section.contains(forbidden), "the MR-2568 section must not use \(forbidden)")
        }
    }
}
