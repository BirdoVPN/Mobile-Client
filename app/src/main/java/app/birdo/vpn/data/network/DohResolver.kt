package app.birdo.vpn.data.network

import app.birdo.vpn.BuildConfig
import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import app.birdo.vpn.service.BirdoVpnService
import app.birdo.vpn.utils.FaultReporter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
    //
    // D-6: DoH serves only the API's BYPASS client — the paths where the app's
    // traffic must not ride a tunnel (before one exists, behind the kill-switch
    // block, on the reconnect path, during Stealth). Its sockets are protected
    // the same way, so a lookup made while a dead tunnel is still up goes
    // around it with the request it resolves for.
    private val bootstrapClient: OkHttpClient = run {
        val builder = OkHttpClient.Builder()
            .socketFactory(ProtectingSocketFactory())
            // Short and explicit, not OkHttp's 10s defaults. Every second spent
            // here is spent inside a Dns.lookup that the API client's phase
            // timeouts do not bound, before the resolver can degrade to the
            // system one. A DoH provider that has not answered in ~3s on a
            // network that is eating our packets is not going to.
            .connectTimeout(BOOTSTRAP_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(BOOTSTRAP_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(BOOTSTRAP_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!BuildConfig.DEBUG) {
            // SPKI pins for Cloudflare DoH. SOURCE OF TRUTH:
            // third_party/cert-pins.json (vendored from birdo-shared);
            // scripts/check-cert-pins.sh enforces the match in CI.
            //
            // Re-measured 2026-08-22 against 1.1.1.1 with SNI cloudflare-dns.com:
            //     leaf -> SSL.com SSL Intermediate CA ECC R2
            //          -> SSL.com Root Certification Authority ECC
            //
            // BOTH previous pins here were dead, and had been for months:
            //   * "WoiWRyIO...PB18=" is genuinely DigiCert High Assurance EV
            //     Root CA (NOT "DigiCert ECC Secure Global Root G3" as labelled)
            //     and Cloudflare has since migrated off DigiCert entirely.
            //   * "hxqRlPTu...63Vg=" was not a certificate hash at all. It is a
            //     corrupted transcription of GTS Root R1 (...63Gc=), differing
            //     only in the last two base64 characters, and appears in no root
            //     store. The real Baltimore CyberTrust Root is
            //     Y9mvm0exBk1JoQ57f9Vm28jKo5lFm/woKcVxrYxu80o=.
            //
            // With no pin able to match, every release-build handshake failed
            // the pinner, resolve() caught it, and DNS fell through to the
            // SYSTEM resolver — silently defeating the DNS-leak protection this
            // class exists to provide. Nothing broke visibly, so nobody noticed.
            //
            // P6-CLI-A-06: the dns.google and dns.quad9.net pins were removed
            // with those providers. Do not re-add a provider without re-adding
            // its pins — an unpinned DoH provider is a resolver a hostile
            // network can impersonate.
            val pinner = CertificatePinner.Builder()
                // --- live in the presented chain ---
                // SSL.com SSL Intermediate CA ECC R2
                .add("cloudflare-dns.com", "sha256/zGgA4OU4DjJdvpRYUqbi5Vh2g9W5Oc/PgKihy9mkLsE=")
                // --- dormant overlap ---
                // SSL.com Root Certification Authority ECC (trust anchor, not sent)
                .add("cloudflare-dns.com", "sha256/oyD01TTXvpfBro3QSZc1vIlcMjrdLTiL/M9mLCPX+Zo=")
                // DigiCert High Assurance EV Root CA — Cloudflare's legacy
                // anchor, kept as migration overlap (correctly labelled now).
                .add("cloudflare-dns.com", "sha256/WoiWRyIOVNa9ihaBciRSC7XHjliYS9VwUGOIud4PB18=")
                .build()
            builder.certificatePinner(pinner)
        }
        builder.build()
    }

    /**
     * The pool of the connections DoH keeps open to its provider, outside any
     * tunnel. The API's call factory drops its idle ones when the app's
     * traffic moves into the tunnel (RoutingCallFactory, F3).
     */
    val connectionPool: ConnectionPool get() = bootstrapClient.connectionPool

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
     * The last answer DoH gave for each hostname, in this process. Read only
     * while Stealth is active and DoH has just failed ([resolveWith]): it came
     * over DoH, so using it discloses nothing, where the network's resolver
     * would. A stale address fails the API's own certificate pinning; it
     * cannot reach anything else.
     */
    private val lastDohAnswers = ConcurrentHashMap<String, List<InetAddress>>()

    /**
     * Resolves using Cloudflare DoH, then system DNS as a fail-open fallback —
     * except while Stealth is active (MR-953 F5, see [resolveWith]).
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
    fun resolve(hostname: String): List<InetAddress> = resolveWith(
        hostname = hostname,
        doh = cloudflare::lookup,
        system = okhttp3.Dns.SYSTEM::lookup,
        stealthActive = { BirdoVpnService.stealthActive },
        lastDohAnswers = lastDohAnswers,
    )

    /**
     * [resolve] with its four collaborators passed in, so the rules are unit
     * tests rather than a device session (DohResolverStealthTest).
     *
     * MR-953 F5 — STEALTH NEVER FALLS BACK TO THE NETWORK'S RESOLVER. While a
     * Stealth session runs, the app's own UID is outside its tunnel (Xray is a
     * child process whose socket cannot be protect()ed, TunnelAppRules), so a
     * system-resolver lookup goes to the network operator in cleartext — on
     * exactly the networks Stealth exists to hide from, and it names
     * api.birdo.app, i.e. "this device runs Birdo". With Stealth active and
     * DoH failing, the lookup FAILS instead, unless DoH answered for this host
     * earlier in the process (that answer came over DoH). The caller sees an
     * [UnknownHostException], as for any unresolvable name: an API call fails
     * as a transport error, and a heartbeat transport error never tears a
     * tunnel down.
     *
     * [stealthActive] is read only after DoH has failed.
     */
    internal fun resolveWith(
        hostname: String,
        doh: (String) -> List<InetAddress>,
        system: (String) -> List<InetAddress>,
        stealthActive: () -> Boolean,
        lastDohAnswers: MutableMap<String, List<InetAddress>>,
    ): List<InetAddress> {
        if (hostname.isBlank()) {
            throw UnknownHostException("hostname is blank")
        }
        val key = hostname.lowercase(Locale.ROOT)
        val dohFailure = try {
            return doh(hostname).also { if (it.isNotEmpty()) lastDohAnswers[key] = it }
        } catch (e: Exception) {
            e
        }
        // A PINNER failure means the pin set no longer matches the live chain:
        // leak protection is off for every user on this build and only a
        // release can restore it. Reported loudly on both paths below.
        val pinRejected = dohFailure is javax.net.ssl.SSLPeerUnverifiedException
        if (stealthActive()) {
            if (pinRejected) {
                FaultReporter.report(
                    FaultReporter.PATH_DNS,
                    "doh_pin_rejected",
                    "DoH certificate pinning rejected cloudflare-dns.com — Stealth lookups " +
                        "fail closed instead of using the system resolver",
                    dohFailure,
                )
            }
            lastDohAnswers[key]?.let { earlier ->
                FaultReporter.trail(
                    FaultReporter.PATH_DNS,
                    "DoH lookup failed during Stealth (${dohFailure.javaClass.simpleName}) — " +
                        "using the last DoH answer, never the system resolver",
                )
                return earlier
            }
            FaultReporter.trail(
                FaultReporter.PATH_DNS,
                "DoH lookup failed during Stealth (${dohFailure.javaClass.simpleName}) — " +
                    "lookup refused rather than sent to the network's resolver",
            )
            throw UnknownHostException(
                "DoH lookup failed while Stealth is active; the network's resolver is not used",
            ).apply { initCause(dohFailure) }
        }
        // Fail open: system resolver. Cert pinning on the actual API
        // connection still protects against DNS-spoofing MITM.
        //
        // But failing open SILENTLY is the defect. When this path is taken the
        // lookup leaves the device in cleartext to whatever resolver the
        // network hands us — precisely the leak this class exists to prevent —
        // and nothing visible changes, so nobody notices. That shape has
        // already bitten once: a dead pin set meant every release-build
        // handshake failed the pinner, resolve() caught it, and DNS fell
        // through for everyone, quietly.
        //
        // The two causes are NOT equally interesting and must not be reported
        // at the same weight:
        //
        //  * A PINNER failure (above). That is worth waking up for.
        //  * Anything else is usually DoH blocked or unreachable on this
        //    network — expected, exactly what the system fallback is for, and
        //    it would fire on every lookup behind a captive portal. Reporting
        //    that as loudly would train everyone to ignore the one that counts.
        if (pinRejected) {
            FaultReporter.report(
                FaultReporter.PATH_DNS,
                "doh_pin_rejected",
                "DoH certificate pinning rejected cloudflare-dns.com — DNS-leak " +
                    "protection has fallen back to the system resolver for every lookup",
                dohFailure,
            )
        } else {
            FaultReporter.trail(
                FaultReporter.PATH_DNS,
                "DoH lookup failed (${dohFailure.javaClass.simpleName}) — using the system resolver",
            )
        }
        return system(hostname)
    }

    /**
     * Returns a Dns implementation for use with OkHttpClient.Builder().dns().
     */
    val dns: okhttp3.Dns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> = resolve(hostname)
    }
}
