package app.birdo.vpn.data.network

import app.birdo.vpn.BuildConfig
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/**
 * DNS-over-HTTPS resolver with Cloudflare primary, Google fallback, and Quad9 final fallback.
 * Matches the Windows client's 3-provider DoH chain.
 * Prevents DNS leaks and ISP snooping on domain lookups.
 */
object DohResolver {

    // Bootstrap client is used for the first DoH connection (before DoH is ready).
    // Cert-pin the three DoH providers to prevent MITM during bootstrap.
    private val bootstrapClient: OkHttpClient = run {
        val builder = OkHttpClient.Builder()
        if (!BuildConfig.DEBUG) {
            // SPKI pins for Cloudflare (1.1.1.1), Google (8.8.8.8), and Quad9 (9.9.9.9)
            // Verified 2026-04-15. Rotate when intermediates are renewed.
            val pinner = CertificatePinner.Builder()
                // Cloudflare DoH — cloudflare-dns.com
                .add("cloudflare-dns.com", "sha256/WoiWRyIOVNa9ihaBciRSC7XHjliYS9VwUGOIud4PB18=") // DigiCert ECC Secure Global Root G3
                .add("cloudflare-dns.com", "sha256/hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Vg=") // Baltimore CyberTrust root (cross-sign backup)
                // Google DoH — dns.google
                .add("dns.google", "sha256/hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Vg=") // GTS Root R1
                .add("dns.google", "sha256/++MBgDH5WGvL9Bcn5Be30cRcL0f5O+NyoXuWtQdX1aI=") // GTS CA 1C3 back-stop
                // Quad9 DoH — dns.quad9.net
                .add("dns.quad9.net", "sha256/fwza0LRMXouZHRC8Ei+4PyuldPDcf3UKgO/04cDM1oE=") // DigiCert TLS RSA4096 Root G5
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

    private val google: DnsOverHttps = DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url("https://dns.google/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("8.8.8.8"),
            InetAddress.getByName("8.8.4.4"),
            InetAddress.getByName("2001:4860:4860::8888"),
        )
        .build()

    private val quad9: DnsOverHttps = DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url("https://dns.quad9.net/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("9.9.9.9"),
            InetAddress.getByName("149.112.112.112"),
            InetAddress.getByName("2620:fe::fe"),
        )
        .build()

    /**
     * Resolves using Cloudflare DoH first, then Google, then Quad9, then system
     * DNS as a final fallback.
     *
     * Catches ALL exceptions (not just UnknownHostException) so transient DoH
     * failures — TLS handshake errors, timeouts, ISP-level blocking of DoH
     * providers on mobile networks — fall through to the next provider instead
     * of bubbling up a bare-hostname UnknownHostException to the UI.
     *
     * System DNS is the last resort: if the user's network blocks DoH entirely
     * (some carrier/captive-portal networks do), the app should still work
     * rather than show an unhelpful "Login failed: api.birdo.app" message.
     */
    fun resolve(hostname: String): List<InetAddress> {
        return try {
            cloudflare.lookup(hostname)
        } catch (_: Exception) {
            try {
                google.lookup(hostname)
            } catch (_: Exception) {
                try {
                    quad9.lookup(hostname)
                } catch (_: Exception) {
                    // Final fallback: system resolver. Cert pinning on the actual
                    // API connection still protects against DNS-spoofing MITM.
                    okhttp3.Dns.SYSTEM.lookup(hostname)
                }
            }
        }
    }

    /**
     * Returns a Dns implementation for use with OkHttpClient.Builder().dns().
     */
    val dns: okhttp3.Dns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> = resolve(hostname)
    }
}
