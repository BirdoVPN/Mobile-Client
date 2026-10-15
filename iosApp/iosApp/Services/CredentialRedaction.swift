import Foundation

// MR-1949 (iOS twin of Android MR-944 / MR-1870): no credential-bearing model
// prints a secret.
//
// Swift prints a struct or an enum through reflection by default, so
// `print(model)`, `"\(model)"`, `String(reflecting:)` and `dump(model)` all
// show every stored field: the password, both tokens, the 2FA challenge and
// code, the SSO code and PKCE verifier, Apple's identity token, the account
// number and the WireGuard private and pre-shared keys. Nothing logs these
// models today; this makes sure a future log line, crash breadcrumb or
// debugger `po` cannot leak one either.
//
// Each model below states the fields a log may show, with every secret
// replaced, and gets its `description`, `debugDescription` AND `customMirror`
// from that one list, so all four printing paths agree. Android's rule is
// kept: a secret prints as `[REDACTED]` when present and `nil` when absent, so
// a log still says which one it was, and non-identifying context (platform,
// app version, flags) survives. Device identifiers and the email are left out
// entirely, as on Android. Codable is untouched: encoding and decoding never
// consult any of these.
//
// This file is the mechanism only: `Redaction` and `CredentialRedacting`,
// Foundation-only and naming no model, so it compiles into all three targets
// (MR-2044): the app, BirdoVPNTests and the PacketTunnel extension. A
// credential-bearing type in the extension adopts it there, in a file the
// extension compiles. The app's own models adopt it in
// CredentialRedactingModels.swift, which BirdoVPNTests compiles with the
// models it covers (AuthWire.swift, ConnectWire.swift, AccountDeletion.swift,
// TunnelProfileSnapshot.swift, AccountIdentity.swift), which is what lets
// CredentialDescriptionTests check the real structs. IosRequiredRowsGuardTest
// (Android unit tests) finds every credential-bearing struct or enum in the
// app and the extension and fails until it has a conformance in its own
// target.

enum Redaction {
    /// What a present secret prints as.
    static let marker = "[REDACTED]"

    /// `[REDACTED]` when the secret is present, `nil` when it is absent.
    static func optional(_ secret: String?) -> String {
        secret == nil ? "nil" : marker
    }

    /// A non-secret optional, printed the way Swift prints one inside a struct.
    static func plain<T>(_ value: T?) -> String {
        value.map { "\($0)" } ?? "nil"
    }
}

/// A model that prints only `redactedFields`. Adopting it supplies all three
/// printing conformances, so none can be forgotten.
protocol CredentialRedacting: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    /// Every field a log may show, in declaration order, secrets already
    /// replaced (`Redaction`).
    var redactedFields: [(label: String, value: String)] { get }
}

extension CredentialRedacting {
    var description: String {
        let fields = redactedFields.map { "\($0.label): \($0.value)" }.joined(separator: ", ")
        return "\(Self.self)(\(fields))"
    }

    var debugDescription: String { description }

    var customMirror: Mirror {
        Mirror(self, children: redactedFields.map { (label: Optional($0.label), value: $0.value as Any) })
    }
}
