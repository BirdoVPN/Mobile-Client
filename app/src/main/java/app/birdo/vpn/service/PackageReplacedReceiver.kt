package app.birdo.vpn.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.utils.FaultReporter

/**
 * Restores the session after an app update (A1-015).
 *
 * An update kills the process — tunnel and kill-switch block included — and
 * nothing brought either back: a Play auto-update overnight left the user
 * unprotected (kill switch off) or with no block at all, with no signal. When
 * the user's session should be up, this starts BirdoVpnService with
 * [BirdoVpnService.ACTION_HEADLESS_CONNECT], a system start: block first when
 * the kill switch or lockdown asks for it, then a headless connect.
 *
 * ACTION_MY_PACKAGE_REPLACED is on Android's documented list of exemptions for
 * starting a foreground service from the background. With Always-on set the
 * platform also restarts the VPN after an update; VpnManager.connectHeadless
 * de-duplicates the two.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!AppPreferences(context.applicationContext).sessionShouldBeUp) return
        try {
            context.startForegroundService(
                Intent(context, BirdoVpnService::class.java)
                    .setAction(BirdoVpnService.ACTION_HEADLESS_CONNECT),
            )
        } catch (e: Exception) {
            // The update ended a session the user wanted up, and it is not
            // coming back on its own. The one witness is this report.
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "package_replaced_restart_failed",
                "Could not restart the VPN service after an app update — the session stays down",
                e,
            )
        }
    }
}
