package app.birdo.vpn

import app.birdo.vpn.utils.FaultReporter
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.transport.NoOpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Regression pins for issue "every failure on the connect, kill-switch and
 * native-integrity paths is reported to nobody".
 *
 * The defect was not a wrong line of code — it was an ABSENCE. `Log.e` is
 * stripped from release builds by `-assumenosideeffects` in
 * app/proguard-rules.pro, and `Sentry` was called from exactly one place in the
 * whole app (TokenManager), so on the shipped artifact a wg-go failure or a
 * kill switch that refused to arm produced nothing at all: no crash, no log
 * line, no event. The operator's first signal was a user writing in.
 *
 * An absence cannot be caught by a behavioural test on the code that is
 * missing, so these are structural scans of the REAL shipped sources — the same
 * technique PrivacyBoundaryTest uses. They fail the build when a future change
 * puts a data-plane failure back on a channel that R8 deletes.
 *
 * Two rounds. The first closed the file the finding cited (WgNative) and the
 * kill-switch root. An adversarial review then showed the finding AS STATED
 * was still open: twelve silent branches in the very file that had been
 * edited, a second state funnel (VpnManager) with seventeen unguarded error
 * branches — including the outermost kill-switch failure — the stealth and PQ
 * paths untouched, and a regression pin with a demonstrable false PASS (a
 * fixed 900-character window that ran into the NEXT function's report). The
 * pins below are the second round's; each names the shape it stops.
 */
class DataplaneFaultReportingTest {

    /** Repo root, found by walking up from the test working dir. */
    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    /**
     * The files that make up the connect, kill-switch, stealth, PQ and
     * native-integrity paths — the domain of the per-file report pins below.
     *
     * This list used to carry the whole Log.e invariant, with a note saying "a
     * new data-plane file must be added here to be covered". That note was
     * prose, not a check, and it was already wrong: BirdoTileService was
     * excluded as "the Quick Settings tile (UI)" while carrying SEVEN
     * error-severity logs on the connect path — including the multi-hop
     * refusal whose own comment says the result "must be VISIBLE" — and
     * SettingsHmac was not considered at all though it is the integrity gate
     * on `kill_switch_enabled` and the Multi-Hop route.
     *
     * So the Log.e invariant is now app-wide with a named allowlist (see
     * `no file logs at error severity outside a named exception`): a new file
     * cannot escape by being absent from a list. This list only decides WHICH
     * fault codes are pinned per file, which is a judgement no scan can make.
     */
    private val dataPlaneFiles = listOf(
        "app/src/main/java/app/birdo/vpn/service/WgNative.kt",
        "app/src/main/java/app/birdo/vpn/service/BirdoVpnService.kt",
        "app/src/main/java/app/birdo/vpn/service/VpnManager.kt",
        "app/src/main/java/app/birdo/vpn/service/TunnelMonitor.kt",
        "app/src/main/java/app/birdo/vpn/service/TransportProbe.kt",
        "app/src/main/java/app/birdo/vpn/service/RosenpassNative.kt",
        "app/src/main/java/app/birdo/vpn/service/RosenpassManager.kt",
        "app/src/main/java/app/birdo/vpn/service/XrayManager.kt",
        "app/src/main/java/app/birdo/vpn/service/BirdoTileService.kt",
        "app/src/main/java/app/birdo/vpn/service/WireGuardConfigBuilder.kt",
        "app/src/main/java/app/birdo/vpn/utils/NativeLibraryVerifier.kt",
        "app/src/main/java/app/birdo/vpn/utils/SettingsHmac.kt",
    )

    /**
     * The other files under `service/`, each with the reason it is NOT on the
     * data plane. Stated here rather than in prose so
     * `every file under service is classified` can hold the two sets to the
     * directory: a new file must be put in one of them before the build is
     * green, which is what the old "must be added here to be covered" note
     * only asked for politely.
     */
    private val notDataPlaneFiles = mapOf(
        "VpnNotificationManager.kt" to
            "builds and posts notifications; its one catch is a failed notification post, " +
            "which changes nothing about whether traffic is protected",
        "RosenpassKeyStore.kt" to
            "PQ key persistence. Both catches recover locally (keep on-disk state / delete " +
            "partial state) and the PQ VERDICT that results is reported by RosenpassManager, " +
            "so reporting here would double-count one outcome",
    )

    private fun source(path: String): String {
        val file = File(repoRoot, path)
        assertTrue("scan target is missing: $path — this test would be vacuous", file.isFile)
        val text = file.readText()
        assertTrue("scan target $path is empty", text.length > 200)
        return text
    }

    /**
     * Kotlin source with comments removed, so a scan cannot be fooled — in
     * either direction — by prose that quotes the pattern it is looking for.
     */
    private fun stripComments(text: String): String =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    private fun strippedSource(path: String): String = stripComments(source(path))

    /** Every shipped Kotlin file under app/src/main. */
    private fun shippedSources(): List<File> {
        val files = File(repoRoot, "app/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        // Vacuity guard: an empty walk would make the scans below pass for free.
        assertTrue(
            "source walk found only ${files.size} files — the walker is broken",
            files.size >= 50,
        )
        return files
    }

    /**
     * Every `catch (name: Type) { … }` block in [text] as (offset, body),
     * brace-matched so each block is judged on ITS OWN contents.
     *
     * The previous scanner took a fixed 900-character window after each
     * catch, which ran into the next function's report call: with the
     * `wg_get_socket_v6_failed` or `wg_turn_off_failed` report deleted from
     * WgNative.kt it still passed. `the catch scanner judges each block on its
     * own contents` pins this helper against that exact shape.
     */
    private fun catchBlocks(text: String): List<Pair<Int, String>> =
        Regex("""catch\s*\(\s*\w+\s*:[^)]*\)\s*\{""").findAll(text).map { m ->
            val open = m.range.last
            var depth = 0
            var end = -1
            var i = open
            scan@ while (i < text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            end = i
                            break@scan
                        }
                    }
                }
                i++
            }
            assertTrue("unbalanced braces after the catch at offset ${m.range.first}", end > open)
            m.range.first to text.substring(open, end + 1)
        }.toList()

    /** The literal spelling every report call must use, so a scan can see it. */
    private val scannableReport =
        Regex("FaultReporter\\.report\\(\\s*FaultReporter\\.PATH_\\w+,\\s*\"([^\"\$]+)\"")

    private fun reportedCodes(text: String): List<String> =
        scannableReport.findAll(text).map { it.groupValues[1] }.toList()

    private fun assertReports(path: String, codes: List<String>) {
        val present = reportedCodes(strippedSource(path)).toSet()
        val missing = codes.filterNot { it in present }
        assertEquals(
            "$path no longer reports these fault codes: $missing. If the branch was removed, " +
                "remove its pin with it; if it was merely reworded, keep reporting it — on the " +
                "shipped artifact a Log.e is not a weaker signal, it is no signal.",
            emptyList<String>(),
            missing,
        )
    }

    /**
     * For a JNI bridge, where every function converts a failure into a
     * sentinel return: an ignored exception is a blind spot by construction,
     * so anonymous catches are banned outright and every bound catch must
     * report before it returns its sentinel.
     */
    private fun assertEveryCatchReports(path: String, minimumCatches: Int) {
        val text = strippedSource(path)
        assertFalse(
            "$path has an anonymous catch (`catch (_:`). Every failure in a JNI bridge is " +
                "swallowed into a sentinel return, so an unreported one reaches nobody: Log.e " +
                "is stripped from release builds. Bind the exception and call FaultReporter.report.",
            Regex("""catch\s*\(\s*_\s*:""").containsMatchIn(text),
        )
        val blocks = catchBlocks(text)
        assertTrue("$path has only ${blocks.size} catch blocks — the scan is vacuous", blocks.size >= minimumCatches)
        blocks.forEach { (start, body) ->
            assertTrue(
                "$path: the catch block at offset $start does not call FaultReporter.report. " +
                    "A swallowed exception here is silent in the release artifact.\n---\n" +
                    "${body.take(300)}\n---",
                body.contains("FaultReporter.report("),
            )
        }
    }

    @Before
    fun clearThrottle() = FaultReporter.resetThrottleForTest()

    // ── The JNI bridges ───────────────────────────────────────────────────

    @Test
    fun `WgNative reports every exception it swallows`() =
        assertEveryCatchReports("app/src/main/java/app/birdo/vpn/service/WgNative.kt", 5)

    /**
     * The structural twin of WgNative: a `System.load` catch that converts
     * failure into a silent capability downgrade (BirdoPQ off).
     * scripts/check_r8_keeps.py names that outcome as reason #2 it exists.
     */
    @Test
    fun `RosenpassNative reports every exception it swallows`() =
        assertEveryCatchReports("app/src/main/java/app/birdo/vpn/service/RosenpassNative.kt", 2)

    /** Tests the test: the shape that produced the false PASS must FAIL. */
    @Test
    fun `the catch scanner judges each block on its own contents`() {
        val snippet = """
            fun a(): Int =
                try { x() } catch (e: Exception) { -1 }
            fun b(): Int =
                try { y() } catch (e: Exception) {
                    FaultReporter.report(FaultReporter.PATH_TUNNEL, "b_failed", "b threw", e)
                    -1
                }
        """.trimIndent()
        val blocks = catchBlocks(snippet)
        assertEquals(2, blocks.size)
        assertFalse(
            "a catch whose report was deleted must be seen as unreported even when the next " +
                "function reports within a few hundred characters",
            blocks[0].second.contains("FaultReporter.report("),
        )
        assertTrue(blocks[1].second.contains("FaultReporter.report("))
    }

    /**
     * `as? Int ?: -1` returned the same -1 for "socket not open yet" (normal)
     * and for "the reflection handle was never resolved" (the socket will
     * never be protected) — an elvis that hid a branch. turnOn was fixed in
     * round one; the two socket getters kept the shape, and getConfig's bare
     * `as? String` folded a changed return type into "stall".
     */
    @Test
    fun `WgNative never folds a missing bridge result into a sentinel`() {
        val path = "app/src/main/java/app/birdo/vpn/service/WgNative.kt"
        val elvisAfterCast = Regex("""as\?\s*\w+\s*\?:""").findAll(strippedSource(path)).map { it.value }.toList()
        assertEquals(
            "$path folds a failed cast into a default with `as? T ?:`, which makes an " +
                "unresolved JNI handle indistinguishable from a normal sentinel. Test for null " +
                "and report: $elvisAfterCast",
            emptyList<String>(),
            elvisAfterCast,
        )
        assertReports(
            path,
            listOf(
                "wg_turn_on_no_result",
                "wg_get_socket_v4_no_result",
                "wg_get_socket_v6_no_result",
                "wg_get_config_unexpected_type",
            ),
        )
    }

    // ── The kill switch ───────────────────────────────────────────────────

    /**
     * A kill switch that fails to arm leaves traffic in the clear while the UI
     * and the notification both say "protected". Round one pinned the service
     * side. The OUTERMOST failure lives in VpnManager: if the KILL_SWITCH_BLOCK
     * intent does not dispatch, the service is never reached and none of its
     * reports can fire — and if the intent is accepted but the block never
     * comes up, the manager is the only thing that knows.
     */
    @Test
    fun `kill switch arming failures are reported, not only logged`() {
        val service = "app/src/main/java/app/birdo/vpn/service/BirdoVpnService.kt"
        val manager = "app/src/main/java/app/birdo/vpn/service/VpnManager.kt"

        assertReports(
            service,
            listOf(
                // activateKillSwitch: establish() returned null — no throwable at all.
                "kill_switch_establish_refused",
                // activateKillSwitch: Builder/establish threw.
                "kill_switch_activate_threw",
                // Re-arm after a system restart (START_STICKY relaunch).
                "kill_switch_rearm_failed_restart",
                // Re-arm for a session the backend invalidated.
                "kill_switch_rearm_failed_invalidated",
                // An unreadable preference on restart decides "do not block":
                // fail-open by design, and it must not be silent.
                "kill_switch_pref_unreadable_restart",
            ),
        )
        assertReports(
            manager,
            listOf(
                // startForegroundService(KILL_SWITCH_BLOCK) threw: never armed, service never reached.
                "kill_switch_block_dispatch_failed",
                // Intent accepted, block not confirmed inside the wait.
                "kill_switch_block_unconfirmed",
                // The fail-closed teardown that holds the block across a rebuild.
                "switch_teardown_dispatch_failed",
                // The push that carries the kill-switch preference to a live service.
                "settings_push_dispatch_failed",
            ),
        )

        // The specific regression: this phrase used to appear ONLY inside a
        // Log.e, which R8 deletes.
        listOf(service, manager).forEach { path ->
            val loggedOnly = strippedSource(path).lines().filter {
                (it.contains("traffic is NOT blocked") || it.contains("traffic is NOT protected")) &&
                    Regex("""Log\.[a-z]+\(""").containsMatchIn(it)
            }
            assertEquals(
                "$path routes a 'traffic is NOT blocked/protected' message through android.util.Log, " +
                    "which is stripped from release builds. Route it through " +
                    "FaultReporter.report instead. Offending lines: $loggedOnly",
                emptyList<String>(),
                loggedOnly,
            )
        }
    }

    // ── The connect path ──────────────────────────────────────────────────

    /**
     * Every refusal and every swallowed failure between "user tapped Connect"
     * and "Connected" in the service. The two that matter most were the
     * silent twins of branches round one DID report: the tunnel-side
     * establish()==null (identical to the kill-switch one, and reported
     * nowhere), and the transport-probe catch that publishes Connected on no
     * evidence — which, because it publishes Connected and not Error, the
     * updateState breadcrumb never sees either.
     */
    @Test
    fun `the connect path reports every refusal`() {
        assertReports(
            "app/src/main/java/app/birdo/vpn/service/BirdoVpnService.kt",
            listOf(
                "connect_no_config",
                "connect_refused_debugger",
                "connect_refused_quantum_not_granted",
                "connect_refused_stealth_not_granted",
                "connect_refused_stealth_unavailable",
                "connect_refused_stealth_start_failed",
                "connect_refused_pq_payload_missing",
                "connect_refused_pq_exchange_failed",
                "connect_refused_integrity",
                "connect_engine_unavailable",
                "connect_establish_refused",
                "connect_v4_default_route_failed",
                // The connect proceeds without the ::/0 blackhole: leak-shaped.
                "connect_v6_blackhole_route_failed",
                "wg_turn_on_rejected",
                "transport_probe_threw",
                "start_tunnel_threw",
                "tunnel_setup_throwable",
                // No callback: works until the first roam, then stalls uncaused.
                "network_callback_register_failed",
            ),
        )
    }

    /**
     * Holds both lists to the directory. `dataPlaneFiles` had become
     * decorative — after the Log.e rule went app-wide nothing read it, so its
     * KDoc described a job it no longer did. These two assertions give it one:
     * classification is mandatory, and a file declared to be on the data plane
     * must actually report something.
     *
     * That second half covers what the Log.e rule cannot. The app-wide scan
     * catches "logged at error severity instead of reported"; it is blind to a
     * failure swallowed with NO log at all, which is how
     * `catch (_: Exception) {}` on the DNS loop in WireGuardConfigBuilder
     * survived every scan in round two.
     */
    @Test
    fun `every file under service is classified, and each data-plane file reports`() {
        val dir = File(repoRoot, "app/src/main/java/app/birdo/vpn/service")
        val onDisk = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name }
            .toSet()
        assertTrue("no sources found under service/ — the scan is vacuous", onDisk.size >= 10)

        val declaredInService = dataPlaneFiles
            .filter { it.contains("/service/") }
            .map { it.substringAfterLast('/') }
            .toSet()
        assertEquals(
            "file(s) under service/ that are neither declared data-plane nor listed in " +
                "notDataPlaneFiles with a reason. Decide which it is: if a failure there can " +
                "leave the user unprotected, unconnected or leaking, add it to dataPlaneFiles " +
                "and pin its fault codes; if not, say why in notDataPlaneFiles",
            emptySet<String>(),
            onDisk - declaredInService - notDataPlaneFiles.keys,
        )
        assertEquals(
            "declared under service/ but no longer on disk — renamed or deleted; update " +
                "dataPlaneFiles so it keeps describing the tree",
            emptySet<String>(),
            declaredInService - onDisk,
        )
        assertEquals(
            "stale entries in notDataPlaneFiles — the file is gone",
            emptySet<String>(),
            notDataPlaneFiles.keys - onDisk,
        )

        // Declared data-plane, reports nothing: either it has no failure branch
        // (so it does not belong on the list) or it has one that reaches nobody.
        val silent = dataPlaneFiles.filter { reportedCodes(strippedSource(it)).isEmpty() }
        assertEquals(
            "declared data-plane files that call FaultReporter.report ZERO times: $silent",
            emptyList<String>(),
            silent,
        )
    }

    /**
     * The connect path builds its config here, and three failures were folded
     * into silence — two of them into `catch (_: Exception) {}`, which logs
     * nothing at all and so is invisible to every Log-based scan.
     *
     * `config_dns_all_rejected` is the leak: with no DNS server accepted the
     * interface carries none, the handset falls back to the network-provided
     * resolver, and the user's queries leave outside the tunnel — precisely
     * what a custom DNS setting exists to prevent. `config_allowed_ip_skipped`
     * is the other one: a PARTIAL skip (::/0 dropped, 0.0.0.0/0 kept) leaves
     * IPv6 routing outside the tunnel, and the `check()` below it only catches
     * a TOTAL skip.
     *
     * The file's remaining `catch (_: Exception)` sites are the three
     * validators (isValidDnsAddress, isValidWireGuardKey, isValidCidr) where an
     * exception IS the answer "invalid" and the caller acts on the returned
     * boolean — deliberately left alone.
     */
    /**
     * The probe's own fail-open. `canReadConfig() == false` returns
     * HANDSHAKE_OK — correct behaviour (a false BLOCKED would cost every user
     * on that build a needless reconnect onto the slow transport) but it is
     * the SAME "Connected with nothing behind it" state as
     * transport_probe_threw, and it was Log.i: below even the severity the
     * app-wide Log.e rule looks at. Found by the classification test above,
     * which noticed TransportProbe was declared data-plane and reported
     * nothing at all.
     *
     * BLOCKED stays Log.w: it is a measured outcome that drives the Adaptive
     * Transport fallback, not a failure of the client.
     */
    @Test
    fun `the transport probe reports when it cannot probe`() {
        assertReports(
            "app/src/main/java/app/birdo/vpn/service/TransportProbe.kt",
            listOf("transport_probe_unavailable"),
        )
    }

    @Test
    fun `the tunnel config builder reports what it silently dropped`() {
        assertReports(
            "app/src/main/java/app/birdo/vpn/service/WireGuardConfigBuilder.kt",
            listOf(
                "config_ipv6_address_rejected",
                "config_dns_server_rejected",
                "config_dns_all_rejected",
                "config_mtu_rejected",
                "config_allowed_ip_skipped",
            ),
        )
    }

    /**
     * The Quick Settings tile is a THIRD connect entry point. It injects
     * VpnManager directly and never passes through VpnViewModel, so nothing
     * upstream covers it — and a throw out of quickConnect / connectMultiHop /
     * disconnect unwinds past VpnManager entirely, which means no
     * publishError, no VpnState.Error and therefore no breadcrumb either. The
     * user's tap does nothing and every channel stays quiet.
     *
     * The multi-hop REFUSAL logs are deliberately Log.w, not reports: each
     * non-success return from connectMultiHop already publishes a
     * VpnState.Error, and the two jurisdiction-leak refusals report at their
     * root as multihop_route_unconfirmed / _mismatch. A second code here would
     * be a duplicate throttle bucket, not extra coverage.
     */
    @Test
    fun `the Quick Settings tile reports the taps that do nothing`() {
        assertReports(
            "app/src/main/java/app/birdo/vpn/service/BirdoTileService.kt",
            listOf(
                "tile_connect_threw",
                "tile_multihop_threw",
                "tile_disconnect_threw",
                // Cannot act and cannot hand off: a no-op tap with no UI anywhere.
                "tile_no_launch_intent",
            ),
        )
    }

    /**
     * SettingsHmac is the integrity gate on `kill_switch_enabled`,
     * `local_network_sharing` and the Multi-Hop route — the settings that
     * decide whether traffic is blocked and where it leaves the network. Its
     * verdicts and both of its fail-open branches were Log.e only.
     *
     * `settings_sign_failed` is the sharp one: the settings are left unsigned,
     * so the NEXT launch reads that as tampering and wipes the user's kill
     * switch — a self-inflicted reset indistinguishable from an attack, with
     * nothing recording which it was. `settings_reset_failed` is the other
     * direction: settings already judged untrustworthy stay in force.
     *
     * MainActivity's own mismatch line stays Log.w for the same reason the
     * tile's refusals do — verify() reports the cause at its root — but the
     * catch AROUND the whole check reports, because a throw there means
     * nothing was verified and nothing was reset.
     */
    @Test
    fun `the settings integrity gate reports its verdicts and its fail-opens`() {
        assertReports(
            "app/src/main/java/app/birdo/vpn/utils/SettingsHmac.kt",
            listOf(
                "settings_hmac_missing",
                "settings_hmac_mismatch",
                "settings_hmac_verify_threw",
                "settings_sign_failed",
                "settings_reset_failed",
            ),
        )
        assertReports(
            "app/src/main/java/app/birdo/vpn/MainActivity.kt",
            listOf("settings_integrity_check_threw"),
        )
    }

    /**
     * FaultReporter's own throttle exists because "socket protect runs every
     * cycle" — yet only WgNative's JNI *getter* throw was reported. These are
     * the two actual protect() call sites; when protect() itself throws,
     * wg-go's own UDP socket is routed back into the tunnel.
     */
    @Test
    fun `both socket protect call sites report`() {
        assertReports("app/src/main/java/app/birdo/vpn/service/BirdoVpnService.kt", listOf("socket_reprotect_failed"))
        assertReports("app/src/main/java/app/birdo/vpn/service/TunnelMonitor.kt", listOf("socket_protect_failed"))
    }

    /** Native-integrity verdicts only ever happen on builds we did not ship. */
    @Test
    fun `native integrity failures are reported`() {
        assertReports(
            "app/src/main/java/app/birdo/vpn/utils/NativeLibraryVerifier.kt",
            listOf(
                "native_lib_missing",
                "integrity_no_hash_no_signature",
                "integrity_repackaged_apk",
                "integrity_hash_mismatch_untrusted",
                // Accepted, but only because the signature saved it: this is how we
                // learn our own build pipeline injected a stale NATIVE_HASH_*.
                "integrity_hash_stale_accepted_by_signature",
                // Its twin: a BLANK hash accepted via signature — the injection
                // task never ran. Was the one accepted branch left unreported.
                "integrity_no_hash_accepted_by_signature",
                "integrity_verify_threw",
            ),
        )
        // The verifier says WHY; these say what it COST the user — the twin
        // of the service's connect_refused_integrity on the other two libs.
        assertReports("app/src/main/java/app/birdo/vpn/service/RosenpassNative.kt", listOf("pq_disabled_integrity"))
        assertReports("app/src/main/java/app/birdo/vpn/service/XrayManager.kt", listOf("stealth_binary_integrity_failed"))
    }

    // ── The two state funnels ─────────────────────────────────────────────

    /**
     * The catch-all. ~19 sites publish a VpnState.Error and more will be added;
     * requiring each new one to remember a report call is the exact shape of
     * bug this ticket was. updateState is the single funnel they all pass
     * through IN THE SERVICE, so a breadcrumb there covers every present and
     * future branch there.
     */
    @Test
    fun `every VpnState Error leaves a breadcrumb via updateState`() {
        val text = source("app/src/main/java/app/birdo/vpn/service/BirdoVpnService.kt")
        val body = text.substringAfter("private fun updateState(newState: VpnState) {")
            .substringBefore("\n        }")
        assertTrue(
            "BirdoVpnService.updateState no longer trails VpnState.Error. It is " +
                "the only funnel every error branch passes through; without it a " +
                "new error branch is invisible again.",
            body.contains("FaultReporter.trail(") && body.contains("VpnState.Error"),
        )
    }

    /**
     * VpnManager is a SECOND, independent state funnel — a private
     * MutableStateFlow with, before this pin, seventeen direct
     * `_state.value = VpnState.Error(…)` assignments and no funnel at all. The
     * claim "updateState covers every error branch" was true only inside the
     * service. Now the only VpnState.Error this class may construct is the one
     * inside publishError, which leaves the breadcrumb; the branches that are
     * failures rather than outcomes also report.
     */
    @Test
    fun `VpnManager publishes every Error through one funnel`() {
        val path = "app/src/main/java/app/birdo/vpn/service/VpnManager.kt"
        val text = strippedSource(path)
        val constructions = Regex("""VpnState\.Error\(""").findAll(text).map { it.range.first }.toList()
        assertEquals(
            "$path constructs VpnState.Error in ${constructions.size} places. The only one allowed " +
                "is inside publishError, which leaves the breadcrumb — route the new branch " +
                "through publishError(message).",
            1,
            constructions.size,
        )
        val funnelStart = text.indexOf("private fun publishError(message: String) {")
        assertTrue("$path has lost its publishError funnel", funnelStart >= 0)
        val funnelEnd = text.indexOf("\n    }", funnelStart)
        assertTrue("publishError has no closing brace", funnelEnd > funnelStart)
        assertTrue(
            "the one VpnState.Error construction in $path must be inside publishError",
            constructions[0] in funnelStart..funnelEnd,
        )
        assertTrue(
            "VpnManager.publishError no longer trails — every error branch in the manager is silent again",
            text.substring(funnelStart, funnelEnd).contains("FaultReporter.trail("),
        )
        assertReports(
            path,
            listOf(
                "service_start_dispatch_failed",
                "service_start_dispatch_failed_multihop",
                "service_stop_dispatch_failed",
                // The jurisdiction-leak guard: the user cannot observe their own egress country.
                "multihop_route_unconfirmed",
                "multihop_route_mismatch",
                "connect_refused_pq_engine_unavailable",
                "multihop_refused_pq_engine_unavailable",
                // The sole pipeline from the service to the UI.
                "manager_state_collector_threw",
                "settings_reapply_threw",
            ),
        )
    }

    // ── The optional transports ───────────────────────────────────────────

    /** The stealth (Xray Reality) connect path: 18 Log.e/w sites, 0 reported. */
    @Test
    fun `the stealth path reports`() {
        assertReports(
            "app/src/main/java/app/birdo/vpn/service/XrayManager.kt",
            listOf(
                // The server's Xray parameters failing validation: a backend contract violation or a MitM.
                "stealth_config_incomplete",
                "stealth_uuid_invalid",
                "stealth_public_key_invalid",
                "stealth_short_id_invalid",
                "stealth_sni_invalid",
                "stealth_endpoint_invalid",
                "stealth_endpoint_parse_threw",
                // Start and stop.
                "stealth_start_failed_all_methods",
                "stealth_start_threw",
                "stealth_stop_failed",
                "stealth_libxray_rejected_config",
                "stealth_libxray_start_threw",
                "stealth_binary_missing",
                "stealth_binary_exited",
                "stealth_binary_start_threw",
            ),
        )
    }

    /** The BirdoPQ path: every abort that silently costs the user post-quantum protection. */
    @Test
    fun `the quantum path reports`() {
        assertReports(
            "app/src/main/java/app/birdo/vpn/service/RosenpassNative.kt",
            listOf("pq_native_load_failed", "pq_native_load_threw"),
        )
        assertReports(
            "app/src/main/java/app/birdo/vpn/service/RosenpassManager.kt",
            listOf(
                "pq_abort_no_bilateral_psk",
                "pq_nonce_missing",
                "pq_nonce_malformed",
                "pq_nonce_out_of_bounds",
                "pq_ciphertext_malformed",
                "pq_ciphertext_wrong_size",
                "pq_secret_key_wrong_size",
                "pq_derive_threw",
                "pq_derive_no_result",
                "pq_derived_psk_wrong_size",
                "pq_generate_keypair_threw",
                "pq_keypair_persist_failed",
            ),
        )
    }

    // ── The invariant behind all of the above ─────────────────────────────

    /**
     * On the shipped artifact a bare `Log.e` is not a quieter channel, it is
     * no channel. Error severity means FaultReporter.report (which does its
     * own Log.e for debug builds); a routine outcome is Log.w plus the
     * breadcrumb the state funnels leave. This is the rule every per-code pin
     * above is an instance of, and it is what stops the NEXT silent branch —
     * the one nobody has thought to pin.
     *
     * Scoped to the whole of app/src/main, NOT to [dataPlaneFiles]. When this
     * scan owned only a nine-file list, a file could stay silent simply by not
     * being on it, and two already had: BirdoTileService (seven error logs on
     * the connect path, excluded as "UI") and SettingsHmac (five, the
     * integrity gate on `kill_switch_enabled` and the Multi-Hop route). The
     * list said a new data-plane file "must be added here to be covered" —
     * prose, checked by nothing. An allowlist inverts that: absence from it is
     * the failing state, so the next file is covered on the day it is written.
     */
    @Test
    fun `no file logs at error severity outside a named exception`() {
        // Every entry is a path that MAY keep android.util.Log.e/wtf, with the
        // reason. Anything else under app/src/main fails this test, so a NEW
        // file cannot escape the rule by being absent from a list. A stale
        // entry fails it too (below), so the allowlist cannot rot into a claim
        // about a file that no longer logs.
        val allowed = mapOf(
            "utils/FaultReporter.kt" to
                "the reporter's own line — what every other error routes THROUGH; it is the " +
                    "convenience half, and the Sentry capture below it is the signal half",
            "BirdoApp.kt" to
                "Sentry and Play Billing initialisation: FaultReporter cannot report that " +
                    "Sentry itself failed to initialise, and billing is not a data-plane path",
            "data/auth/TokenManager.kt" to
                "the auth / Keystore-recovery path — a different finding, deliberately not " +
                    "reworked here. TokenManager is the one file that already talks to Sentry " +
                    "directly. Named so this is a DISCLOSED exclusion, not an invisible one",
        )
        var logCalls = 0
        val offenders = mutableListOf<String>()
        val allowedHits = mutableSetOf<String>()
        shippedSources().forEach { file ->
            val rel = file.absolutePath.replace(File.separatorChar, '/')
                .substringAfter("app/src/main/java/app/birdo/vpn/")
            val text = stripComments(file.readText())
            logCalls += Regex("""\bLog\.[a-z]+\(""").findAll(text).count()
            Regex("""\bLog\.(e|wtf)\(""").findAll(text).forEach { m ->
                if (rel in allowed) {
                    allowedHits += rel
                } else {
                    val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                    offenders += "$rel:~$line: " + text.substring(m.range.first).lineSequence().first().trim()
                }
            }
        }
        assertTrue("found only $logCalls Log calls under app/src/main — the scan is vacuous", logCalls >= 40)
        assertEquals(
            "android.util.Log.e/wtf outside the named exceptions. R8 deletes it from the " +
                "release build (-assumenosideeffects in proguard-rules.pro), so on the artifact " +
                "users run it reports to NOBODY. If it is an error, call FaultReporter.report " +
                "(it logs too); if it is a routine outcome, Log.w it and let the VpnState.Error " +
                "breadcrumb carry it; if it genuinely cannot use the reporter, add it to " +
                "`allowed` above WITH the reason. Offending: $offenders",
            emptyList<String>(),
            offenders,
        )
        // An allowlist nobody prunes becomes a false statement about coverage.
        assertEquals(
            "allowlisted for Log.e/wtf but no longer containing one — remove the entry so the " +
                "list keeps meaning what it says",
            emptySet<String>(),
            allowed.keys - allowedHits,
        )
    }

    /**
     * A duplicated code means two failures share one throttle bucket, so the
     * second is muted for five minutes by the first — silently. And a call the
     * scanner cannot parse is a code it cannot check: every report call site
     * must be spelled `FaultReporter.report(FaultReporter.PATH_x, "literal"`,
     * so the count of call sites and the count of scannable codes must agree.
     * The previous version matched the literal spelling only, so a site that
     * imported PATH_CONNECT directly, or passed a constant, was invisible.
     */
    @Test
    fun `fault codes are unique across the app and every report call is scannable`() {
        val callSite = Regex("""FaultReporter\.report\(""")
        var calls = 0
        val codes = mutableListOf<String>()
        val unscannable = mutableListOf<String>()
        shippedSources().forEach { file ->
            val text = stripComments(file.readText())
            assertFalse(
                "${file.name} imports a FaultReporter member directly. Spell every call " +
                    "FaultReporter.report(FaultReporter.PATH_x, \"code\", …) so this scan can see it.",
                text.contains("import app.birdo.vpn.utils.FaultReporter."),
            )
            val n = callSite.findAll(text).count()
            val found = reportedCodes(text)
            calls += n
            codes += found
            if (n != found.size) unscannable += "${file.name}: $n call(s), ${found.size} scannable"
        }
        assertTrue("found only $calls report call sites — the scan is vacuous", calls >= 60)
        assertEquals(
            "report calls this scan cannot read (a non-literal code, an interpolated code, or a " +
                "path not spelled FaultReporter.PATH_x): $unscannable",
            emptyList<String>(),
            unscannable,
        )
        val duplicates = codes.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals(
            "duplicate fault codes share a throttle bucket and mute each other: $duplicates",
            emptySet<String>(),
            duplicates,
        )
    }

    /**
     * The scrubber is the backstop, not the plan: no report may interpolate an
     * endpoint, a path or key material into its message in the first place.
     */
    @Test
    fun `report messages never interpolate an endpoint, a path or key material`() {
        val forbidden = Regex("\\$\\{?(xrayEndpoint|endpoint|settings|configString|configJson|absolutePath|privateKey|presharedKey|publicKey|resultStr)\\b")
        val offenders = shippedSources().flatMap { file ->
            val text = stripComments(file.readText())
            Regex("""FaultReporter\.report\(""").findAll(text).mapNotNull { m ->
                // The call's argument text, up to its matching close paren.
                var depth = 0
                var i = m.range.last
                var end = -1
                args@ while (i < text.length) {
                    when (text[i]) {
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) {
                                end = i
                                break@args
                            }
                        }
                    }
                    i++
                }
                val call = text.substring(m.range.first, if (end > 0) end + 1 else text.length)
                forbidden.find(call)?.let { "${file.name}: ${it.value} in ${call.take(160).replace('\n', ' ')}" }
            }.toList()
        }
        assertEquals("a report interpolates a value that must never leave the device: $offenders", emptyList<String>(), offenders)
    }

    // The invariant that keeps this whole channel alive in a release build —
    // `-assumenosideeffects` must name android.util.Log and NOTHING else, since
    // adding io.sentry there would delete the reporter silently (R8 does not
    // report what it removed) — is already pinned by
    // PrivacyBoundaryTest.`sentry is never added to the proguard strip list`
    // (P6-CLI-SENTRY-01). Not duplicated here on purpose: two copies of one pin
    // is how the pair drifts apart. If that test moves, this comment is the
    // pointer to follow.

    // ── Throttle behaviour ───────────────────────────────────────────────

    @Test
    fun `the first occurrence of a fault code always sends`() {
        assertTrue(FaultReporter.shouldSend("test_code_first"))
    }

    @Test
    fun `a repeat inside the window is throttled, and codes do not share a bucket`() {
        assertTrue(FaultReporter.shouldSend("test_code_a"))
        // The polling loops (socket protect, stall detection) call into the
        // reporter every cycle while a fault lasts; without this the quota is
        // gone in minutes.
        assertFalse(FaultReporter.shouldSend("test_code_a"))
        assertFalse(FaultReporter.shouldSend("test_code_a"))
        // A DIFFERENT failure must not be muted by an unrelated one — that
        // would turn the throttle into a single-event-per-process reporter.
        assertTrue(FaultReporter.shouldSend("test_code_b"))
        assertFalse(FaultReporter.shouldSend("test_code_b"))
        assertFalse(FaultReporter.shouldSend("test_code_a"))
    }

    /**
     * Unit tests never initialise Sentry, which is the real disabled state — the
     * one a release build is in between process start and SentryAndroid.init,
     * and permanently when the DSN is blank. A capture that is a no-op must not
     * spend the throttle window, or the first REAL occurrence after init is
     * muted for five minutes.
     */
    @Test
    fun `report does not consume the throttle window while Sentry is disabled`() {
        FaultReporter.report(FaultReporter.PATH_CONNECT, "test_code_disabled", "message")
        assertTrue(FaultReporter.shouldSend("test_code_disabled"))
    }

    /**
     * Breadcrumbs are never throttled against quota, but a reconnect loop
     * publishing the same VpnState.Error every few seconds would evict the
     * auto-instrumented crumbs from the 100-slot ring buffer — the context the
     * breadcrumb exists to preserve. Identical crumbs collapse; distinct ones,
     * and the same text on a different path, do not.
     */
    @Test
    fun `identical breadcrumbs inside the window are collapsed, distinct ones are not`() {
        assertTrue(FaultReporter.shouldTrail("connect", "state=Error: timed out"))
        assertFalse(FaultReporter.shouldTrail("connect", "state=Error: timed out"))
        assertTrue(FaultReporter.shouldTrail("connect", "state=Error: no handshake"))
        assertTrue(FaultReporter.shouldTrail("tunnel", "state=Error: timed out"))
        assertFalse(FaultReporter.shouldTrail("tunnel", "state=Error: timed out"))
    }

    /** The reporter runs inside catch blocks; it may never throw. */
    @Test
    fun `report and trail are safe when Sentry was never initialised`() {
        // Unit tests never call SentryAndroid.init, which is the same state a
        // debug build is in (initSentry returns early) — so this is the real
        // uninitialised path, not a mock of it.
        FaultReporter.report(FaultReporter.PATH_CONNECT, "test_code_no_sentry", "message")
        FaultReporter.report(
            FaultReporter.PATH_KILL_SWITCH,
            "test_code_no_sentry_throwable",
            "message",
            IllegalStateException("boom"),
        )
        FaultReporter.trail(FaultReporter.PATH_TUNNEL, "message")
    }

    /**
     * The enabled half of the throttle test above, and the only test that
     * exercises the channel END TO END against the real SDK: with Sentry
     * enabled, one report is one event carrying the two tags a fleet-wide
     * regression is filtered on, the throwable (with `exceptions` already
     * populated — the field BirdoApp's scrubber walks), and the breadcrumb
     * trail() left before it; a repeat inside the window is not a second
     * event; a different code is; and closing Sentry returns the reporter to
     * the no-op state. A no-op transport and a beforeSend that captures and
     * DROPS every event mean nothing leaves the JVM.
     */
    @Test
    fun `with Sentry enabled a report is one tagged event and a repeat is throttled`() {
        val captured = mutableListOf<SentryEvent>()
        Sentry.init { options ->
            options.dsn = "https://examplePublicKey@o0.ingest.sentry.io/0"
            options.isEnableUncaughtExceptionHandler = false
            options.isEnableShutdownHook = false
            options.isEnableBackpressureHandling = false
            options.isAttachServerName = false
            options.setTransportFactory { _, _ -> NoOpTransport.getInstance() }
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                captured += event
                null // captured for assertion, never sent
            }
        }
        try {
            assertTrue("Sentry.init did not enable the SDK — this test would be vacuous", Sentry.isEnabled())

            FaultReporter.trail(FaultReporter.PATH_TUNNEL, "context crumb")
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "test_code_enabled",
                "engine failed",
                IllegalStateException("boom"),
            )
            assertEquals("one report must raise exactly one event", 1, captured.size)
            val event = captured[0]
            assertEquals("connect", event.getTag("birdo.path"))
            assertEquals("test_code_enabled", event.getTag("birdo.fault"))
            assertEquals(SentryLevel.ERROR, event.level)
            assertTrue(
                "the message must carry the code so events group on it when there is no throwable",
                event.message?.formatted?.contains("test_code_enabled") == true,
            )
            assertTrue(
                "the throwable must reach the event so Sentry groups on its stack",
                event.throwable is IllegalStateException,
            )
            assertTrue(
                "exceptions must be populated before beforeSend, or the scrubber cannot walk them",
                !event.exceptions.isNullOrEmpty(),
            )
            assertTrue(
                "the breadcrumb trail() left must ride along on the event that follows it",
                event.breadcrumbs?.any { it.category == "birdo.tunnel" } == true,
            )

            // The window is spent now — and only now — that a capture happened.
            FaultReporter.report(FaultReporter.PATH_CONNECT, "test_code_enabled", "engine failed again")
            assertEquals("a repeat inside the window must not be a second event", 1, captured.size)
            FaultReporter.report(FaultReporter.PATH_KILL_SWITCH, "test_code_enabled_other", "different fault")
            assertEquals("a different code must not be muted by the first", 2, captured.size)
            assertEquals("kill-switch", captured[1].getTag("birdo.path"))
        } finally {
            Sentry.close()
        }
        assertFalse("Sentry.close() must return the SDK to the disabled state", Sentry.isEnabled())
        // ...and the reporter to costing nothing: no event, no window spent.
        FaultReporter.report(FaultReporter.PATH_CONNECT, "test_code_after_close", "message")
        assertEquals(2, captured.size)
        assertTrue(FaultReporter.shouldSend("test_code_after_close"))
    }
}
