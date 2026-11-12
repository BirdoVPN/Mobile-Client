package app.birdo.vpn.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.birdo.vpn.data.repository.BirdoRepository
import app.birdo.vpn.data.repository.RotatePrepareResponse
import app.birdo.vpn.data.repository.RotationFailure
import app.birdo.vpn.data.repository.RotationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The three steps of replacing the number (MR-1707), then the done screen. */
enum class ReplaceStep { PROOF, SAVE, CONFIRM, DONE }

/**
 * What the sheet shows. Carries NO number: the new one lives in
 * [ReplaceNumberFlow] as a char array and is read only while a step draws it.
 */
data class ReplaceNumberState(
    val step: ReplaceStep = ReplaceStep.PROOF,
    val busy: Boolean = false,
    /** The last refusal, or null. Cleared by the next action. */
    val failure: RotationFailure? = null,
    val retryAfterMs: Long? = null,
    /** The "saved it" box of step 2. Unticked on every arrival. */
    val savedAcknowledged: Boolean = false,
    /**
     * A confirm was sent in this flow and the answer since says the pending number
     * is gone or the session ended: the swap may already have happened with the
     * response lost. The flow stays on CONFIRM with the number still held, and the
     * sheet offers "Sign in again" instead of a silent restart.
     */
    val maybeCommitted: Boolean = false,
)

/** The two calls the flow needs: [BirdoRepository] in the app, a fake in tests. */
interface RotationGateway {
    suspend fun prepare(currentNumber: String, password: String?, twoFactorCode: String?): RotationResult<RotatePrepareResponse>
    suspend fun confirm(newNumber: String): RotationResult<Unit>
}

/**
 * The step machine. Plain Kotlin (no Android, no scope) so every refusal's
 * path is a JVM test.
 *
 *   PROOF   -> prepare ok                      -> SAVE
 *   SAVE    -> box ticked + continue            -> CONFIRM
 *   CONFIRM -> confirm ok                       -> DONE
 *
 * Refusals: 409 (not pending / account changed) and 403 (disabled) restart at
 * PROOF with the number dropped; a wrong re-typed number (400) and a lost
 * confirm (no answer) STAY on CONFIRM with the number still held, so a retry
 * is possible and the old number keeps working until confirm lands; a 401 the
 * handler wrote, a 429 and anything else are shown where the user is.
 *
 * The new number is a credential: held in a CharArray, zeroed by [clear], never
 * logged, never put in [ReplaceNumberState].
 */
class ReplaceNumberFlow(private val gateway: RotationGateway) {
    private val _state = MutableStateFlow(ReplaceNumberState())
    val state: StateFlow<ReplaceNumberState> = _state.asStateFlow()

    private var newNumber: CharArray? = null

    /** Whether a confirm request has gone out in this flow. */
    private var confirmSent = false

    /** The held new number as digits, for the step that shows it; empty when none. */
    fun newNumberDigits(): String = newNumber?.let { String(it) } ?: ""

    /** Whether a new number is held. */
    fun holdsNumber(): Boolean = newNumber != null

    suspend fun prepare(currentNumber: String, password: String?, twoFactorCode: String?) {
        if (_state.value.busy || _state.value.step != ReplaceStep.PROOF) return
        _state.value = _state.value.copy(busy = true, failure = null, retryAfterMs = null)
        when (val r = gateway.prepare(currentNumber.filter { it.isDigit() }, password, twoFactorCode)) {
            is RotationResult.Ok -> {
                val digits = r.data.newNumber.filter { it.isDigit() }
                if (digits.isEmpty()) {
                    _state.value = _state.value.copy(busy = false, failure = RotationFailure.REJECTED)
                } else {
                    wipe()
                    newNumber = digits.toCharArray()
                    _state.value = ReplaceNumberState(step = ReplaceStep.SAVE)
                }
            }
            is RotationResult.Refused -> refused(r)
        }
    }

    fun setSavedAcknowledged(value: Boolean) {
        if (_state.value.step == ReplaceStep.SAVE) _state.value = _state.value.copy(savedAcknowledged = value)
    }

    /** Step 2 to 3: only once the box is ticked. */
    fun continueToConfirm() {
        val s = _state.value
        if (s.step == ReplaceStep.SAVE && s.savedAcknowledged && newNumber != null) {
            _state.value = ReplaceNumberState(step = ReplaceStep.CONFIRM)
        }
    }

