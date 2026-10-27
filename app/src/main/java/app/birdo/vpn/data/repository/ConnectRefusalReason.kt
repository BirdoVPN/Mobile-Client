package app.birdo.vpn.data.repository

/**
 * MR-139: why the server refused a connect inside its 200 (`refusalReason`
 * beside `success: false`). birdo-web's CONNECT_REFUSALS (vpn.service.ts),
 * vendored here as `$defs.ConnectRefusal` in contract/vpn-protocol.schema.json
 * and pinned to it by ConnectErrorContractTest.
 *
 * Before it existed the device cap was recognised by the server's wording
 * ("Device limit reached …") and nothing else could be told apart. Read off
 * the model as a plain string, so a value this build does not know cannot fail
 * the decode: [fromWire] returns null for it and for none, and the refusal is
 * handled as one without a reason, by its wording, exactly as before.
 */
enum class ConnectRefusalReason(val wire: String) {
    /** The plan's device cap is reached; asking again changes nothing. */
    DEVICE_LIMIT("device_limit"),

    /** The chosen server, every server, or Multi-Hop is outside the plan. */
    PLAN("plan"),

    /** The Free data allowance is used up (always with `quotaExceeded: true`). */
    QUOTA_EXCEEDED("quota_exceeded"),

    /** The chosen server (or a Multi-Hop entry or exit) is full. */
    SERVER_CAPACITY("server_capacity"),

    /** The chosen server is missing or not ONLINE, or no server can be used right now. */
    SERVER_UNAVAILABLE("server_unavailable"),
    ;

    companion object {
        /** The reason [value] names, or null for none and for one newer than this build. */
        fun fromWire(value: String?): ConnectRefusalReason? =
            if (value == null) null else entries.firstOrNull { it.wire == value }
    }
}
