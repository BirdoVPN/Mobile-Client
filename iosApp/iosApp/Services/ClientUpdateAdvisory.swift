import Foundation

/// `clientUpdate` on a SUCCESSFUL connect — the version floor, warn-only.
///
/// iOS is the ONLY platform that ever receives this. Android and desktop are
/// hard-blocked below their floor and get a structured 426 instead; iOS is
/// deliberately `warn` because an App Review queue sits between a fix and the
/// user and there is no sideload escape hatch, so a hard floor could strand a
/// paying customer with no path forward (see the backend's
/// `versionFloorEnforcement`).
///
/// OPEN-WORK H3: the backend has been attaching this to every below-floor iOS
/// connect and NO client read it — not iOS, not Android, not desktop. The
/// warn-only floor therefore warned nobody: a user on an unsupported build
/// connected normally and was told nothing at all.
struct ClientUpdateAdvisory: Decodable, Equatable {
    /// False for iOS by construction. Decoded rather than assumed so that if
    /// the floor is ever tightened server-side, the client already carries the
    /// flag instead of needing a release to learn about it.
    let required: Bool
    let minVersion: String
    let currentVersion: String
    let updateUrl: String
    let message: String

    private enum CodingKeys: String, CodingKey {
        case required, minVersion, currentVersion, updateUrl, message
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // Every field optional-with-default: an advisory is ADVICE, and a
        // backend that adds or renames a field must never be able to fail the
        // decode of a connect response that is otherwise perfectly good.
        required = try c.decodeIfPresent(Bool.self, forKey: .required) ?? false
        minVersion = try c.decodeIfPresent(String.self, forKey: .minVersion) ?? ""
        currentVersion = try c.decodeIfPresent(String.self, forKey: .currentVersion) ?? ""
        updateUrl = try c.decodeIfPresent(String.self, forKey: .updateUrl) ?? ""
        message = try c.decodeIfPresent(String.self, forKey: .message) ?? ""
    }

    /// Only render an advisory that actually says something. A `clientUpdate`
    /// present but empty must not produce a blank banner.
    var isRenderable: Bool { !message.isEmpty }

    /// The App Store is the only place an iOS user can update from, whatever
    /// the server sends — `updateUrl` points at birdo.app/clients, which is
    /// correct for desktop and useless here. Kept on the struct so the value
    /// is visible, and deliberately not used to build the button's link.
    static let appStoreURL = URL(string: "https://apps.apple.com/app/id6670361940")
}

// Foundation-only BY DESIGN, and in its own file for the same reason
// QuickSelect.swift and BirdoShieldGate.swift are: the decoding rules below are
// the whole risk surface of this feature — an advisory that fails a good
// connect, or one that renders blank — and they can only be pinned by a test if
// they compile into the test bundle without dragging APIClient.swift's
// networking stack in with them. See VersionFloorAdvisoryTests.
