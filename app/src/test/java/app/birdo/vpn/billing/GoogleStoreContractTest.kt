package app.birdo.vpn.billing

import app.birdo.vpn.data.api.BirdoApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.POST
import java.io.File

/**
 * Pins the cross-repo wire contract with birdo-web's Google Play store rail.
 *
 * Two failure modes this guards, both of which have already happened on this
 * project:
 *
 *  1. A DROPPED `@Serializable`. It compiles, CI stays green, and it throws at
 *    runtime — Retrofit's kotlinx converter resolves the serializer
 *    REFLECTIVELY at call time. Serializing each type here is the cheapest
 *    possible proof the annotation is attached to the class we think it is.
 *    (Same reason RequestSerializationTest exists.)
 *
 *  2. A SILENTLY DIVERGED contract. Desktop parsed `requiredVersion` while the
 *     backend sent `minVersion`; two agents each assumed. The route strings and
 *     field names asserted here are what this client actually puts on the wire,
 *     so a server-side rename fails a test rather than a customer's purchase.
 */
class GoogleStoreContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── @Serializable is attached to the classes we think ───────────────────

    @Test
    fun `the link request serializes with the field name the backend validates`() {
        val encoded = json.encodeToString(
            serializer<GooglePlayLinkRequest>(),
            GooglePlayLinkRequest(purchaseToken = "opaque-token"),
        )
        // GooglePlayLinkDto declares exactly `purchaseToken`, and the global
        // ValidationPipe runs forbidNonWhitelisted, so a rename here is a 400.
        assertEquals("""{"purchaseToken":"opaque-token"}""", encoded)
    }

    @Test
    fun `the purchase-intent response parses the field the server sends`() {
        val decoded = json.decodeFromString(
            serializer<GooglePurchaseIntentResponse>(),
            """{"obfuscatedAccountId":"2f1c0d3e-0000-4000-8000-000000000000"}""",
        )
        assertEquals("2f1c0d3e-0000-4000-8000-000000000000", decoded.obfuscatedAccountId)
    }

    @Test
    fun `the link response parses a full server answer`() {
        val decoded = json.decodeFromString(
            serializer<GooglePlayLinkResponse>(),
            """
            {"linked":true,"plan":"SOVEREIGN","state":"ACTIVE",
             "expiresAt":"2027-01-01T00:00:00.000Z",
             "duplicateBilling":{"otherSource":"POLAR","otherPlan":"OPERATIVE",
                                 "otherPeriodEnd":"2026-09-01T00:00:00.000Z",
                                 "message":"You are also paying on the web."}}
            """.trimIndent(),
        )
        assertEquals("SOVEREIGN", decoded.plan)
        assertEquals("ACTIVE", decoded.state)
        // These names come from the backend's DuplicateBilling interface, not
        // from a guess: `message` is the field the banner renders, and a
        // mis-named field would deserialise to null and show nothing at all.
        assertEquals("POLAR", decoded.duplicateBilling?.otherSource)
        assertEquals("OPERATIVE", decoded.duplicateBilling?.otherPlan)
        assertEquals(
            "You are also paying on the web.",
            decoded.duplicateBilling?.message,
        )
    }

    /**
     * The server may ADD fields freely — an over-strict client would break on
     * the next backend release. Unknown keys and an absent duplicateBilling
     * must both be fine.
     */
    @Test
    fun `the link response tolerates unknown keys and a minimal body`() {
        val decoded = json.decodeFromString(
            serializer<GooglePlayLinkResponse>(),
            """{"linked":true,"plan":"OPERATIVE","state":"ACTIVE","somethingNew":42}""",
        )
        assertEquals("OPERATIVE", decoded.plan)
        assertNull(decoded.duplicateBilling)
        assertNull(decoded.expiresAt)
    }

    /**
     * The refusal envelope. `details` is the ONLY key GlobalExceptionFilter
     * copies through from a thrown HttpException — a top-level `code` is
     * rebuilt away before the wire — so classification reads `details.code` and
     * this test pins that it is actually reachable from the real body shape.
     */
    @Test
    fun `the error envelope surfaces details dot code`() {
        val body = """
            {"statusCode":409,"message":"This Google Play subscription is already linked.",
             "error":"Conflict","timestamp":"2026-08-22T00:00:00.000Z",
             "path":"/payments/store/google/link",
             "details":{"code":"STORE_TRANSACTION_ALREADY_LINKED","reason":"ALREADY_OWNED"}}
        """.trimIndent()
        val envelope = json.decodeFromString(serializer<StoreErrorEnvelope>(), body)
        assertEquals("STORE_TRANSACTION_ALREADY_LINKED", envelope.details?.code)
        assertEquals("ALREADY_OWNED", envelope.details?.reason)
        assertEquals(
            StoreLinkRefusal.ALREADY_LINKED_TO_ANOTHER_ACCOUNT,
            StoreLinkRefusal.classify(envelope.statusCode, envelope.details?.code),
        )
    }

    /** A 4xx with no `details` at all must still classify from the status. */
    @Test
    fun `an envelope without details classifies from the status alone`() {
        val envelope = json.decodeFromString(
            serializer<StoreErrorEnvelope>(),
            """{"statusCode":401,"message":"Unauthorized","error":"Unauthorized"}""",
        )
        assertNull(envelope.details)
        assertEquals(
            StoreLinkRefusal.NEEDS_SIGN_IN,
            StoreLinkRefusal.classify(envelope.statusCode, envelope.details?.code),
        )
    }

    // ── Routes ──────────────────────────────────────────────────────────────

    @Test
    fun `routes match the controller prefix`() {
        assertEquals("payments/store/google/purchase-token", GoogleStoreRoutes.PURCHASE_TOKEN)
        assertEquals("payments/store/google/link", GoogleStoreRoutes.LINK)
    }

    /**
     * The routes live in ONE place so a server-side rename is a one-file change.
     * Retrofit needs compile-time constants in its annotations, which makes it
     * easy to paste a literal instead; this asserts the interface really is
     * wired to the constants.
     */
    @Test
    fun `the api interface uses the shared route constants`() {
        val paths = BirdoApi::class.java.methods
            .mapNotNull { it.getAnnotation(POST::class.java)?.value }
            .toSet()
        assertTrue(
            "purchase-token route is not wired to GoogleStoreRoutes",
            GoogleStoreRoutes.PURCHASE_TOKEN in paths,
        )
        assertTrue("link route is not wired to GoogleStoreRoutes", GoogleStoreRoutes.LINK in paths)
    }

    // ── Subscriptions only ──────────────────────────────────────────────────

    /**
     * The owner was explicit: vouchers and one-time purchases stay on the web.
     * A one-time product would need `ProductType.INAPP` and `consumeAsync`;
     * neither may appear anywhere in the billing package. This is a structural
     * pin, not a comment, because "we only meant to add subscriptions" is
     * exactly the kind of intent that erodes one helpful commit at a time.
     */
    @Test
    fun `the rail sells subscriptions only`() {
        val dir = File(repoRoot(), "app/src/main/java/app/birdo/vpn/billing")
        val sources = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("billing sources not found — this scan would be vacuous", sources.size >= 4)
        sources.forEach { file ->
            // Comments are stripped first: this pins what the rail DOES, not
            // what it says about itself. The doc comment on PlayBillingManager
            // names both forbidden symbols in order to explain why they are
            // absent, and a naive scan flags it — which is how this stripping
            // earned its place rather than being added on principle.
            val code = stripComments(file.readText())
            assertTrue(
                "${file.name} calls ProductType.INAPP — one-time purchases stay on the web",
                !code.contains("ProductType.INAPP"),
            )
            assertTrue(
                "${file.name} calls consumeAsync — Birdo sells no consumables",
                !code.contains("consumeAsync"),
            )
        }
    }

    /**
     * Crude but adequate: no source in the billing package puts a comment
     * opener inside a string literal, and an over-eager strip could only ever
     * make the scan above MORE strict, never less. The companion test below is
     * the vacuity guard — a stripper that returned an empty string would make
     * every scan pass for free.
     *
     * (Deliberately no comment-opener characters in this doc block: Kotlin
     * block comments NEST, so one here would swallow the code beneath it and
     * produce a page of unrelated syntax errors. Which it did, once.)
     */
    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .lines()
        .joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the comment stripper keeps code`() {
        val stripped = stripComments(
            """
            // ProductType.INAPP in a line comment
            /* ProductType.INAPP in a block comment */
            val kept = BillingClient.ProductType.SUBS
            """.trimIndent(),
        )
        assertTrue("the stripper ate the code", stripped.contains("ProductType.SUBS"))
        assertTrue("the stripper left a comment", !stripped.contains("ProductType.INAPP"))
    }

    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        return dir
    }
}
