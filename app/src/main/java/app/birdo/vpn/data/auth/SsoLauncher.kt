package app.birdo.vpn.data.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/** How the SSO broker page is opened. */
internal enum class SsoLaunch { CUSTOM_TAB, BROWSER }

/**
 * A Custom Tab whenever the device has a browser that provides one, the plain
 * browser otherwise.
 *
 * The broker used to open with a bare ACTION_VIEW + FLAG_ACTIVITY_NEW_TASK
 * (A2-016): sign-in left the app for a separate browser task, and after the
 * birdo://auth redirect the broker page stayed behind as a stray tab. A Custom
 * Tab runs in THIS task, above the singleTask MainActivity, so the redirect
 * that brings MainActivity forward also closes it. It is still the user's real
 * browser (cookies, password manager, the provider's own security checks),
 * which is why a WebView is not an option. iOS gets the same effect from
 * ASWebAuthenticationSession (SSOService.swift).
 */
internal fun ssoLaunchFor(customTabsProvider: String?): SsoLaunch =
    if (customTabsProvider != null) SsoLaunch.CUSTOM_TAB else SsoLaunch.BROWSER

/**
 * Opens [uri] per [ssoLaunchFor]. Seeing the default provider on API 30+ needs
 * the CustomTabsService `<queries>` entry in the manifest; without it
 * getPackageName answers null and every sign-in silently falls back to the
 * browser.
 */
internal fun openSsoBroker(context: Context, uri: Uri) {
    val provider = CustomTabsClient.getPackageName(context, null)
    when (ssoLaunchFor(provider)) {
        SsoLaunch.CUSTOM_TAB -> {
            val tab = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
                .setDefaultColorSchemeParams(
                    CustomTabColorSchemeParams.Builder().setToolbarColor(TOOLBAR_COLOR).build(),
                )
                .build()
            tab.intent.setPackage(provider)
            if (context !is Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tab.launchUrl(context, uri)
        }
        SsoLaunch.BROWSER -> context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** The app's dark surface (#0B0B10), so the tab reads as part of the flow. */
private const val TOOLBAR_COLOR = 0xFF0B0B10.toInt()
