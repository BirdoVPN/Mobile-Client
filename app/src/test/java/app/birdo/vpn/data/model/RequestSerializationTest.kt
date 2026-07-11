package app.birdo.vpn.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards every request body we put on the wire against a MISSING or MISBOUND
 * `@Serializable` annotation.
 *
 * This exists because of a real incident: an `@Serializable` in the shared model
 * had a new class inserted directly beneath it, so the annotation silently
 * re-bound to that new class and left [ConnectRequest] unannotated. Nothing
 * caught it — the code compiles (Retrofit's kotlinx converter resolves the
 * serializer REFLECTIVELY at call time, not at compile time), and the repository
 * tests mock the API interface, so no test ever serialized the class. The result
 * would have been a `SerializationException` on every single VPN connect,
 * swallowed by the repository's catch-all into a generic error.
 *
 * Serializing each request type here is the cheapest possible check that the
 * annotation is actually attached to the class we think it is.
 */
class RequestSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ConnectRequest serializes`() {
        val encoded = json.encodeToString(
            serializer<ConnectRequest>(),
            ConnectRequest(serverNodeId = "node-1", clientPublicKey = "pub"),
        )
        assertTrue("serverNodeId missing from payload", encoded.contains("serverNodeId"))
    }

    @Test
    fun `ConnectRequest carries the attestation token when present`() {
        val encoded = json.encodeToString(
            serializer<ConnectRequest>(),
            ConnectRequest(serverNodeId = "node-1", integrityToken = "token-abc"),
        )
        assertTrue("integrityToken missing from payload", encoded.contains("integrityToken"))
        assertTrue("integrityToken value missing", encoded.contains("token-abc"))
    }

    /**
     * A null token must be OMITTED, not sent as an explicit `null`: the backend's
     * zod schema is `.strict()` with `integrityToken` declared `.optional()`,
     * which accepts an absent key but rejects an explicit null. This is what lets
     * non-Play (sideload / F-Droid) builds keep connecting.
     */
    @Test
    fun `ConnectRequest omits a null attestation token`() {
        val encoded = json.encodeToString(
            serializer<ConnectRequest>(),
            ConnectRequest(serverNodeId = "node-1", integrityToken = null),
        )
        assertTrue("null integrityToken must not be encoded", !encoded.contains("integrityToken"))
    }

    @Test
    fun `MultiHopConnectRequest serializes and carries the attestation token`() {
        val encoded = json.encodeToString(
            serializer<MultiHopConnectRequest>(),
            MultiHopConnectRequest(
                entryNodeId = "entry-1",
                exitNodeId = "exit-1",
                integrityToken = "token-abc",
            ),
        )
        assertTrue("entryNodeId missing from payload", encoded.contains("entryNodeId"))
        assertTrue("exitNodeId missing from payload", encoded.contains("exitNodeId"))
        // The backend enforces attestation on the multi-hop connect exactly as it
        // does on the single-hop one; a multi-hop body without this field would be
        // a peer-issuing path that skips attestation.
        assertTrue("integrityToken missing from payload", encoded.contains("integrityToken"))
    }

    @Test
    fun `MultiHopConnectRequest omits a null attestation token`() {
        val encoded = json.encodeToString(
            serializer<MultiHopConnectRequest>(),
            MultiHopConnectRequest(entryNodeId = "entry-1", exitNodeId = "exit-1"),
        )
        assertTrue("null integrityToken must not be encoded", !encoded.contains("integrityToken"))
    }

    @Test
    fun `AttestationNonceResponse deserializes`() {
        val decoded = json.decodeFromString(
            serializer<AttestationNonceResponse>(),
            """{"nonce":"abc123"}""",
        )
        assertTrue("nonce not parsed", decoded.nonce == "abc123")
    }

    @Test
    fun `CreatePortForwardRequest serializes`() {
        val encoded = json.encodeToString(
            serializer<CreatePortForwardRequest>(),
            CreatePortForwardRequest(internalPort = 8080, protocol = "tcp"),
        )
        assertTrue("internalPort missing from payload", encoded.contains("internalPort"))
    }
}
