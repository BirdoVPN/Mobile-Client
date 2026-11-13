import Foundation

/// MR-1618 -- where "Forgot password?" goes.
///
/// The backend route is `POST /auth/password/request-reset`, the one the
/// website's `/reset-password` page calls. It is guarded by Cloudflare
/// Turnstile (`@RequireTurnstile('password-reset-request')`): the request is
/// refused without a `turnstileToken` that only a browser can mint, and there
/// is no native bypass. So the app opens the website's page, which runs the
/// same endpoint with its own token and completes the reset (the e-mailed link
/// lands on the same page).
///
/// Foundation-only, in the unit-test bundle, so the target cannot drift.
enum AuthLinks {
    static let resetPassword = URL(string: "https://birdo.app/reset-password")!
}
