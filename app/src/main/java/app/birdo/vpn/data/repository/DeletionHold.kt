package app.birdo.vpn.data.repository

import app.birdo.vpn.data.model.DeleteAccountResponse
import app.birdo.vpn.data.model.PendingChange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * MR-179 hold fallback (birdo-web #883): an account deletion the server holds
 * for 24 hours. Nothing is deleted until [applyAfter]; until then the hold can
 * be cancelled (`POST auth/pending-changes/{id}/cancel`).
 */
data class PendingDeletion(val id: String, val applyAfter: Instant)

/**
 * What a hold request (`stepUpMethod: "hold"`) answered, when it was not an
 * error. The server never deletes at once for a hold (#883 final: switch off
 * is 409 `hold_unavailable`), so there is no "deleted" outcome here, and any
 * other 2xx is an unexpected answer (review of #616).
 */
sealed interface DeletionHoldResult {
    /**
     * 202 `change_pending` (a new hold), or 409 `change_already_pending`
     * ([alreadyPending], the hold that was already waiting). Nothing was
     * deleted: no sign-out, no tunnel teardown, no token wipe.
     */
    data class Held(val pending: PendingDeletion, val alreadyPending: Boolean) : DeletionHoldResult
}

/**
 * MR-2378 (review of #616): who filed a deletion hold, which decides what a
 * sign-out does to it. The server voids a hold only when the session that
 * FILED it ends; a hold filed on the website (no session) or by another
 * device survives a sign-out here. Pure, so the mapping is unit tested.
 *
 * Only a 202 `change_pending` to THIS session's own request makes a hold
 * "filed here". A 409 `change_already_pending` names a hold that was already
 * waiting: this session's own only if this session had already marked it.
 * Nothing else (a lost answer, a list read, a restart) ever claims a hold.
 */
internal object HoldOrigin {
    /** The status [BirdoRepository.deleteAccount] reports for a hold it was answered with. */
    fun statusFor(held: DeletionHoldResult.Held): Int =
        if (held.alreadyPending) BirdoRepository.HTTP_CONFLICT else BirdoRepository.HTTP_ACCEPTED

    /** Did this status, answering this session's own request, file a NEW hold? Only a 202. */
    fun filedByThisRequest(status: Int): Boolean = status == BirdoRepository.HTTP_ACCEPTED

    /**
     * The marker after a hold request was answered with [held]: a new hold
     * (202) is this session's; a hold already waiting (409) keeps whatever
     * marker this session had ([mine]), so it is ours only if it was already.
     */
    fun markerAfterHoldAnswer(mine: String?, held: DeletionHoldResult.Held): String? =
        if (held.alreadyPending) mine?.takeIf { it == held.pending.id } else held.pending.id

    /**
     * The marker after the list said [listed]: claimed only when the read
     * follows this session's own 202 ([filedByThisRequest]), kept while the
     * same hold is listed, gone once that hold is (only one account deletion
     * waits at a time, so a different id is not this session's).
     */
    fun markerAfterRead(mine: String?, listed: PendingDeletion?, filedByThisRequest: Boolean): String? = when {
        listed == null -> null
        filedByThisRequest -> listed.id
        listed.id == mine -> mine
        else -> null
    }
}

/**
 * The wire rules of the hold, kept pure so they are unit tested on the JVM.
 *
 * `stepUp` is polymorphic on the deletion route: an OBJECT
 * `{ methods, pendingChangeId, applyAfter }` on 202 / 409, and an ARRAY of
 * factor names on the 401 / 403 / 429 refusals. Every reader here takes either
 * (or anything else) and answers null for what is not a hold, never throws,
 * and never reads a hold out of a refusal.
 */
internal object DeletionHoldWire {
    const val METHOD_HOLD = "hold"
    const val ACTION_ACCOUNT_DELETE = "account_delete"
    const val OUTCOME_CHANGE_PENDING = "change_pending"
    const val ERROR_CHANGE_ALREADY_PENDING = "change_already_pending"
    const val ERROR_HOLD_UNAVAILABLE = "hold_unavailable"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Whether a 2xx body is the hold's 202 rather than a deletion. */
    fun isHoldAnswer(status: Int, body: DeleteAccountResponse?): Boolean =
        status == 202 || body?.pending == true || body?.outcome == OUTCOME_CHANGE_PENDING

    /** The hold in a `stepUp` value: an object with a non-blank id and a parseable instant. */
    fun holdOf(stepUp: JsonElement?): PendingDeletion? {
        val obj = stepUp as? JsonObject ?: return null
        val id = (obj["pendingChangeId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val at = parseInstant((obj["applyAfter"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull) ?: return null
        return PendingDeletion(id, at)
    }

    /**
     * The hold an error body names: `stepUp` at the top (the contract), or
     * under `details` (the API's exception filter passes only `details`
     * through, so a fixed #883 may put it there). Null when it names none:
     * the caller then reads `GET auth/pending-changes`.
     */
    fun holdOfErrorBody(raw: String?): PendingDeletion? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        val details = root["details"] as? JsonObject
        return holdOf(root["stepUp"]) ?: holdOf(details?.get("stepUp")) ?: holdOf(details)
    }

    /** The error body's machine-readable `error` (or `details.code`), lower-case. */
    fun errorCodeOf(raw: String?): String? {
        val envelope = ApiErrorMapper.parseEnvelope(raw) ?: return null
        return (envelope.error ?: envelope.code)?.trim()?.lowercase()
    }

    /** The `account_delete` entry of `GET auth/pending-changes`, the soonest when there are several. */
    fun accountDeletionIn(changes: List<PendingChange>): PendingDeletion? =
        changes.asSequence()
            .filter { it.action == ACTION_ACCOUNT_DELETE && it.id.isNotBlank() }
            .mapNotNull { change -> parseInstant(change.applyAfter)?.let { PendingDeletion(change.id.trim(), it) } }
            .minByOrNull { it.applyAfter }

    /** ISO-8601 with an offset (`...Z` or `+02:00`); anything else is null. */
    fun parseInstant(raw: String?): Instant? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(text).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
