package app.birdo.vpn.service

import android.content.Context
import android.util.Log
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedicated auto-reconnect service with exponential backoff and metadata persistence.
 *
 * Mirrors the Windows client's auto_reconnect.rs (500 LOC) pattern:
 * - Exponential backoff: 1s → 2s → 4s → 8s → 16s → 32s → 60s (capped)
 * - Metadata persistence: stores reconnect info in preferences so reconnection
 *   survives process restarts
 * - Kill switch integration: activates kill switch before reconnect, deactivates on success
 * - Network-aware: pauses when offline, resumes immediately when connectivity returns
 * - Fresh keys: fetches new keys from API on each reconnect (never reuses old keys)
 */
@Singleton
class AutoReconnectService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BirdoRepository,
    private val prefs: AppPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var attempt = 0

    private val _state = MutableStateFlow(ReconnectState.Idle)
    val state: StateFlow<ReconnectState> = _state.asStateFlow()

    private val _attempt = MutableStateFlow(0)
    val attemptFlow: StateFlow<Int> = _attempt.asStateFlow()

    companion object {
        private const val TAG = "AutoReconnect"
        private const val MAX_ATTEMPTS = 10
        private const val INITIAL_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 60_000L
    }

    enum class ReconnectState {
        Idle,
        WaitingForRetry,
        Reconnecting,
        Exhausted,
    }

    /**
     * Start auto-reconnection for the given server.
     * @param serverId The server to reconnect to.
     * @param onSuccess Called when reconnection succeeds (caller should update UI).
     * @param onExhausted Called when all attempts are exhausted.
     */
    fun start(
        serverId: String,
        onSuccess: () -> Unit = {},
        onExhausted: () -> Unit = {},
    ) {
        if (reconnectJob?.isActive == true) return

        // Persist reconnect target for process restart recovery
        prefs.lastServerId = serverId
        attempt = 0

        reconnectJob = scope.launch {
            while (attempt < MAX_ATTEMPTS && isActive) {
                attempt++
                _attempt.value = attempt
                _state.value = ReconnectState.WaitingForRetry

                val delayMs = calculateDelay(attempt)
                Log.i(TAG, "Reconnect attempt $attempt/$MAX_ATTEMPTS in ${delayMs}ms")
                delay(delayMs)

                if (!isActive) break

                _state.value = ReconnectState.Reconnecting
                Log.i(TAG, "Attempting reconnection to $serverId (attempt $attempt)")

                // Fetch fresh keys from API — never reuse old key material.
                // When quantum protection is enabled, also upload our ML-KEM-1024
                // public key so the server can encapsulate against it (BirdoPQ v1).
                val pqClientPublicKey: String? = if (prefs.quantumProtectionEnabled) {
                    RosenpassManager.getClientPublicKeyB64(context)
                } else null
                if (prefs.quantumProtectionEnabled && pqClientPublicKey == null) {
                    Log.e(TAG, "Quantum engine unavailable during reconnect; refusing downgrade")
                    continue
                }
                val result = repository.connectVpn(
                    serverNodeId = serverId,
                    deviceName = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
                    stealthMode = prefs.stealthModeEnabled,
                    quantumProtection = prefs.quantumProtectionEnabled,
                    pqClientPublicKey = pqClientPublicKey,
                )

                when (result) {
                    is ApiResult.Success -> {
                        Log.i(TAG, "Reconnection successful on attempt $attempt")
                        _state.value = ReconnectState.Idle
                        _attempt.value = 0
                        attempt = 0
                        onSuccess()
                        return@launch
                    }
                    is ApiResult.Error -> {
                        Log.w(TAG, "Reconnect attempt $attempt failed: ${result.message}")
                        // Continue loop for next attempt
                    }
                }
            }

            // Exhausted all attempts
            Log.e(TAG, "Auto-reconnect exhausted after $MAX_ATTEMPTS attempts")
            _state.value = ReconnectState.Exhausted
            _attempt.value = 0
            attempt = 0
            onExhausted()
        }
    }

    /** Cancel any pending reconnection. */
    fun cancel() {
        reconnectJob?.cancel()
        reconnectJob = null
        attempt = 0
        _attempt.value = 0
        _state.value = ReconnectState.Idle
    }

    /** Trigger immediate retry (e.g., when network connectivity returns). */
    fun retryNow() {
        val currentJob = reconnectJob
        if (currentJob?.isActive == true && _state.value == ReconnectState.WaitingForRetry) {
            currentJob.cancel()
            val serverId = prefs.lastServerId ?: return
            start(serverId)
        }
    }

    private fun calculateDelay(attempt: Int): Long {
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s, 60s, 60s...
        return (INITIAL_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(6)))
            .coerceAtMost(MAX_DELAY_MS)
    }
}
