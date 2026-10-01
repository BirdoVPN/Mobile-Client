import Foundation

/// Account deletion (GDPR Art. 17) — the pure decisions behind
/// `AuthViewModel.deleteAccount`, kept Foundation-only so the unit-test bundle
/// can pin them (AuthViewModel and APIClient cannot be compiled into it).
///
/// Owner item 43 (2026-10-01): the delete request goes FIRST. Only a server
/// that has CONFIRMED the erasure gets the tunnel torn down, the credentials
/// cleared and the user signed out; a refusal leaves the user connected and
/// signed in, with the reason on screen. (It used to disconnect the VPN before
/// sending, so a wrong password or a 5xx cost the user their tunnel for
/// nothing.)
enum AccountDeletion {
    /// Did the request possibly reach the server, with the answer lost?
    ///
    /// This is NOT an edge case while connected. The request rides the tunnel,
    /// and the erasure revokes every peer of the account on the nodes BEFORE
    /// the server answers (birdo-web `GdprService.deleteUserData`, B5), so the
    /// answer to a successful deletion is routed to a peer that no longer
    /// exists and never arrives: the request times out on a deleted account.
    /// Reading that timeout as "deletion failed" would leave the user signed
    /// in to nothing, behind a dead tunnel.
    ///
    /// So these errors mean UNKNOWN, and the caller checks: tunnel down, then
    /// `GET /auth/me` — a 401 there is a confirmed deletion.
    ///
    /// Transport errors that prove the request never left (no route, DNS, TLS
    /// or pin failure) are definitive failures instead: nothing was sent.
    static func outcomeIsUnknown(_ error: Error) -> Bool {
        guard let urlError = error as? URLError else { return false }
        switch urlError.code {
        case .timedOut, .networkConnectionLost, .badServerResponse,
             .cannotParseResponse, .zeroByteResource:
            return true
        default:
            return false
        }
    }
}
