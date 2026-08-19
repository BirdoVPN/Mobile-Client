package app.birdo.vpn.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.birdo.vpn.BuildConfig
import app.birdo.vpn.data.model.AppUpdateInfo
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-update nudge state, driven by GET /updates/android/{version}.
 *
 * Non-invasive by design:
 *  - checked once per process start (no periodic re-nag),
 *  - a failed/offline check shows NOTHING,
 *  - an optional update is a dismissible banner, and dismissing it stays
 *    dismissed until a strictly NEWER version is published,
 *  - only a backend-declared `updateRequired` (the owner-set support floor
 *    that also 426s VPN connects) renders as non-dismissible.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repository: BirdoRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class UpdateUiState(
        val info: AppUpdateInfo? = null,
        val showBanner: Boolean = false,
        val updateRequired: Boolean = false,
    )

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    init {
        check()
    }

    fun check() {
        viewModelScope.launch {
            when (val result = repository.checkAppUpdate()) {
                is ApiResult.Success -> {
                    val info = result.data
                    val dismissed = prefs.getString(KEY_DISMISSED_VERSION, null)
                    _state.value = UpdateUiState(
                        info = info,
                        showBanner = info.updateRequired ||
                            (info.updateAvailable && info.latestVersion != null &&
                                info.latestVersion != dismissed),
                        updateRequired = info.updateRequired,
                    )
                }
                is ApiResult.Error -> {
                    // Silent: never surface update UI off a failed check.
                }
            }
        }
    }

    /** Dismiss the optional banner for the currently-advertised version. */
    fun dismiss() {
        val current = _state.value
        // A required update is the one banner that must not be dismissible —
        // it mirrors the backend refusing connects.
        if (current.updateRequired) return
        current.info?.latestVersion?.let {
            prefs.edit().putString(KEY_DISMISSED_VERSION, it).apply()
        }
        _state.value = current.copy(showBanner = false)
    }

    /**
     * Where "Update" should send the user, per install channel. Play installs
     * go to the store listing (Play owns updating them); direct/F-Droid
     * installs go to the APK download, falling back to the release page and
     * finally the public clients page.
     */
    fun updateTargetUrl(): String {
        val info = _state.value.info
        return if (BuildConfig.IS_PLAY_BUILD) {
            PLAY_LISTING_URL
        } else {
            safeHttpsUrl(info?.downloadUrl) ?: safeHttpsUrl(info?.releaseUrl) ?: FALLBACK_CLIENTS_URL
        }
    }

    /**
     * The update URL is SERVER-SUPPLIED and launched via ACTION_VIEW into a
     * context where the user is conditioned to sideload the result. Enforce
     * https and an expected-host allow-list; anything else falls through to
     * [FALLBACK_CLIENTS_URL].
     */
    private fun safeHttpsUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        if (uri.scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val allowed = host == "birdo.app" || host.endsWith(".birdo.app") ||
            host == "github.com" || host.endsWith(".github.com") ||
            host.endsWith(".githubusercontent.com")
        return if (allowed) url else null
    }

    companion object {
        private const val PREFS_NAME = "birdo_update_prefs"
        private const val KEY_DISMISSED_VERSION = "dismissed_version"

        // The https form works on every device (no market:// intent gamble)
        // and Play still opens it in the store app when installed.
        private const val PLAY_LISTING_URL =
            "https://play.google.com/store/apps/details?id=app.birdo.vpn"
        private const val FALLBACK_CLIENTS_URL = "https://birdo.app/clients"
    }
}
