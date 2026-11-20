import Foundation

/// MR-2568 — bring the VPN profile an older build saved up to the current
/// `KillSwitchProfileFlags` decision, ONCE, on the first launch after an
/// update.
///
/// MR-2340 made `enforceRoutes` true in every kill-switch state. The flags are
/// written into the system's saved profile only by an in-app connect, a
/// kill-switch toggle or the circuit breaker's fail-open, so a profile saved by
/// an older build kept `enforceRoutes = false` until one of those happened.
/// Meanwhile a start from the Settings app or Control Center uses the SAVED
/// profile as it is. The profiles that gain `enforceRoutes` are the kill-switch
/// OFF ones, which never have On Demand armed (`applyKillSwitchFlag` arms it
/// only with the kill switch on). So those users stayed open to TunnelVision
/// (a more specific local or DHCP option 121 route beating the tunnel), and
/// the Local Network Sharing value saved in their profile was not delivered.
///
/// The rules, each pinned by ProfileFlagsMigrationTests:
///   - Only an EXISTING profile is rewritten. `SavedProfileStore` has no way to
///     create one, so the migration can never raise the system's "add VPN
///     configurations" prompt. No profile: nothing happens, and the next
///     launch looks again (a profile made later by `connect()` already carries
///     the current flags).
///   - Flags only, decided by `KillSwitchProfileFlags.forProfile` from the
///     profile's OWN kill-switch state (`includeAllNetworks`) and Local Network
///     Sharing (`excludeLocalNetworks`), never from the current settings. A
///     profile the circuit breaker failed open keeps its kill switch off until
///     the next connect re-reads the setting, as before. The server config,
///     On Demand and every other field are left alone.
///   - Nothing starts, stops or reconnects a tunnel. A connected profile is
///     saved like any other, and the system applies the flags from the next
///     tunnel start (see `VPNManager.migrateSavedProfileFlags`).
///   - The marker is written only after the save SUCCEEDED, or when the profile
///     already carries the current flags. A failed save is retried at the next
///     launch, never in a loop: the migration runs once per process, from
///     `VPNManager.loadManager()`.
///
/// Foundation-only on purpose: ProfileFlagsMigrationTests drives it with a
/// fake store. The live store (NetworkExtension) is in VPNManager.swift,
/// which cannot be compiled into BirdoVPNTests.
enum ProfileFlagsMigration {
    /// The flag decision saved profiles must carry. Bump it when
    /// `KillSwitchProfileFlags.forProfile` changes in a way existing profiles
    /// must catch up with. 1: MR-2340, `enforceRoutes` in every state.
    static let currentVersion = 1

    /// UserDefaults key of the version this install's saved profile was last
    /// brought up to.
    static let markerKey = "vpn_profile_flags_version"

    enum Outcome: Equatable, Sendable {
        /// The marker is current: the migration already ran on this install.
        case notNeeded
        /// No saved profile. Nothing created, marker untouched.
        case noProfile
        /// The profile already carried the current flags: marker written, no save.
        case alreadyCurrent
        /// Flags rewritten and saved; marker written after the save.
        case rewritten
        /// The save failed: marker untouched, so the next launch tries again.
        case saveFailed
    }

    /// The flags `profile` should carry: its own kill-switch and Local Network
    /// Sharing state, through the one decision every writer uses.
    static func target(for saved: KillSwitchProfileFlags) -> KillSwitchProfileFlags {
        .forProfile(killSwitch: saved.includeAllNetworks,
                    localNetworkSharing: saved.excludeLocalNetworks)
    }

    /// Run once per launch, with the profile the system's preferences returned.
    /// `done` is called exactly once, with what happened.
    static func run(store: SavedProfileStore,
                    marker: ProfileFlagsMarker,
                    done: @escaping @Sendable (Outcome) -> Void) {
        guard marker.version < currentVersion else { return done(.notNeeded) }
        guard let profile = store.profile else { return done(.noProfile) }
        let wanted = Self.target(for: profile.flags)
        guard profile.flags != wanted else {
            marker.version = currentVersion
            return done(.alreadyCurrent)
        }
        profile.flags = wanted
        store.saveProfile { saved in
            if saved { marker.version = currentVersion }
            done(saved ? .rewritten : .saveFailed)
        }
    }
}

/// The saved profile as the migration may touch it: its flag set, nothing else.
protocol SavedProfileFlags: AnyObject {
    var flags: KillSwitchProfileFlags { get set }
}

/// What the migration needs from the system's VPN preferences: the profile
/// they returned (nil when none is saved) and a save of that same profile.
/// Deliberately no way to create a profile.
protocol SavedProfileStore: AnyObject {
    var profile: SavedProfileFlags? { get }
    /// Save `profile` as edited. `done(true)` only when the system accepted it.
    func saveProfile(_ done: @escaping @Sendable (Bool) -> Void)
}

/// Where the migration records the version the saved profile was brought up to.
protocol ProfileFlagsMarker: AnyObject, Sendable {
    var version: Int { get set }
}

/// The live marker: the host app's standard UserDefaults. Only the host app
/// writes the profile's flags, so the tunnel extension never needs to read it.
final class DefaultsProfileFlagsMarker: ProfileFlagsMarker, @unchecked Sendable {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var version: Int {
        get { defaults.integer(forKey: ProfileFlagsMigration.markerKey) }
        set { defaults.set(newValue, forKey: ProfileFlagsMigration.markerKey) }
    }
}
