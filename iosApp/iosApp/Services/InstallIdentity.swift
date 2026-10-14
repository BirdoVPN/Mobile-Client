import Foundation

/// MR-1726 (owner decision 2026-10-13: yes) — the device identity is PER
/// INSTALL on iOS and macOS, as it is on Android (`DeviceInfoProvider`, MR-969 /
/// MR-998: a random id in app-private storage with `allowBackup=false`, so it
/// dies with an uninstall).
///
/// The Apple apps keep `device_id` and `anonymous_id` in the keychain, and
/// keychain items OUTLIVE the app bundle: deleting the app and installing it
/// again handed the new install the old install's device id. The backend keys
/// its `(userId, deviceId)` device rows and the anonymous-registration limit on
/// that value, so every account ever created on the phone — across sign-outs,
/// deletions of the app and fresh starts — arrived carrying the same id and was
/// trivially joinable server-side. That is the linkage Android removed.
///
/// UserDefaults, unlike the keychain, IS deleted with the app on iOS. So a
/// sentinel kept there tells a launch of a fresh install from any later
/// launch: when it is absent, whatever the keychain still holds was left by a
/// previous install and is discarded — the session is signed out (owner,
/// 2026-10-14: "sign out on reinstall", as Android's reinstall does), a new
/// random device id is minted and the previous install's anonymous account
/// number is dropped — and then the sentinel is set.
///
/// UPGRADES ARE NOT FRESH INSTALLS. The first build carrying this check also
/// finds no sentinel on every existing install. Those must stay signed in and
/// keep their id (Android's migration rule: nobody's device row changes on
/// upgrade), so the consent record — which every build since the guest shell
/// writes on the first screen a new install shows, and which an earlier launch
/// of THIS install is the only possible author of — marks the install as
/// already run. Unless a fresh install's clean-up is still PENDING: it marks
/// itself before it starts and unmarks itself only when it is done, because
/// the user can answer the consent screen in a launch whose clean-up failed,
/// and the next launch would then read that consent as an upgrade and keep the
/// previous install's session and identity for good.
///
/// Sign-out does NOT go through here and does not change the device id, as on
/// Android (`BirdoRepository.logout` keeps it; only account deletion rotates
/// it, `APIClient.resetDeviceIdentity`). What a sign-out does drop is
/// `LocalSignOut.wipe` below.
///
/// KNOWN LIMITS, accepted with the decision:
/// - a reinstall registers as a NEW device server-side; the old row stays until
///   the backend reaps it, which on a plan at its device cap can read as
///   "device limit reached" until then (Android's accepted cost, the same);
/// - macOS does not delete an app's container when the app is dragged to the
///   Bin, so the sentinel usually survives there and a Mac reinstall keeps its
///   identity (and session); a fresh macOS user account or a removed container
///   resets it.
///
/// Foundation-only on purpose: the BirdoVPNTests bundle compiles it with an
/// in-memory store, so the fresh-install / upgrade / already-run decision is
/// pinned without a keychain.
enum InstallIdentity {
    /// The UserDefaults key of the first-run sentinel.
    static let sentinelKey = "install_identity_checked_v1"

    /// Set while a fresh install's clean-up has started and not finished; the
    /// consent record proves nothing while it is set (see the type doc).
    static let pendingKey = "install_identity_pending_v1"

    /// UserDefaults keys only an earlier launch of THIS install can have
    /// written: the consent record (accepted or "Not now") that the first
    /// screen of every build since the guest shell asks for before anything
    /// else. A signed-in user of an older build always has one — the old
    /// builds wrote `gdpr_consented` for every signed-in session.
    static var earlierLaunchKeys: [String] {
        [ConsentRecord.acceptedKey, ConsentRecord.versionKey, ConsentRecord.deferredKey]
    }

