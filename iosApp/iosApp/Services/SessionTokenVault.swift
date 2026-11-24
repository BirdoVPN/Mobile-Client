import Foundation

/// #671 review (MR-2580): the session's token pair as this process sees it.
///
/// Spec-first: today's behaviour behind the new API (the keychain alone, no
/// lock, no pair kept in memory, no fence), so SessionTokenVaultTests can show
/// it failing.
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
    }

    private let fence: any SpentRefreshFence

    init(fence: any SpentRefreshFence) {
        self.fence = fence
    }

    func accessToken(in store: any TokenPairStore) -> Data? {
        store.hostLookup(.accessToken).data
    }

    /// The refresh token a refresh may present.
    func refreshToken(in store: any TokenPairStore) -> Data? {
        store.hostLookup(.refreshToken).data
    }

    /// A relaunch found the fence and no newer pair: the stored refresh token
    /// is spent, and the session is signed out locally.
    var storedRefreshTokenIsSpent: Bool { false }

    /// A new session's pair (a sign-in, a number rotation).
    func save(accessToken: Data, refreshToken: Data, in store: any TokenPairStore) -> Bool {
        TokenPairWrite.save(accessToken: accessToken, refreshToken: refreshToken,
                            replacing: .anotherSession, in: store)
    }

    /// A refresh rotation's pair, for the refresh token it presented.
    func saveRotation(accessToken: Data, refreshToken: Data, expectingRefreshToken presented: Data,
                      in store: any TokenPairStore) -> RotationSave {
        TokenPairWrite.save(accessToken: accessToken, refreshToken: refreshToken,
                            replacing: .consumedRefreshToken, in: store) ? .stored : .keptInMemory
    }

    /// Store a pair kept in memory. True when none is left to store.
    @discardableResult
    func persistPending(in store: any TokenPairStore) -> Bool {
        true
    }

    /// Sign-out: both tokens go.
    func clear(in store: any TokenPairStore) {
        store.deleteHostData(.accessToken)
        store.deleteHostData(.refreshToken)
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
    let url: URL

    init(directory: URL) {
        url = directory.appendingPathComponent("spent-refresh-fence", isDirectory: false)
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
            try Data("spent at \(stamp)\n".utf8).write(to: url, options: Self.writeOptions)
            return true
        } catch {
            return false
        }
    }

    func clear() {
        try? FileManager.default.removeItem(at: url)
    }

    #if os(iOS)
    private static var writeOptions: Data.WritingOptions { [.atomic, .noFileProtection] }
    private static var folderAttributes: [FileAttributeKey: Any]? { [.protectionKey: FileProtectionType.none] }
    #else
    private static var writeOptions: Data.WritingOptions { [.atomic] }
    private static var folderAttributes: [FileAttributeKey: Any]? { nil }
    #endif
}