    /** Step 3 back to 2 ("show the number again"); the box is unticked again. */
    fun showNumberAgain() {
        if (_state.value.step == ReplaceStep.CONFIRM && newNumber != null) {
            _state.value = ReplaceNumberState(step = ReplaceStep.SAVE)
        }
    }

    suspend fun confirm(typed: String) {
        val held = newNumber ?: return
        if (_state.value.busy || _state.value.step != ReplaceStep.CONFIRM) return
        val digits = typed.filter { it.isDigit() }
        // A wrong guess counts toward the server's per-account limit: say so locally first.
        if (!digits.toCharArray().contentEquals(held)) {
            _state.value = _state.value.copy(failure = RotationFailure.MISMATCH, retryAfterMs = null)
            return
        }
        _state.value = _state.value.copy(busy = true, failure = null, retryAfterMs = null)
        confirmSent = true
        when (val r = gateway.confirm(String(held))) {
            is RotationResult.Ok -> {
                wipe()
                _state.value = ReplaceNumberState(step = ReplaceStep.DONE)
            }
            is RotationResult.Refused -> refused(r)
        }
    }

    private fun refused(r: RotationResult.Refused) {
        val s = _state.value
        // After a confirm went out, a 401 or a 409 may mean it DID commit and only
        // its answer was lost (the old tokens are revoked, the pending entry is
        // consumed). Do not restart as if nothing changed: the user would type the
        // old number, which no longer works. Keep step 3 and the number.
        if (confirmSent && s.step == ReplaceStep.CONFIRM && newNumber != null && r.failure in AMBIGUOUS_AFTER_CONFIRM) {
            _state.value = s.copy(busy = false, failure = r.failure, retryAfterMs = r.retryAfterMs, maybeCommitted = true)
            return
        }
        when (r.failure) {
            // Start again: the pending number is gone (or never was) server-side.
            RotationFailure.NOT_PENDING,
            RotationFailure.ACCOUNT_CHANGED,
            RotationFailure.DISABLED -> {
                wipe()
                _state.value = ReplaceNumberState(step = ReplaceStep.PROOF, failure = r.failure, retryAfterMs = r.retryAfterMs)
            }
            // Everything else stays where the user is. NETWORK on confirm keeps the
            // number in memory: the swap may or may not have happened, retry is allowed.
            else -> _state.value = s.copy(busy = false, failure = r.failure, retryAfterMs = r.retryAfterMs)
        }
    }

    /** Drop every number held: zero the array, then forget it. Call on dismiss. */
    fun clear() {
        wipe()
        _state.value = ReplaceNumberState()
    }

    private fun wipe() {
        newNumber?.fill('0')
        newNumber = null
        confirmSent = false
    }

    private companion object {
        val AMBIGUOUS_AFTER_CONFIRM = setOf(
            RotationFailure.SESSION_EXPIRED,
            RotationFailure.PROOF_INVALID,
            RotationFailure.NOT_PENDING,
        )
    }
}

@HiltViewModel
class ReplaceNumberViewModel @Inject constructor(
    private val repository: BirdoRepository,
) : ViewModel() {

    private val flow = ReplaceNumberFlow(object : RotationGateway {
        override suspend fun prepare(currentNumber: String, password: String?, twoFactorCode: String?) =
            repository.rotateNumberPrepare(currentNumber, password, twoFactorCode)

        override suspend fun confirm(newNumber: String) = repository.rotateNumberConfirm(newNumber)
    })

    val state: StateFlow<ReplaceNumberState> = flow.state

    fun newNumberDigits(): String = flow.newNumberDigits()

    fun prepare(currentNumber: String, password: String?, twoFactorCode: String?) {
        viewModelScope.launch { flow.prepare(currentNumber, password, twoFactorCode) }
    }

    fun setSavedAcknowledged(value: Boolean) = flow.setSavedAcknowledged(value)
    fun continueToConfirm() = flow.continueToConfirm()
    fun showNumberAgain() = flow.showNumberAgain()

    fun confirm(typed: String) {
        viewModelScope.launch { flow.confirm(typed) }
    }

    /** The sheet was dismissed or the screen left. */
    fun clear() = flow.clear()

    override fun onCleared() {
        flow.clear()
    }
}
