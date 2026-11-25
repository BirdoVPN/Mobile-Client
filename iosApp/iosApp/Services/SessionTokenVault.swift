import Foundation

/// #671 review (MR-2580): the session as this process sees it, and the ONE
/// lock every write of it takes.
///
/// What the keychain alone cannot do:
/// 1. One lock (`lock`, Android's `sessionLock`) around every token write and
///    delete: a sign-in's read, write and put-back; the refresh rotation's
///    compare-and-swap and write; a sign-out's wipe (`clear`: every
///    `LocalSignOut.wipe`, `logoutIfUnauthorized`'s and `InstallIdentity`'s
///    included); a number rotation's commit; the persist retry. So a rotation
///    can no longer write over a sign-in, re-create a pair a sign-out just
///    wiped, or have its spent token put back by a failed sign-in. The lock is
///    held around synchronous keychain calls only, never across an `await`.
/// 2. A refresh rotation's pair the keychain refused is kept in memory
///    (`pending`): this process keeps working on it (so its requests do not
///    401 into another rotation), and it is stored as soon as the keychain
///    takes it (`persistPending`: before each refresh, on foreground, on the
///    first unlock).
/// 3. When the keychain refused the new REFRESH token, the stored one is the
///    token the server just spent. Presented again after the server's 30 s
///    grace, it reads as theft, and every session and WireGuard peer of the
///    account is revoked. A durable fence (`SpentRefreshFence`) marks it,
///    written before anything returns: no refresh ever presents it, and a
///    relaunch that finds the fence with no newer pair signs out locally, with
///    no request. If the fence cannot be written either, the session is signed
///    out now (`.signedOut`). The fence clears only once a NEW refresh token
///    reads back from the keychain, or a sign-out verifiably removed the spent
///    one.
///
/// Residual, by design: a kill between the server's answer and the keychain
/// write still leaves the spent token stored and unfenced. The server's 30 s
/// grace exists for that window; nothing written before the request could
/// close it without turning every lost answer into a sign-out.
///
/// Only the host app refreshes; the PacketTunnel extension has no refresh
/// token and compiles none of this (SessionTokenVaultTests pins both).
final class SessionTokenVault: @unchecked Sendable {
    /// What a refresh rotation's write came to.
    enum RotationSave: Equatable, Sendable {
        /// Both tokens are stored.
        case stored
        /// The keychain refused the pair, or part of it. This process keeps
        /// working on it (`pending`), and it is stored as soon as the keychain
        /// takes it (`persistPending`).
        case keptInMemory
        /// The refresh token the rotation presented is no longer the current
        /// one (a sign-out, a sign-in, another rotation): nothing is written.
        case superseded
        /// #671 review P2: a session other than the one the rotation renewed
        /// is stored (a sign-in, another rotation). Nothing is written, and the
        /// rotated pair is never revoked: the server would end that device's
        /// whole lineage, the stored session with it.
        case replaced
        /// #671 review P2: the session the rotation renewed has ended (signed
        /// out: nothing stored, the stored token fenced, or the session
        /// generation moved). Nothing is written; the rotated pair is a live
        /// session nobody holds, so it is the late revoke's (#666).
        case ended
        /// The keychain refused the pair AND the fence could not be written:
        /// nothing durable would keep the spent refresh token from being
        /// presented after a relaunch, so the session is signed out now.
        case signedOut
    }

    private struct Pair {
        let access: Data
        let refresh: Data
    }

    /// What a sign-in writes besides the tokens, so the UI never names one
    /// account while requests act on another (#671 review, item 4).
    private static let identityItems: [HostKeychainItem] = [.userEmail, .signInMethod, .anonymousId]

    private let lock = NSLock()
    private let fence: any SpentRefreshFence
    /// The newest pair, while the keychain does not hold all of it.
    private var pending: Pair?
    /// The fence, as last read or written by this process (its only writer).
    private var fenced: Bool?

