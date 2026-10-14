import Foundation

/// Everything needed to put the RUNNING tunnel back the way it was: the
/// persisted provider configuration (all string-valued — the redacted
/// wg-quick config, the keychain refs, the heartbeat handle) plus the two
/// secrets the extension reads by those refs. Taken before a swap and
/// dropped the moment the swap commits or reverts, so the previous private
/// key is in app memory for seconds, not for the session. Read back from
/// the system profile and the shared keychain — not from memory — so a
/// rebuild after an app relaunch under a live tunnel can still revert
/// (`VPNManager.snapshotRunningProfile` / `restoreProfile`, Mobile-Client #159).
///
/// Moved out of VPNManager.swift (NetworkExtension, which cannot compile into
/// the unit-test bundle) so CredentialDescriptionTests can print the REAL
/// struct: it holds the tunnel's private and pre-shared keys, and its
/// redacting description lives in CredentialRedaction.swift with the others.
struct TunnelProfileSnapshot: Sendable {
    let providerConfiguration: [String: String]
    let serverAddress: String
    let privateKey: String
    let presharedKey: String?
}
