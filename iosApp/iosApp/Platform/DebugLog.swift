import Foundation
import os

/// Debug-only diagnostic log for the host app (iOS + macOS).
///
/// CLI-I-05: the host app called raw `NSLog` from 36 sites (BirdoPQManager 9,
/// VPNManager 9, VpnViewModel 18), and the only `#if DEBUG` in the whole app
/// was StoreKitService's private `log(_:)`. `NSLog` writes to the unified log
/// at `.default` level with every argument PUBLIC, in every configuration —
/// so a Release build on a user's device persisted the disconnect error text,
/// the rebuild verdicts and the PQ key-material failure reasons for anyone
/// with the device (or a sysdiagnose) to read. The packet-tunnel extension
/// already does this right (`os_log` with `%{private}@`); the app did not.
///
/// Rules, in order of what they buy:
///   1. `#if DEBUG` — the body compiles to NOTHING in Release, so the message
///      never reaches the log at all. This is the property the guard test
///      (`ReleaseLogGuardTest`) pins: no `NSLog(` outside this file.
///   2. `privacy: .private` — even in a Debug build on a device the message
///      is redacted in Console.app unless the debugger is attached, matching
///      the extension's convention.
///   3. `.debug` level — not persisted to disk by the unified log, only
///      streamed while something is listening.
///
/// The signature deliberately mirrors `NSLog(_:_:)` (printf-style format +
/// `CVarArg...`) so every call site was a mechanical rename with the message
/// text byte-identical — including `%@` / `%ld` sites — and any future
/// `NSLog(` can be swapped the same way. Callers must not switch to string
/// interpolation for the sake of it: a message that is identical to the one in
/// the code review and the audit ledger is worth more than a prettier one.
@inline(__always)
func debugLog(_ format: String, _ args: CVarArg...) {
    #if DEBUG
    // `String(format:)` interprets a stray `%` in an argument-free message, so
    // only format when there is something to substitute.
    let message = args.isEmpty ? format : String(format: format, arguments: args)
    Logger(subsystem: Bundle.main.bundleIdentifier ?? "app.birdo.vpn", category: "debug")
        .debug("\(message, privacy: .private)")
    #endif
}