    init(fence: any SpentRefreshFence) {
        self.fence = fence
    }

    func accessToken(in store: any TokenPairStore) -> Data? {
        lock.withLock { pending?.access ?? store.hostLookup(.accessToken).data }
    }

    /// The refresh token a refresh may present: the pair kept in memory, or the
    /// stored one unless the fence marks it spent.
    func refreshToken(in store: any TokenPairStore) -> Data? {
        lock.withLock { currentRefresh(in: store).data }
    }

    /// A relaunch found the fence and no newer pair: the stored refresh token
    /// is spent, and the session is signed out locally.
    var storedRefreshTokenIsSpent: Bool {
        lock.withLock { pending == nil && isFenced }
    }

    /// A new session's pair (a sign-in, a number rotation), and its e-mail.
    ///
    /// The prior session's e-mail, sign-in method and anonymous number go
    /// FIRST, with its access token (`TokenPairWrite`), and come back if the
    /// sign-in fails, next to the prior refresh token only. The new e-mail is
    /// written LAST; `AuthViewModel` writes the method and the number after
    /// this. So no kill point pairs one account's items with another's tokens.
    func save(accessToken: Data, refreshToken: Data, email: Data?, in store: any TokenPairStore) -> Bool {
        lock.withLock {
            let priorRefresh = store.hostLookup(.refreshToken)
            var removed: [(HostKeychainItem, Data)] = []
            for item in Self.identityItems {
                guard case .found(let data) = store.hostLookup(item) else { continue }
                store.deleteHostData(item)
                removed.append((item, data))
                guard store.hostLookup(item) == .notFound else {
                    restore(removed, in: store)
                    return false
                }
            }
            guard TokenPairWrite.write(accessToken: accessToken, refreshToken: refreshToken,
                                       replacing: .anotherSession, in: store) == .stored,
                  store.hostLookup(.refreshToken) == .found(refreshToken) else {
                if store.hostLookup(.refreshToken) == priorRefresh { restore(removed, in: store) }
                return false
            }
            // The keychain holds the new session: whatever was kept in memory,
            // and the fence, belonged to the one it replaced.
            pending = nil
            unfence()
            if let email { store.replaceHostData(email, for: .userEmail) }
            return true
        }
    }

    /// A refresh rotation's pair, for the refresh token it presented. The
    /// compare-and-swap and the write are one step under the lock.
    func saveRotation(accessToken: Data, refreshToken: Data, expectingRefreshToken presented: Data,
                      sessionEnded: Bool = false, in store: any TokenPairStore) -> RotationSave {
        lock.withLock {
            switch currentRefresh(in: store) {
            case .found(let current) where current != presented:
                return .superseded
            case .notFound:
                // Signed out, or the fence says the stored token is spent.
                return .superseded
            case .found, .failed:
                // The presented token, or a keychain that cannot be read: the
                // presented token was spent either way, so the new pair is kept.
                return keep(Pair(access: accessToken, refresh: refreshToken), in: store)
            }
        }
    }

    /// Store a pair kept in memory. True when none is left to store.
    @discardableResult
    func persistPending(in store: any TokenPairStore) -> Bool {
        lock.withLock {
            guard let pair = pending else { return true }
            return keep(pair, in: store) == .stored
        }
    }

    /// Sign-out: both tokens go, and the fence with them once the spent
    /// refresh token is verifiably gone (a keychain that will not delete it
    /// keeps the fence).
    func clear(in store: any TokenPairStore) {
        lock.withLock {
            pending = nil
            store.deleteHostData(.accessToken)
            store.deleteHostData(.refreshToken)
            if store.hostLookup(.refreshToken) == .notFound { unfence() }
        }
    }

    // MARK: - Under the lock

    private func currentRefresh(in store: any TokenPairStore) -> KeychainLookup {
        if let pending { return .found(pending.refresh) }
        if isFenced { return .notFound }
        return store.hostLookup(.refreshToken)
    }

