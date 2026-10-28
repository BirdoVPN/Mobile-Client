package app.birdo.vpn

import app.birdo.vpn.data.model.ClientConfigResponse
import app.birdo.vpn.di.NetworkModule
import app.birdo.vpn.service.MultiHopPolicy
import app.birdo.vpn.service.StealthPolicy
import app.birdo.vpn.shared.model.PlanFeature
import app.birdo.vpn.shared.model.PlanGates
import app.birdo.vpn.shared.model.planHasFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wiring for a batch of register rows that lives in workflows, the Xcode
 * project and Swift views, pinned from the Android unit tests so every PR
 * checks it (the style of IosRequiredRowsGuardTest): Apple CI runs only when
 * iosApp/ changes, macos.yml and ios.yml only on a dispatch or a tag, and none
 * of the Swift views compiles into the un-hosted iOS test bundle. The pure
 * logic is tested where it lives (scripts/tests/extract_mac_screenshots_test.sh,
 * TunnelRoutesTests, AccountIdentityTests).
 */
class MobileMiscRowsGuardTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private fun source(path: String): String {
        val file = File(repoRoot, path)
        assertTrue("scan target is missing: $path — this test would be vacuous", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private val ios = "iosApp/iosApp"
    private val workflows by lazy {
        File(repoRoot, ".github/workflows").listFiles { f -> f.extension == "yml" }!!
            .associate { it.name to it.readText().replace("\r\n", "\n") }
            .also { assertTrue("found only ${it.size} workflows", it.size > 10) }
    }

    /** The YAML of the job whose header line is `  <id>:` in [workflow]. */
    private fun job(workflow: String, id: String): String {
        val yml = workflows.getValue(workflow)
        val start = yml.indexOf("\n  $id:\n")
        assertTrue("$workflow has no job $id", start >= 0)
        val next = Regex("""\n  [\w-]+:\n""").find(yml, start + 1)
        return yml.substring(start, next?.range?.first ?: yml.length)
    }

    // ── MR-1797: the Mac screenshot job can fail ─────────────────────────────

    @Test
    fun `the Mac screenshot job fails on a missing store screenshot and on an empty upload`() {
        val shots = job("macos.yml", "screenshots-macos")
        assertTrue(
            "the extraction step must run the tested script, not an inline copy",
            "python3 scripts/extract_mac_screenshots.py \"\$RUNNER_TEMP/att\" \"\$OUT\" || status=\$?" in shots &&
                "exit \"\$status\"" in shots,
        )
        assertFalse("an inline extractor is back (its 'n == 0' check passed one capture)", "PYEOF" in shots)
        assertTrue("the UI test's own exit must still decide", "status=(\"\${PIPESTATUS[@]}\")" in shots)
        val upload = shots.substringAfter("- name: Upload screenshots")
        assertTrue("an upload of nothing must fail", "if-no-files-found: error" in upload.substringBefore("- name:"))
        assertFalse("if-no-files-found: warn" in shots)

        val script = source("scripts/extract_mac_screenshots.py")
        assertTrue(
            "the four store screenshots are the required set",
            "REQUIRED = (\"00-launch\", \"01-connect\", \"02-servers\", \"03-settings\")" in script,
        )
        // The names the script requires are the names the UI test captures.
        val uiTest = source("iosApp/BirdoVPNUITests/ScreenshotTests.swift")
        listOf("00-launch", "01-connect", "02-servers", "03-settings", "99-stuck").forEach {
            assertTrue("ScreenshotTests no longer captures $it", "capture(\"$it\")" in uiTest)
        }
        assertTrue(
            "the self-test must run in the required Lint Check job",
            "run: bash scripts/tests/extract_mac_screenshots_test.sh" in job("android-ci.yml", "lint"),
        )
    }

    // ── MR-1085: no BirdoShared framework on the Apple side ──────────────────

    @Test
    fun `nothing builds or links the unused KMP Apple framework`() {
        val swift = listOf(ios, "iosApp/PacketTunnel", "iosApp/BirdoVPNTests").flatMap { dir ->
            File(repoRoot, dir).walkTopDown().filter { it.isFile && it.extension == "swift" }.map { it.readText() }.toList()
        }
        assertTrue("found only ${swift.size} Swift files", swift.size > 40)
        assertFalse(
            "a Swift file imports BirdoShared: then the framework is used, and this row's premise is gone",
            swift.any { Regex("""(?m)^\s*import\s+BirdoShared\b""").containsMatchIn(it) },
        )
        val projectYml = source("iosApp/project.yml").lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        assertFalse("project.yml links BirdoShared", "\"BirdoShared\"" in projectYml)
        assertFalse("project.yml searches the shared module's build output", "shared/build/bin" in projectYml)
        assertFalse("FRAMEWORK_SEARCH_PATHS" in projectYml)
        workflows.forEach { (name, yml) ->
            assertFalse("$name still links the KMP framework", Regex(""":shared:link\w*Framework""").containsMatchIn(yml))
            assertFalse("$name still moves the framework between jobs", "shared-framework" in yml)
        }
        // With no Gradle step left, no Apple job may still set up a JDK for it.
        listOf("apple-ci.yml", "codeql-swift.yml", "ios.yml", "macos.yml").forEach { name ->
            assertFalse("$name sets up a JDK nothing uses", "actions/setup-java" in workflows.getValue(name))
            assertFalse("$name runs Gradle", "./gradlew" in workflows.getValue(name))
        }
    }

    // ── MR-1362: the account number is masked by default on iOS too ──────────

    @Test
    fun `iOS Profile masks the account number until asked, and Home never shows it`() {
        val profile = source("$ios/Views/ProfileView.swift")
        assertTrue(
            "the number must start hidden",
            "@State private var accountNumberShown = false" in profile,
        )
        assertTrue(
            "hidden means Android's mask, shown means grouped",
            "accountNumberShown ? AccountNumberText.grouped(number) : AccountNumberText.masked(number)" in profile,
        )
        assertTrue("Profile must render the masked text", "Text(shownAccountNumber(number))" in profile)
        assertFalse("Profile renders the raw number again", "Text(number)" in profile)
        // MR-1094: revealing it asks for the device owner first (OwnerDecisionGuardTest).
        assertTrue("there must be a way to reveal it", "accountNumberShown = true" in profile)
        assertTrue("Copy still takes all of it", "copyAccountNumber(number)" in profile)

        val home = source("$ios/Views/HomeView.swift")
        val identity = home.substringAfter("private var accountIdentity: String? {").substringBefore("\n    }")
        assertFalse("Home shows the account number", "anonymousAccountNumber" in identity)
        assertTrue("Home labels an anonymous account as Android does", "return \"Anonymous account\"" in identity)

        // Same mask as Android's maskAnonymousId.
        val mask = source("$ios/Services/AccountIdentity.swift")
        assertTrue("enum AccountNumberText" in mask && "String(repeating: \"•\", count: group.count)" in mask)
    }

    // ── MR-953: the iOS route contract ───────────────────────────────────────

    @Test
    fun `iOS builds AllowedIPs through TunnelRoutes and a failed network-settings apply fails the start`() {
        val manager = source("$ios/Services/VPNManager.swift")
        assertTrue("let allowedIPs = TunnelRoutes.allowedIPs(config.allowedIPs)" in manager)
        assertFalse("an inline AllowedIPs floor is back in VPNManager", "allowedIPs.append(" in manager)
        val unitTests = source("iosApp/project.yml").substringAfter("\n  BirdoVPNTests:").substringBefore("\n  BirdoVPNUITests:")
        assertTrue("- path: iosApp/Services/TunnelRoutes.swift" in unitTests)
        assertTrue(File(repoRoot, "iosApp/BirdoVPNTests/TunnelRoutesTests.swift").isFile)

        // WireGuardKit throws when setTunnelNetworkSettings fails; the provider
        // must turn that into a failed start, never a tunnel reported up.
        val provider = source("iosApp/PacketTunnel/PacketTunnelProvider.swift")
        val start = provider.substringAfter("adapter.start(tunnelConfiguration: tunnelConfiguration) {")
        val onError = start.substringAfter("if let adapterError {").substringBefore("\n            }")
        assertTrue("completionHandler(TunnelError.adapterFailed(" in onError && "return" in onError)
        val adapter = source("iosApp/Vendor/wireguard-apple/Sources/WireGuardKit/WireGuardAdapter.swift")
        assertTrue("throw WireGuardAdapterError.setNetworkSettings(systemError)" in adapter)

        val review = source("docs/ROUTE-CONTRACT-REVIEW.md")
        listOf("## Android", "## iOS and macOS", "### Residual risk").forEach { assertTrue("the review lost $it", it in review) }
    }

    // ── MR-1517: both apps follow the server's plan-gating table ─────────────

    /**
     * The decided table (owner, 2026-10-07): birdo-web
     * app/api/client-config/route.ts `features.<PLAN>`, read at 367e3f95.
     */
    private val serverTable = mapOf(
        "RECON" to mapOf("splitTunneling" to true, "stealthMode" to false, "multiHop" to false, "portForwarding" to false, "customDns" to true),
        "OPERATIVE" to mapOf("splitTunneling" to true, "stealthMode" to true, "multiHop" to false, "portForwarding" to false, "customDns" to true),
        "SOVEREIGN" to mapOf("splitTunneling" to true, "stealthMode" to true, "multiHop" to true, "portForwarding" to true, "customDns" to true),
    )

    private val gatedFeatures = listOf("stealthMode", "multiHop", "portForwarding")

    /** [table] as the server serves it: `{"features":{"<PLAN>":{"<flag>":bool}}}`, decoded by THE wire Json. */
    private fun served(table: Map<String, Map<String, Boolean>>): PlanGates? {
        val body = table.entries.joinToString(",", "{\"features\":{", "}}") { (plan, flags) ->
            flags.entries.joinToString(",", "\"$plan\":{", "}") { (flag, on) -> "\"$flag\":$on" }
        }
        return NetworkModule.json.decodeFromString<ClientConfigResponse>(body).planGates
    }

    /** What each Android gate site answers for [plan] under [gates]. */
    private fun androidGates(plan: String, gates: PlanGates?): Map<String, Boolean> = mapOf(
        "stealthMode" to StealthPolicy.toggleUnlocked(plan, gates),
        "multiHop" to (MultiHopPolicy.entitledByPlan(plan, gates) == true),
        "portForwarding" to (planHasFeature(plan, gates, PlanFeature.PORT_FORWARDING) == true),
    )

    @Test
    fun `Android gates Stealth, Multi-Hop and Port Forwarding as the server table does`() {
        // The served table and the built-in fallback (no config yet) agree.
        serverTable.forEach { (plan, features) ->
            listOf(plan, plan.lowercase()).forEach { spelling ->
                listOf(served(serverTable), null).forEach { gates ->
                    androidGates(spelling, gates).forEach { (feature, unlocked) ->
                        assertEquals("$feature for $spelling, gates=$gates", features.getValue(feature), unlocked)
                    }
                }
            }
        }
        gatedFeatures.forEach { feature ->
            val lowest = serverTable.entries.first { it.value.getValue(feature) }.key
            assertEquals("built-in lowest plan for $feature", lowest, PlanFeature.entries.single { it.key == feature }.minPlan)
        }
        // A change to the server's table reaches every gate: flip each flag in a mocked `features` block.
        serverTable.forEach { (plan, features) ->
            gatedFeatures.forEach { feature ->
                val flipped = serverTable + (plan to features + (feature to !features.getValue(feature)))
                assertEquals(
                    "$feature for $plan after the server flipped it",
                    !features.getValue(feature),
                    androidGates(plan, served(flipped)).getValue(feature),
                )
            }
        }

        // The sites that are not plain functions read the same table.
        val nav = source("app/src/main/java/app/birdo/vpn/ui/navigation/BirdoNavGraph.kt")
        assertTrue(
            "Port Forwarding must gate on the plan table",
            Regex("""val portForwardEntitled = planHasFeature\(\s*vpnState\.subscription\?\.plan,\s*vpnState\.planGates,\s*PlanFeature\.PORT_FORWARDING,\s*\) == true""")
                .containsMatchIn(nav) && "portForwardUnlocked = portForwardEntitled," in nav,
        )
        assertTrue("stealthUnlocked = StealthPolicy.toggleUnlocked(plan, vpnState.planGates)," in nav)
        val home = source("app/src/main/java/app/birdo/vpn/ui/screen/HomeScreen.kt")
        assertTrue("val multiHopEntitled = MultiHopPolicy.entitledByPlan(state.subscription?.plan, state.planGates) == true" in home)
        val vm = source("app/src/main/java/app/birdo/vpn/ui/viewmodel/VpnViewModel.kt")
        assertTrue("the ViewModel must publish the fetched table", "planGates = gates," in vm)
        listOf("service/BirdoTileService.kt", "widget/BirdoWidget.kt").forEach {
            assertTrue(
                "$it dials on the plan table",
                "QuickToggle.connectPlan(decision, knownPlan, repository.cachedPlanGates())" in
                    source("app/src/main/java/app/birdo/vpn/$it"),
            )
        }
        assertTrue(
            "MultiHopPolicy.entitledByPlan(prefs.lastKnownPlan, repository.cachedPlanGates())" in
                source("app/src/main/java/app/birdo/vpn/service/VpnManager.kt"),
        )
        // No gate site compares a plan name any more.
        listOf(nav, home, vm).forEach {
            assertFalse(Regex("\"(SOVEREIGN|OPERATIVE)\"").containsMatchIn(it))
        }
        // Split tunnelling and Custom DNS are on every plan: no lock exists to pass.
        val settings = source("app/src/main/java/app/birdo/vpn/ui/screen/SettingsScreen.kt")
        assertFalse(Regex("""(?i)(split\w*|customDns\w*)Unlocked""").containsMatchIn(settings))
    }

    @Test
    fun `the Android paywall lists each gated feature under the plans the server table gives it`() {
        val screen = source("app/src/main/java/app/birdo/vpn/ui/screen/SubscriptionScreen.kt")
        val lines = mapOf(
            "splitTunneling" to "R.string.subscription_feature_split_tunneling",
            "stealthMode" to "R.string.subscription_feature_stealth",
            "multiHop" to "R.string.subscription_feature_multi_hop",
            "portForwarding" to "R.string.subscription_feature_port_forwarding",
            "customDns" to "R.string.subscription_feature_custom_dns",
        )
        serverTable.forEach { (plan, features) ->
            val card = screen.substringAfter("id = \"$plan\",").substringBefore("PlanInfo(")
            assertTrue("no paywall card for $plan", card.length < screen.length)
            features.forEach { (feature, included) ->
                assertEquals("$plan card and $feature", included, lines.getValue(feature) in card)
            }
        }
    }

    @Test
    fun `iOS gates Multi-Hop and Port Forwarding on the plan table and lists them only on Sovereign`() {
        // The pure gate is PlanGateTests' (Apple CI); its built-in table is the decided one.
        val gate = source("$ios/Services/BirdoShieldGate.swift")
        assertTrue("case stealthMode, multiHop, portForwarding" in gate)
        assertTrue("case .stealthMode: return \"OPERATIVE\"" in gate && "case .multiHop, .portForwarding: return \"SOVEREIGN\"" in gate)
        assertTrue(File(repoRoot, "iosApp/BirdoVPNTests/PlanGateTests.swift").isFile)
        // Every gate site asks it; nothing keeps a plan-name gate to fall back on.
        val multiHop = "settingsVM.planHas(.multiHop, plan: vpnVM.subscription?.plan) == true"
        val home = source("$ios/Views/HomeView.swift")
        assertTrue(multiHop in home && "let unlocked = multiHopUnlocked" in home && "} else if multiHopUnlocked {" in home)
        val hop = source("$ios/Views/MultiHopView.swift")
        assertTrue(multiHop in hop && "multiHopUnlocked && selectedEntry != nil && selectedExit != nil" in hop)
        assertTrue("if multiHopUnlocked {\n                        pickerContent" in hop)
        assertTrue("the badge names the lowest plan with it", "PlanBadge(settingsVM.lowestPlan(with: .multiHop), locked: true)" in hop)
        val settings = source("$ios/Views/SettingsView.swift")
        assertTrue(
            settings.substringAfter("private var portForwardingRow: some View {").trimStart()
                .startsWith("if settingsVM.planHas(.portForwarding, plan: vpnVM.subscription?.plan) == true {"),
        )
        val portForward = source("$ios/Views/PortForwardView.swift")
        assertTrue("return settingsVM.planHas(.portForwarding, plan: sub.plan) == false" in portForward)
        listOf(hop, portForward).forEach { assertTrue("@EnvironmentObject var settingsVM: SettingsViewModel" in it) }
        listOf("Services/APIClient.swift", "ViewModels/VpnViewModel.swift", "Views/HomeView.swift", "Views/MultiHopView.swift",
            "Views/SettingsView.swift", "Views/PortForwardView.swift").forEach {
            assertFalse("$it gates on a plan name again", Regex("""isSovereign|isOperativeOrHigher""").containsMatchIn(source("$ios/$it")))
        }
        // The table is fetched at launch, as on Android, and re-read when Multi-Hop opens.
        assertTrue("await settingsVM.refreshClientConfig()" in source("$ios/ContentView.swift"))
        assertTrue("await settingsVM.refreshClientConfig()" in hop)
        // Custom DNS: every plan, unless the server's own table says otherwise.
        assertTrue("guard let config else { return true }" in gate && "?? true" in gate)

        // iOS has neither Stealth nor split tunnelling, so its paywall offers neither.
        val paywall = source("$ios/Views/SubscriptionView.swift")
        val cards = listOf("RECON", "OPERATIVE", "SOVEREIGN").associateWith { plan ->
            paywall.substringAfter("slug: \"$plan\",").substringBefore("PlanCardModel(")
        }
        cards.forEach { (plan, card) ->
            assertTrue("no paywall card for $plan", card.length < paywall.length)
            val features = serverTable.getValue(plan)
            assertEquals("$plan card and Multi-Hop", features.getValue("multiHop"), "\"Multi-hop routing\"" in card)
            assertEquals("$plan card and Port Forwarding", features.getValue("portForwarding"), "\"Port forwarding\"" in card)
            assertEquals("$plan card and Custom DNS", features.getValue("customDns"), "\"Custom DNS\"" in card)
            assertFalse("$plan card sells Stealth, which iOS does not have", "Stealth" in card)
            assertFalse("$plan card sells split tunnelling, which iOS does not have", "Split" in card)
        }
    }
}
