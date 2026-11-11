package nl.ako.hachat.core

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Staat onversleutelde HTTP alleen toe naar privé-netwerkadressen (LAN/VPN).
 * Zie res/xml/network_security_config.xml voor de motivatie.
 */
object CleartextPolicy {

    private val localSuffixes = listOf(".local", ".lan", ".home.arpa", ".internal", ".localdomain")

    /** Is dit letterlijke adres privé / lokaal? */
    fun isPrivateAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress) return true
        val b = addr.address
        return when (addr) {
            // 100.64.0.0/10 — CGNAT, o.a. Tailscale
            is Inet4Address -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
            // fc00::/7 — Unique Local Addresses (WireGuard/Tailscale IPv6)
            is Inet6Address -> (b[0].toInt() and 0xFE) == 0xFC
            else -> false
        }
    }

    /** Hostnamen die per definitie lokaal zijn (mDNS e.d.). */
    fun isLocalHostname(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h == "localhost" || h == "homeassistant" || localSuffixes.any { h.endsWith(it) }
    }

    /** True als cleartext naar deze host is toegestaan. [resolver] is injecteerbaar voor tests. */
    fun isCleartextAllowed(
        host: String,
        resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ): Boolean {
        val bare = host.removePrefix("[").removeSuffix("]")
        if (looksLikeIpLiteral(bare)) {
            return runCatching { isPrivateAddress(InetAddress.getByName(bare)) }.getOrDefault(false)
        }
        if (isLocalHostname(bare)) return true
        // Hostnaam: alleen toegestaan als ALLE adressen privé zijn (voorkomt DNS-trucs naar internet).
        val resolved = runCatching { resolver(bare) }.getOrNull() ?: return false
        return resolved.isNotEmpty() && resolved.all(::isPrivateAddress)
    }

    private fun looksLikeIpLiteral(h: String): Boolean =
        h.contains(':') || h.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))

    class CleartextBlockedException(host: String) : IOException(
        "Onversleutelde HTTP naar '$host' geweigerd: alleen toegestaan naar adressen in je eigen netwerk/VPN. Gebruik https:// voor publieke adressen."
    )

    /** OkHttp-interceptor die het beleid afdwingt. */
    class Guard(
        private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            if (!url.isHttps && !isCleartextAllowed(url.host, resolver)) {
                throw CleartextBlockedException(url.host)
            }
            return chain.proceed(chain.request())
        }
    }
}