    private func keep(_ pair: Pair, in store: any TokenPairStore) -> RotationSave {
        let outcome = TokenPairWrite.write(accessToken: pair.access, refreshToken: pair.refresh,
                                           replacing: .consumedRefreshToken, in: store)
        // Only a NEW refresh token that reads back makes the stored one unspent.
        let refreshStored = outcome != .notStored && store.hostLookup(.refreshToken) == .found(pair.refresh)
        if refreshStored { unfence() }
        if outcome == .stored && refreshStored {
            pending = nil
            return .stored
        }
        pending = pair
        if refreshStored { return .keptInMemory }
        // The stored refresh token, if the delete left one, is the one the
        // server just spent: fenced before anything returns.
        if isFenced || fence.set() {
            fenced = true
            return .keptInMemory
        }
        // Nothing durable can fence it: signed out now. This process never
        // presents it (`fenced`), whatever the keychain kept.
        pending = nil
        fenced = true
        store.deleteHostData(.accessToken)
        store.deleteHostData(.refreshToken)
        return .signedOut
    }

    private func restore(_ removed: [(HostKeychainItem, Data)], in store: any TokenPairStore) {
        for (item, data) in removed { store.replaceHostData(data, for: item) }
    }

    private var isFenced: Bool {
        if let fenced { return fenced }
        let read = fence.isSet
        fenced = read
        return read
    }

    private func unfence() {
        if isFenced { fence.clear() }
        fenced = false
    }
}

/// #671 review: the durable mark that the stored refresh token was spent by a
/// rotation whose new pair the keychain refused.
protocol SpentRefreshFence: AnyObject {
    var isSet: Bool { get }
    /// False when the mark could not be written.
    @discardableResult func set() -> Bool
    func clear()
}

/// The fence as a file. Its whole content is `spent at <milliseconds since
/// 1970>`: no token, no hash of one, no account id.
///
/// Readable and writable BEFORE the first unlock after a boot, when the
/// keychain items (AfterFirstUnlock), UserDefaults and every file of the
/// default class (CompleteUntilFirstUserAuthentication) are not: the file and
/// its folder are created with `FileProtectionType.none` on iOS. macOS has no
/// such classes.
final class FileSpentRefreshFence: SpentRefreshFence, @unchecked Sendable {
    /// Writes the file: `Data.write(to:options:)`, or a recorder in the tests.
    typealias Writer = @Sendable (_ data: Data, _ url: URL, _ options: Data.WritingOptions) throws -> Void

    let url: URL
    private let writer: Writer

    init(directory: URL,
         writer: @escaping Writer = { data, url, options in try data.write(to: url, options: options) }) {
        url = directory.appendingPathComponent("spent-refresh-fence", isDirectory: false)
        self.writer = writer
    }

    /// `Application Support/BirdoVPN/spent-refresh-fence`, in the app's own
    /// container. Only the host app refreshes, so only it reads this.
    static func standard() -> FileSpentRefreshFence {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return FileSpentRefreshFence(directory: support.appendingPathComponent("BirdoVPN", isDirectory: true))
    }

    var isSet: Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    @discardableResult
    func set() -> Bool {
        let stamp = UInt64(max(0, Date().timeIntervalSince1970 * 1000))
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true,
                                                    attributes: Self.folderAttributes)
            try writer(Data("spent at \(stamp)\n".utf8), url, Self.writeOptions)
            return true
        } catch {
            return false
        }
    }

    func clear() {
        try? FileManager.default.removeItem(at: url)
    }

    #if os(iOS)
    static var writeOptions: Data.WritingOptions { [.atomic, .noFileProtection] }
    static var folderAttributes: [FileAttributeKey: Any]? { [.protectionKey: FileProtectionType.none] }
    #else
    static var writeOptions: Data.WritingOptions { [.atomic] }
    static var folderAttributes: [FileAttributeKey: Any]? { nil }
    #endif
}
