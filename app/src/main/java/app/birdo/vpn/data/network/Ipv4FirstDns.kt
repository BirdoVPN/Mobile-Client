package app.birdo.vpn.data.network

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * MR-949: the tunnel client's resolver. The system's answer (inside a live
 * tunnel that is the tunnel's DNS), with every IPv4 address ahead of every
 * IPv6 one; order within each family is kept, and nothing is dropped.
 *
 * Every node carries IPv4, but not every node carries IPv6. On a node without
 * it the tunnel has no IPv6 address while `::/0` is still routed into it — the
 * deliberate leak blackhole in BirdoVpnService.buildVpnInterface — so an AAAA
 * answer leads into a black hole. OkHttp 4.12, the version this app compiles
 * against, tries a host's addresses one after another, each with the tunnel
 * client's 10 s connect timeout: an IPv6-first answer on dual-stack Wi-Fi
 * stalled the first API call after Connected by 10 s per call. IPv4 first means
 * that call connects at once on every node. IPv6 stays in the list as the
 * fallback, so a dual-stack node is still reachable over it if IPv4 fails.
 *
 * (OkHttp 5, which the runtime classpath resolves today through Ktor in
 * :shared, races the families itself — fast fallback, IPv6 first, IPv4 250 ms
 * later — and is unaffected by this order. This does not depend on it.)
 */
internal class Ipv4FirstDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        delegate.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
}
