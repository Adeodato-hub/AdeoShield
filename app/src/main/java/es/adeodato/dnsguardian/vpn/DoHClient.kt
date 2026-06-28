package es.adeodato.dnsguardian.vpn

import android.net.VpnService
import android.util.Log
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Cliente DNS-over-HTTPS (RFC 8484).
 *
 * Por qué NO necesita VpnService.protect():
 * ──────────────────────────────────────────
 * Usamos rutas VPN selectivas: solo enrutamos hacia el TUN las IPs de los
 * servidores DNS sin filtro (8.8.8.8, 1.1.1.1, …).
 * Las IPs de AdGuard Family (94.140.14.15 / 94.140.15.16) NO están en esas
 * rutas, por lo que las conexiones TCP de OkHttp hacia ellas salen directamente
 * por la red física, sin pasar por el TUN. No hay bucle circular.
 *
 * Por qué SÍ usa HardcodedDns:
 * ──────────────────────────────
 * La resolución del hostname "family.adguard-dns.com" usaría el resolver del
 * sistema, que en este contexto apunta a 10.0.0.1 (nuestra VPN). Eso sí
 * crearía un bucle. HardcodedDns evita cualquier consulta DNS devolviendo
 * las IPs conocidas directamente, sin tocar el resolver del sistema.
 *
 * Orden de providers:
 *   1. AdGuard Family DNS  (94.140.14.15 / 94.140.15.16)
 *   2. CleanBrowsing Family (185.228.168.168 / 185.228.169.168)
 *   3. Cloudflare for Families (1.1.1.3 / 1.0.0.3)
 */
class DoHClient(@Suppress("UNUSED_PARAMETER") vpnService: VpnService) {

    companion object {
        private const val TAG      = "DoHClient"
        private const val TIMEOUT  = 5L
        private val DNS_MEDIA_TYPE = "application/dns-message".toMediaType()

        private data class Provider(val url: String, val host: String, val ips: List<String>)

        private val PROVIDERS = listOf(
            Provider(
                url  = "https://family.adguard-dns.com/dns-query",
                host = "family.adguard-dns.com",
                ips  = listOf("94.140.14.15", "94.140.15.16")
            ),
            Provider(
                url  = "https://doh.cleanbrowsing.org/doh/family-filter/",
                host = "doh.cleanbrowsing.org",
                ips  = listOf("185.228.168.168", "185.228.169.168")
            ),
            Provider(
                url  = "https://family.cloudflare-dns.com/dns-query",
                host = "family.cloudflare-dns.com",
                ips  = listOf("1.1.1.3", "1.0.0.3")
            )
        )
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(HardcodedDns())
        .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT, TimeUnit.SECONDS)
        .build()

    /**
     * Envía [dnsWirePayload] (consulta DNS en wire-format) al primer provider
     * disponible y devuelve la respuesta en wire-format, o null si todos fallan.
     */
    fun query(dnsWirePayload: ByteArray): ByteArray? {
        for (provider in PROVIDERS) {
            val result = doQuery(provider, dnsWirePayload)
            if (result != null) return result
        }
        Log.e(TAG, "Todos los providers DoH fallaron.")
        return null
    }

    private fun doQuery(provider: Provider, payload: ByteArray): ByteArray? {
        val request = Request.Builder()
            .url(provider.url)
            .post(payload.toRequestBody(DNS_MEDIA_TYPE))
            .header("Accept", "application/dns-message")
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.bytes()
                } else {
                    Log.w(TAG, "${provider.host} respondió HTTP ${resp.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "${provider.host} no disponible: ${e.message}")
            null
        }
    }

    /**
     * Resolver DNS que devuelve IPs hardcodeadas para los endpoints DoH,
     * evitando cualquier consulta al resolver del sistema (y por tanto al TUN).
     * Para cualquier otro hostname delega en el DNS del sistema.
     */
    private class HardcodedDns : Dns {
        private val table: Map<String, List<InetAddress>> = buildMap {
            for (p in PROVIDERS) {
                put(p.host, p.ips.map { ip -> InetAddress.getByName(ip) })
            }
        }
        override fun lookup(hostname: String): List<InetAddress> =
            table[hostname] ?: Dns.SYSTEM.lookup(hostname)
    }
}
