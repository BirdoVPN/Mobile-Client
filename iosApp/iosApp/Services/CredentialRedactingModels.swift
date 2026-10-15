import Foundation

// MR-1949: the app's credential-bearing models print only what a log may show.
// The mechanism (`Redaction`, `CredentialRedacting`) is CredentialRedaction.swift,
// which the PacketTunnel extension compiles too; these conformances name app
// models, so they live here, in the app and BirdoVPNTests only (MR-2044).

// MARK: - Session

extension TokenPairData: CredentialRedacting {
    var redactedFields: [(label: String, value: String)] {
        [("accessToken", Redaction.marker), ("refreshToken", Redaction.marker)]
    }
}

extension LoginResult: CredentialRedacting {
    var redactedFields: [(label: String, value: String)] {
        switch self {
        case .success(let tokens):
            return [("success", tokens.description)]
        case .twoFactorRequired:
            return [("twoFactorRequired", "challengeToken: \(Redaction.marker)")]
        }
    }
}

extension AnonymousRegistration: CredentialRedacting {
    /// `anonymousId` is the account's only credential.
    var redactedFields: [(label: String, value: String)] {
        [("anonymousId", Redaction.optional(anonymousId)), ("tokens", tokens.description)]
    }
}

extension UserProfile: CredentialRedacting {
    /// Leaves out `email`, `name` and `accountNumber`, like Android's
    /// UserProfile: the email of an older backend's anonymous account carries
    /// its account number.
    var redactedFields: [(label: String, value: String)] {
        [
            ("id", id),
            ("accountType", Redaction.plain(accountType)),
            ("isAnonymous", Redaction.plain(isAnonymousField)),
            ("hasPassword", "\(hasPassword)"),
            ("isSSO", "\(isSSO)"),
        ]
    }
}

// MARK: - Sign-in request bodies

extension LoginBody: CredentialRedacting {
    /// Leaves out the email, the password and the device identifiers.
    var redactedFields: [(label: String, value: String)] {
        [("platform", platform), ("platformVersion", platformVersion), ("appVersion", appVersion)]
    }
}

extension AnonymousLoginBody: CredentialRedacting {
    /// Leaves out the account number, the password and the device identifiers.
    var redactedFields: [(label: String, value: String)] {
        [("platform", platform), ("platformVersion", platformVersion), ("appVersion", appVersion)]
    }
}

extension TwoFactorBody: CredentialRedacting {
    /// Neither the challenge nor the TOTP / backup code in `token`.
    var redactedFields: [(label: String, value: String)] {
        [("challengeToken", Redaction.marker), ("token", Redaction.marker)]
    }
}

extension AppleNativeBody: CredentialRedacting {
    /// Neither Apple's identity token nor the nonce it was bound to.
    var redactedFields: [(label: String, value: String)] {
        [
            ("identityToken", Redaction.marker),
            ("nonce", Redaction.marker),
            ("platform", platform),
            ("platformVersion", platformVersion),
            ("appVersion", appVersion),
        ]
    }
}

extension SsoExchangeBody: CredentialRedacting {
    /// Leaves out `code` and `codeVerifier` (together they redeem the handoff
    /// for a token pair) and the device identifiers.
    var redactedFields: [(label: String, value: String)] {
        [("platform", platform), ("platformVersion", platformVersion), ("appVersion", appVersion)]
    }
}

extension RefreshBody: CredentialRedacting {
    var redactedFields: [(label: String, value: String)] {
        [("refresh_token", Redaction.marker)]
    }
}

// MARK: - Sign-in responses

extension TokenPairDTO: CredentialRedacting {
    var redactedFields: [(label: String, value: String)] {
        [("accessToken", Redaction.marker), ("refreshToken", Redaction.marker)]
    }
}

extension AuthTokensResponse: CredentialRedacting {
    var redactedFields: [(label: String, value: String)] {
        [
            ("ok", Redaction.plain(ok)),
            ("tokens", tokens?.description ?? "nil"),
            ("requiresTwoFactor", Redaction.plain(requiresTwoFactor)),
            ("challengeToken", Redaction.optional(challengeToken)),
            ("anonymousId", Redaction.optional(anonymousId)),
        ]
    }
}

extension RefreshTokensResponse: CredentialRedacting {
    var redactedFields: [(label: String, value: String)] {
        [("accessToken", Redaction.marker), ("refreshToken", Redaction.optional(refreshToken))]
    }
}

// MARK: - Account deletion

extension DeleteAccountBody: CredentialRedacting {
    /// Which of the three was sent still shows; none of their values do.
    var redactedFields: [(label: String, value: String)] {
        [
            ("password", Redaction.optional(password)),
            ("twoFactorCode", Redaction.optional(twoFactorCode)),
            ("appleAuthorizationCode", Redaction.optional(appleAuthorizationCode)),
        ]
    }
}

// MARK: - App Store purchases

extension ApplePurchaseTokenResponse: CredentialRedacting {
    /// The token that binds an App Store purchase to this account.
    var redactedFields: [(label: String, value: String)] {
        [("appAccountToken", Redaction.marker)]
    }
}

extension AppleLinkBody: CredentialRedacting {
    /// Apple's signed transaction, like Android's Play purchase token.
    var redactedFields: [(label: String, value: String)] {
        [("signedTransaction", Redaction.marker)]
    }
}

// MARK: - Connect

extension VPNConnectionConfig: CredentialRedacting {
    /// Never the tunnel's private key or pre-shared key, nor the BirdoPQ
    /// ciphertext in `rosenpassPublicKey` the PSK is derived from (Android
    /// ConnectResponse redacts the same three).
    var redactedFields: [(label: String, value: String)] {
        [
            ("serverAddress", serverAddress),
            ("serverPort", "\(serverPort)"),
            ("privateKey", Redaction.marker),
            ("publicKey", publicKey),
            ("presharedKey", Redaction.optional(presharedKey)),
            ("addresses", "\(addresses)"),
            ("dns", "\(dns)"),
            ("allowedIPs", "\(allowedIPs)"),
            ("mtu", Redaction.plain(mtu)),
            ("keyId", Redaction.plain(keyId)),
            ("deferredKeyId", Redaction.plain(deferredKeyId)),
            ("quantumEnabled", Redaction.plain(quantumEnabled)),
            ("rosenpassPublicKey", Redaction.optional(rosenpassPublicKey)),
            ("rosenpassEndpoint", Redaction.plain(rosenpassEndpoint)),
            ("clientUpdate", Redaction.plain(clientUpdate)),
            ("multiHop", Redaction.plain(multiHop)),
        ]
    }
}

// MARK: - Live rebuild

extension TunnelProfileSnapshot: CredentialRedacting {
    /// Never the private or pre-shared key. The provider configuration shows
    /// only its keys: a profile written by an older build may still carry the
    /// heartbeat token itself (see `VPNManager.restoreProfile`).
    var redactedFields: [(label: String, value: String)] {
        [
            ("providerConfiguration", "\(providerConfiguration.keys.sorted())"),
            ("serverAddress", serverAddress),
            ("privateKey", Redaction.marker),
            ("presharedKey", Redaction.optional(presharedKey)),
        ]
    }
}

// MARK: - Account identity

extension AccountIdentity.Resolved: CredentialRedacting {
    /// The account number is the anonymous account's only credential.
    var redactedFields: [(label: String, value: String)] {
        [("isAnonymous", "\(isAnonymous)"), ("accountNumber", Redaction.optional(accountNumber))]
    }
}
