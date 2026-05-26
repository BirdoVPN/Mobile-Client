@file:Suppress("MatchingDeclarationName")

package app.birdo.vpn.data.model

/**
 * Re-exports from shared KMP module.
 *
 * All model types now live in :shared (app.birdo.vpn.shared.model).
 * These typealiases preserve the existing import paths so that the
 * rest of the Android codebase requires zero import changes.
 */

// ─── Auth ────────────────────────────────────────────────────────────────────
typealias LoginRequest = app.birdo.vpn.shared.model.LoginRequest
typealias TokenPair = app.birdo.vpn.shared.model.TokenPair
typealias LoginResult = app.birdo.vpn.shared.model.LoginResult
typealias LoginResponse = app.birdo.vpn.shared.model.LoginResponse
typealias TwoFactorVerifyRequest = app.birdo.vpn.shared.model.TwoFactorVerifyRequest
typealias TwoFactorVerifyResponse = app.birdo.vpn.shared.model.TwoFactorVerifyResponse
typealias RefreshRequest = app.birdo.vpn.shared.model.RefreshRequest
typealias RefreshResponse = app.birdo.vpn.shared.model.RefreshResponse

// ─── User ────────────────────────────────────────────────────────────────────
typealias UserProfile = app.birdo.vpn.shared.model.UserProfile
typealias SubscriptionStatus = app.birdo.vpn.shared.model.SubscriptionStatus

// ─── Anonymous Login ─────────────────────────────────────────────────────────
typealias AnonymousLoginRequest = app.birdo.vpn.shared.model.AnonymousLoginRequest
typealias AnonymousLoginResponse = app.birdo.vpn.shared.model.AnonymousLoginResponse

// ─── Vouchers ────────────────────────────────────────────────────────────────
typealias RedeemVoucherRequest = app.birdo.vpn.shared.model.RedeemVoucherRequest
typealias RedeemVoucherResponse = app.birdo.vpn.shared.model.RedeemVoucherResponse

// ─── GDPR / Account Deletion ─────────────────────────────────────────────────
typealias DeleteAccountRequest = app.birdo.vpn.shared.model.DeleteAccountRequest
typealias DeleteAccountResponse = app.birdo.vpn.shared.model.DeleteAccountResponse
typealias GdprExportResponse = app.birdo.vpn.shared.model.GdprExportResponse

// ─── VPN Servers ─────────────────────────────────────────────────────────────
typealias VpnServer = app.birdo.vpn.shared.model.VpnServer

// ─── VPN Connect ─────────────────────────────────────────────────────────────
typealias ConnectRequest = app.birdo.vpn.shared.model.ConnectRequest
typealias ServerNodeInfo = app.birdo.vpn.shared.model.ServerNodeInfo
typealias ConnectResponse = app.birdo.vpn.shared.model.ConnectResponse

// ─── Multi-Hop ───────────────────────────────────────────────────────────────
typealias MultiHopRoute = app.birdo.vpn.shared.model.MultiHopRoute
typealias MultiHopConnectRequest = app.birdo.vpn.shared.model.MultiHopConnectRequest
typealias MultiHopNodeInfo = app.birdo.vpn.shared.model.MultiHopNodeInfo
typealias MultiHopInfo = app.birdo.vpn.shared.model.MultiHopInfo
typealias MultiHopConnectResponse = app.birdo.vpn.shared.model.MultiHopConnectResponse

// ─── Port Forwarding ─────────────────────────────────────────────────────────
typealias PortForward = app.birdo.vpn.shared.model.PortForward
typealias CreatePortForwardRequest = app.birdo.vpn.shared.model.CreatePortForwardRequest
typealias CreatePortForwardResponse = app.birdo.vpn.shared.model.CreatePortForwardResponse

// ─── Google Play Billing ─────────────────────────────────────────────────────
// Removed: Android distributed as APK from GitHub Releases; no Play Billing.

// ─── Key Rotation ────────────────────────────────────────────────────────────
typealias KeyRotationRequest = app.birdo.vpn.shared.model.KeyRotationRequest
typealias KeyRotationResponse = app.birdo.vpn.shared.model.KeyRotationResponse

// ─── Protocol Error Codes ────────────────────────────────────────────────────
typealias ProtocolErrorCode = app.birdo.vpn.shared.model.ProtocolErrorCode
typealias ApiErrorBody = app.birdo.vpn.shared.model.ApiErrorBody

// ─── Heartbeat ───────────────────────────────────────────────────────────────
typealias HeartbeatResponse = app.birdo.vpn.shared.model.HeartbeatResponse

// ─── Quality Reporting ───────────────────────────────────────────────────────
typealias QualityReport = app.birdo.vpn.shared.model.QualityReport