    enum Outcome: Equatable, Sendable {
        /// The sentinel was already set: a later launch. Nothing changes.
        case alreadyChecked
        /// No sentinel, but this install has run before (an upgrade from a
        /// build without the check): session and identity kept, sentinel set.
        case upgradeKeptIdentity
        /// A fresh install with nothing left over: sentinel set.
        case freshInstall
        /// A fresh install that found a previous install's session or identity
        /// in the keychain: signed out, identity replaced, sentinel set.
        case discardedPreviousInstall
        /// A fresh install's clean-up started but could not finish (the session
        /// would not clear, or a keychain write failed). The pending flag stays
        /// set, so every later launch finishes it — consent or not. The stored
        /// session belongs to the previous install: do not run signed in on it.
        case cleanupIncomplete
        /// Nothing could be read this launch (protected data not yet
        /// available): nothing changed, the next launch decides.
        case retryNextLaunch

        /// The session in the keychain must not be used this launch.
        var storedSessionUnusable: Bool { self == .cleanupIncomplete }
    }

    /// Run once per launch, before anything reads the session or the device
    /// identity.
    ///
    /// - Parameters:
    ///   - protectedDataAvailable: false before the first unlock after a boot,
    ///     when neither UserDefaults nor the keychain can be trusted to read
    ///     back what they hold — an absent sentinel then proves nothing.
    ///   - mintDeviceId: the fresh random id to store in place of a previous
    ///     install's (`InstallIdentity.mintDeviceId` in the app).
    @discardableResult
    static func checkOnLaunch(defaults: UserDefaults,
                              store: any InstallIdentityStore,
                              protectedDataAvailable: Bool,
                              mintDeviceId: () -> String) -> Outcome {
        guard protectedDataAvailable else { return .retryNextLaunch }
        if defaults.bool(forKey: sentinelKey) { return .alreadyChecked }
        let pending = defaults.bool(forKey: pendingKey)
        if !pending, earlierLaunchKeys.contains(where: { defaults.object(forKey: $0) != nil }) {
            defaults.set(true, forKey: sentinelKey)
            return .upgradeKeptIdentity
        }
        let leftoverSession = store.hasSession
        let leftoverDevice = store.deviceId != nil
        let leftoverAnonymous = store.anonymousId != nil
        if !leftoverSession && !leftoverDevice && !leftoverAnonymous {
            // Nothing (left) to discard; APIClient mints the id on first use.
            defaults.set(true, forKey: sentinelKey)
            defaults.removeObject(forKey: pendingKey)
            return .freshInstall
        }
        // Marked BEFORE anything is touched, cleared only when all of it is
        // done: a clean-up that stops part-way must be finished by a later
        // launch, even one that by then finds a consent record.
        defaults.set(true, forKey: pendingKey)
        if leftoverSession {
            // FIRST, before the identity: if the sign-out cannot be completed,
            // nothing else changes — a live session must never be left running
            // under a new device id.
            store.clearSession()
            guard !store.hasSession else { return .cleanupIncomplete }
        }
        if leftoverDevice {
            // The keychain write is delete-then-add, so a failed write leaves
            // NO device id rather than the old one: APIClient would mint a
            // fresh one on first use, which is safe. The rest of the clean-up
            // still has to run, so a failure stops here and a later launch
            // finishes it.
            guard store.saveDeviceId(mintDeviceId()) else { return .cleanupIncomplete }
        }
        if leftoverAnonymous {
            store.clearAnonymousId()
            guard store.anonymousId == nil else { return .cleanupIncomplete }
        }
        defaults.set(true, forKey: sentinelKey)
        defaults.removeObject(forKey: pendingKey)
        return .discardedPreviousInstall
    }

    /// A fresh random device id: `UUID()` is backed by the system CSPRNG, so the
    /// value is a v4 UUID rather than a hash of anything and carries no
    /// information about the handset or the user. Mirrors Android's
    /// `DeviceInfoProvider.mintDeviceId`.
    static func mintDeviceId() -> String {
        "ios_" + UUID().uuidString.replacingOccurrences(of: "-", with: "")
    }
}

