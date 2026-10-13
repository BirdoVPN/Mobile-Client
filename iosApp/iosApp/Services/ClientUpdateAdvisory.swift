import Foundation

/// The version floor, as the user sees it. It reaches this app two ways:
///
///   1. ADVICE — `clientUpdate` on a SUCCESSFUL connect (`required: false`).
///      OPEN-WORK H3: the backend attached this to every below-floor iOS
///      connect and NO client read it, so a user on an unsupported build
///      connected normally and was told nothing at all.
///   2. REFUSAL — the structured 426 `update_required` (`required: true`, built
///      by `UpdateRequiredRefusal`). birdo-web #605 made the iOS floor HARD:
///      below it every connect is refused, exactly as on Android and desktop.
///      MR-1661: that 426 used to be mapped to one fixed sentence with its
///      `details` thrown away, so the user saw no version and no way to update.
struct ClientUpdateAdvisory: Decodable, Equatable {
    /// False on a successful connect's advice; true on the 426 refusal, where
    /// the banner cannot be dismissed. Decoded rather than assumed for the
    /// advice, so a server-side change of mind needs no release to be seen.
    let required: Bool
    let minVersion: String
    let currentVersion: String
    let updateUrl: String
    let message: String

    private enum CodingKeys: String, CodingKey {
        case required, minVersion, currentVersion, updateUrl, message
    }

    init(required: Bool, minVersion: String, currentVersion: String, updateUrl: String, message: String) {
        self.required = required
        self.minVersion = minVersion
        self.currentVersion = currentVersion
        self.updateUrl = updateUrl
        self.message = message
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
    /// the server sends — `updateUrl` has pointed at birdo.app/clients, which
    /// is correct for desktop and useless here.
    static let appStoreURL = URL(string: "https://apps.apple.com/app/id6670361940")

    /// Where the Update button goes: the server's `updateUrl` when it is an
    /// https App Store link, otherwise `appStoreURL`. A server-supplied link
    /// is never opened anywhere else, so a website or a typo cannot become
    /// the update path.
    var storeURL: URL? {
        if let url = URL(string: updateUrl), url.scheme?.lowercased() == "https",
           let host = url.host?.lowercased(), host == "apps.apple.com" {
            return url
        }
        return Self.appStoreURL
    }
}

/// MR-1661: a connect refused with HTTP 426 `update_required` — this build is
/// below the support floor (birdo-web #605 made it hard on iOS).
///
/// Its own type rather than an `APIError` case, for the reason
/// `RetryableConnectRefusal` is: it only comes from the two connect routes,
/// which throw it through `performRequest`'s `refusal:` hook. That hook sees
/// the raw body BEFORE the generic mapping turns every 426 into one fixed
/// sentence and drops `details` (`minVersion`, `currentVersion`, `updateUrl`).
/// `LocalizedError`, and its description IS that fixed sentence, so the error
/// banner reads exactly as before; the required-update banner beside it now
/// carries the versions and the App Store button.
struct UpdateRequiredRefusal: Error, LocalizedError, Equatable {
    /// The floor, as a required advisory. `message` is client copy built from
    /// the versions (Review #219: never the raw body).
    let advisory: ClientUpdateAdvisory

    /// What the generic 426 mapping has always shown.
    static let refusalCopy = "This app version is no longer supported. Update BirdoVPN to reconnect."

    var errorDescription: String? { Self.refusalCopy }

    /// The banner text for a floor. `minVersion` is "latest" when the server
    /// knows no number, and either version may be absent.
    static func message(minVersion: String, currentVersion: String) -> String {
        let current = currentVersion.isEmpty ? "" : " (\(currentVersion))"
        if minVersion.isEmpty || minVersion.lowercased() == "latest" {
            return "This version of BirdoVPN\(current) is no longer supported. "
                + "Update to the latest version to reconnect."
        }
        return "This version of BirdoVPN\(current) is no longer supported. "
            + "Update to \(minVersion) or later to reconnect."
    }

    /// The refusal this response is, or nil to let `APIClient` map it as
    /// before. Claimed on the STATUS, as the generic mapping always did, so a
    /// 426 whose body is not the contract's shape still raises the banner; the
    /// `details` fill it in when present.
    static func classify(status: Int, body: Data) -> UpdateRequiredRefusal? {
        guard status == 426 else { return nil }
        let details = (try? JSONDecoder().decode(Envelope.self, from: body))?.details
        let minVersion = details?.minVersion ?? ""
        let currentVersion = details?.currentVersion ?? ""
        return UpdateRequiredRefusal(advisory: ClientUpdateAdvisory(
            required: true,
            minVersion: minVersion,
            currentVersion: currentVersion,
            updateUrl: details?.updateUrl ?? "",
            message: message(minVersion: minVersion, currentVersion: currentVersion)
        ))
    }

    /// `{ ..., details: { minVersion, currentVersion, updateUrl } }` — the
    /// contract's `update_required` (contract/vpn-protocol.schema.json). Each
    /// field decoded on its own and optionally, so a wrong type costs that
    /// field only.
    private struct Envelope: Decodable {
        let details: Details?

        private enum CodingKeys: String, CodingKey { case details }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            details = try? c.decodeIfPresent(Details.self, forKey: .details)
        }
    }

    private struct Details: Decodable {
        let minVersion: String?
        let currentVersion: String?
        let updateUrl: String?

        private enum CodingKeys: String, CodingKey { case minVersion, currentVersion, updateUrl }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            minVersion = try? c.decodeIfPresent(String.self, forKey: .minVersion)
            currentVersion = try? c.decodeIfPresent(String.self, forKey: .currentVersion)
            updateUrl = try? c.decodeIfPresent(String.self, forKey: .updateUrl)
        }
    }
}

// Foundation-only BY DESIGN, and in its own file for the same reason
// QuickSelect.swift and BirdoShieldGate.swift are: the decoding rules below are
// the whole risk surface of this feature — an advisory that fails a good
// connect, or one that renders blank — and they can only be pinned by a test if
// they compile into the test bundle without dragging APIClient.swift's
// networking stack in with them. See VersionFloorAdvisoryTests.
