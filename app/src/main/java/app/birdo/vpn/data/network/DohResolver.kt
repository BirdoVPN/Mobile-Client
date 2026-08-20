package app.birdo.vpn.data.network

import app.birdo.vpn.BuildConfig
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * DNS-over-HTTPS resolver for the Birdo API host, falling back to the system
 * resolver. Keeps the lookup off the local network in cleartext, so the network
 * operator does not get "this device is about to reach api.birdo.app" for free.
 *
 * ## P6-CLI-A-06 — Google and Quad9 removed
 *
 * This was a three-provider chain: Cloudflare, then Google, then Quad9, then the
 * system resolver. Google and Quad9 have no other visibility into this app, so
 * every lookup handed each of them a brand-new datapoint — this IP address is
 * about to use a VPN, right now — that they could not otherwise have had. Two
 * third parties learning a user's VPN habits is not a price a VPN client gets to
 * pay on the user's behalf, and the redundancy bought almost nothing: the system
 * resolver below already covers "DoH is blocked on this network".
 *
 * Cloudflare stays, deliberately, and this is the part worth reading before
 * "finishing the job" by deleting it too:
 *
 *  * **api.birdo.app is served THROUGH Cloudflare.** It resolves to Cloudflare
 *    anycast (172.67.x / 104.21.x and 2606:4700::/32), so Cloudflare terminates
 *    TLS for every single API request this app makes. They already see the
 *    client IP, the timing and the hostname. The DoH lookup discloses nothing to
 *    them that the request one millisecond later does not. Removing it buys the
 *    user zero privacy.
 *  * **Deleting it is a real downgrade for the users who need this most.** With
 *    no DoH the lookup goes out in cleartext to whatever resolver the network
 *    hands us — i.e. straight to the network operator. That is precisely the
 *    adversary Adaptive Transport exists to defeat: the networks that DPI-block
 *    WireGuard are the networks watching DNS.
 *  * **Pinned IPs are NOT a safe substitute here.** Cloudflare anycast addresses
 *    are not owned by a zone and get reassigned. A stale pin would connect to an
 *    unrelated Cloudflare tenant, which fails hostname/pin verification — and
 *    OkHttp does not fail over to another address on a certificate error, only
 *    on a routing one. A rotated address would therefore brick the app for every
 *    installed build until an update shipped, which is the exact opposite of the
 *    fail-open requirement.
 *  * A first-party resolver would be the real fix, and there is nothing to point
 *    at: `dns.birdo.app` does not exist (NXDOMAIN as of 2026-08-20), and a DoH
 *    endpoint on api.birdo.app cannot bootstrap the lookup of api.birdo.app.
 *
 * The system resolver remains the final, unconditional fallback so DNS trouble
 * degrades the app rather than bricking it.
 */
object DohResolver {

    /** Per-phase budget for the DoH attempt (see [bootstrapClient]). */
    private const val BOOTSTRAP_TIMEOUT_SEC = 3L

    /**
     * Whole-call budget for the DoH attempt. Caps the worst case before the
     * system resolver takes over, which fits inside the API client's 45s
     * callTimeout with room for the request itself. With Google and Quad9 gone
     * this is now the whole DoH budget rather than a third of it, so the walk to
     * the system resolver is ~5s instead of ~15s.
     */
    private const val BOOTSTRAP_CALL_TIMEOUT_SEC = 5L

    // Bootstrap client is used for the first DoH connection (before DoH is ready).
    // Cert-pin the DoH provider to prevent MITM during bootstrap.
    private val bootstrapClient: OkHttpClient = run {
        val builder = OkHttpClient.Builder()
            // Short and explicit, not OkHttp's 10s defaults. Every second spent
            // here is spent inside a Dns.lookup that the API client's phase
            // timeouts do not bound, before the resolver can degrade to the
            // system one. A DoH provider that has not answered in ~3s on a
            // network that is eating our packets is not going to.
            .connectTimeout(BOOTSTRAP_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(BOOTSTRAP_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(BOOTSTRAP_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!BuildConfig.DEBUG) {
            // SPKI pins for Cloudflare (1.1.1.1). Verified 2026-04-15.
            // Rotate when intermediates are renewed.
            //
            // P6-CLI-A-06: the dns.google and dns.quad9.net pins were removed
            // with those providers. Do not re-add a provider without re-adding
            // its pins — an unpinned DoH provider is a resolver a hostile
            // network can impersonate.
            val pinner = CertificatePinner.Builder()
                // Cloudflare DoH — cloudflare-dns.com
                .add("cloudflare-dns.com", "sha256/WoiWRyIOVNa9ihaBciRSC7XHjliYS9VwUGOIud4PB18=") // DigiCert ECC Secure Global Root G3
                .add("cloudflare-dns.com", "sha256/hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Vg=") // Baltimore CyberTrust root (cross-sign backup)
                .build()
            builder.certificatePinner(pinner)
        }
        builder.build()
    }

    private val cloudflare: DnsOverHttps = DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("1.0.0.1"),
            InetAddress.getByName("2606:4700:4700::1111"),
        )
        .build()

    /**
     * Resolves using Cloudflare DoH, then system DNS as a fail-open fallback.
     *
     * Catches ALL exceptions (not just UnknownHostException) so transient DoH
     * failures — TLS handshake errors, timeouts, ISP-level blocking of DoH
     * providers on mobile networks — fall through to the system resolver instead
     * of bubbling up a bare-hostname UnknownHostException to the UI.
     *
     * System DNS is the last resort: if the user's network blocks DoH entirely
     * (some carrier/captive-portal networks do), the app should still work
     * rather than show an unhelpful "Login failed: api.birdo.app" message.
     * Blank hostnames are caller bugs and must not fall through to system DNS.
     */
    fun resolve(hostname: String): List<InetAddress> {
        if (hostname.isBlank()) {
            throw UnknownHostException("hostname is blank")
        }
        return try {
            cloudflare.lookup(hostname)
        } catch (_: Exception) {
            // Fail open: system resolver. Cert pinning on the actual API
            // connection still protects against DNS-spoofing MITM.
            okhttp3.Dns.SYSTEM.lookup(hostname)
        }
    }

    /**
     * Returns a Dns implementation for use with OkHttpClient.Builder().dns().
     */
    val dns: okhttp3.Dns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> = resolve(hostname)
    }
}