/// MR-1726 follow-up — what a sign-out removes from this device, as on
/// Android, where `TokenManager.clearAll()` drops the tokens AND the pending
/// anonymous id. iOS used to keep `anonymous_id` after a sign-out ("so a user
/// who never wrote it down can still sign back in"), but no signed-out screen
/// reads it, and the anonymous-create path could fall back to it and show a
/// PREVIOUS account's number as the new one. The device id stays: only a
/// deletion or a fresh install replaces it.
enum LocalSignOut {
    static func wipe(_ store: any InstallIdentityStore) {
        store.clearSession()
        store.clearAnonymousId()
    }
}

/// #485 review — the anonymous account number stored on this device belongs
/// to the session signed in on it, and to nothing else: never a previous
/// account's number kept across a sign-out, an expired session or a sign-in
/// that did not say what the new number is.
enum AnonymousNumberRecord {
    /// After a sign-in's tokens are stored. An anonymous sign-in records the
    /// number the server or the user gave for THIS account; given none, it
    /// records nothing rather than keep an older one, which would then be
    /// shown as this account's (`/auth/me` fills it in, `recordProfile`). Any
    /// other sign-in drops it.
    static func recordSignIn(anonymous: Bool, number: String?, in store: any InstallIdentityStore) {
        if anonymous, let number {
            store.saveAnonymousId(number)
        } else {
            store.clearAnonymousId()
        }
    }

    /// After `GET /auth/me`: the profile's number replaces the stored one, and
    /// an account the profile says is not anonymous keeps none. An anonymous
    /// profile without a number leaves the one this sign-in recorded.
    static func recordProfile(isAnonymous: Bool, number: String?, in store: any InstallIdentityStore) {
        guard isAnonymous else {
            store.clearAnonymousId()
            return
        }
        if let number { store.saveAnonymousId(number) }
    }

    /// The UserDefaults flag of `purgeStaleOnLaunch`.
    static let stalePurgeDoneKey = "stale_anonymous_id_purged_v1"

    enum PurgeOutcome: Equatable, Sendable {
        /// Done on an earlier launch.
        case alreadyDone
        /// Signed in: the stored number is this session's, and its sign-out
        /// wipes it (`LocalSignOut`). The purge waits for a signed-out launch.
        case signedIn
        /// Signed out with no number stored: nothing to purge; done.
        case nothingStored
        /// Signed out with a number stored: removed; done.
        case purged
        /// Nothing could be read, or the removal did not stick: next launch.
        case retryNextLaunch
    }

    /// One-time, at launch. Builds before the sign-out wipe kept
    /// `anonymous_id` after a sign-out, and an expired session's clean-up
    /// kept it as well, so a device signed out on one of those builds still
    /// holds a previous account's number. Only ever removed while SIGNED OUT,
    /// when no screen reads it.
    ///
    /// - Parameter protectedDataAvailable: false before the first unlock after
    ///   a boot, when an absent number proves nothing (see `InstallIdentity`).
    @discardableResult
    static func purgeStaleOnLaunch(defaults: UserDefaults,
                                   store: any InstallIdentityStore,
                                   signedIn: Bool,
                                   protectedDataAvailable: Bool) -> PurgeOutcome {
        guard protectedDataAvailable else { return .retryNextLaunch }
        if defaults.bool(forKey: stalePurgeDoneKey) { return .alreadyDone }
        guard !signedIn else { return .signedIn }
        var outcome = PurgeOutcome.nothingStored
        if store.anonymousId != nil {
            store.clearAnonymousId()
            guard store.anonymousId == nil else { return .retryNextLaunch }
            outcome = .purged
        }
        defaults.set(true, forKey: stalePurgeDoneKey)
        return outcome
    }
}

/// The keychain items the check and the sign-out read and replace.
/// `KeychainService` conforms; the tests use an in-memory store.
protocol InstallIdentityStore: AnyObject {
    var deviceId: String? { get }
    var anonymousId: String? { get }
    /// Either session token is stored.
    var hasSession: Bool { get }
    @discardableResult func saveDeviceId(_ id: String) -> Bool
    @discardableResult func saveAnonymousId(_ id: String) -> Bool
    func clearAnonymousId()
    /// Drop the session: tokens and everything stored with them.
    func clearSession()
}
