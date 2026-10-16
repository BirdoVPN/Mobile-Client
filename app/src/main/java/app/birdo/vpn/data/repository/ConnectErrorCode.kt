package app.birdo.vpn.data.repository

/**
 * The machine-readable `error` codes the connect routes emit: birdo-web's
 * CONNECT_ERROR_CODES (backend/src/vpn/contract/protocol-schema.ts), vendored
 * here as `$defs.ErrorCode` in contract/vpn-protocol.schema.json and pinned to
 * it by ConnectErrorContractTest.
 *
 * Read from the error envelope's `error` field as a plain string
 * ([ApiErrorMapper.parseEnvelope]), never through a serializer, so a code this
 * build does not know cannot fail a decode: it is [UNKNOWN], and the failure
 * is classified by its HTTP status exactly as it was before the code existed.
 * The same field carries Nest's reason phrase ("SERVICE_UNAVAILABLE",
 * "Bad Request") on every error that names no code; that is [UNKNOWN] too.
 *
 * [retryable] is the contract's word, not a guess from the status: the server
 * refused a connect that nothing about the account or the request will keep
 * refusing, and says when to come back (`details.retryAfterSeconds`, read by
 * [RetryAfter]). Asking again is the remedy; treating it as a refusal is not.
 */
enum class ConnectErrorCode(val wire: String?, val retryable: Boolean) {
    /** 426: below the support floor, or a quantumProtection request without a usable PQ key. */
    UPDATE_REQUIRED("update_required", retryable = false),

    /** 403: the attestation policy refused this build. */
    CLIENT_ATTESTATION_REQUIRED("client_attestation_required", retryable = false),

    /**
     * 503: a FREE account's data allowance could not be read, so the cap
     * failed closed (birdo-web PR #590). Paid plans never receive it.
     */
    QUOTA_CHECK_UNAVAILABLE("quota_check_unavailable", retryable = true),

    /**
     * 503: the backend had no node agent to add the peer with (its one throw
     * site, wireguard.service.ts, runs only when getAgent() is null, a
     * hub-wide setting), and the SSH fallback never carries a WireGuard
     * pre-shared key, so the server rolled the new peer back instead of
     * installing it without one (VpnController rethrowPskDeliveryRefusal, on
     * /vpn/connect and /vpn/multi-hop/connect). A temporary server-side
     * condition, not one relay's: every location answers the same until it
     * clears, so the remedy is to wait, not to switch.
     * Nothing was downgraded server-side and nothing may be here: the re-dial
     * asks for exactly what this one did, because VpnManager reads Quantum
     * Protection afresh on every dial and no failure path turns it off.
     */
    PSK_DELIVERY_UNAVAILABLE("psk_delivery_unavailable", retryable = true),

    /**
     * 503 (birdo-web #711, MR-2058): the allocator could not prove a tunnel
     * address free, because a reservation it must read (the port-forward pins,
     * or the Multi-Hop exit routes) did not answer, so it failed closed rather
     * than risk handing this session another customer's address
     * (TunnelAddressUnavailableError, on /vpn/connect and
     * /vpn/multi-hop/connect). Nothing is wrong with the account or the node;
     * the server says to come back after `details.retryAfterSeconds` (15).
     * It used to decode as [UNKNOWN] and was retried only because it happened
     * to arrive as a 503, with no wait at all when the body lost its own.
     */
    TUNNEL_ADDRESS_UNAVAILABLE("tunnel_address_unavailable", retryable = true),

    /** No code, a reason phrase, or a code newer than this build: classified by status. */
    UNKNOWN(null, retryable = false),
    ;

    companion object {
        /** The code an envelope's `error` value names. Exact match: the server's codes are lower snake case. */
        fun fromWire(error: String?): ConnectErrorCode =
            if (error == null) UNKNOWN else entries.firstOrNull { it.wire == error } ?: UNKNOWN
    }
}
