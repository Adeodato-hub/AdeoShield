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
 * Cliente DNS-over-HTTPS (RFC 8484) con fallback entre providers.
 * Usa IPs hardcodeadas para resolver los endpoints y evitar recursión
 * contra nuestra propia VPN. Los sockets salen por la red física gracias
 * a ProtectedSocketFactory.
 */
class DoHClient(private val vpnService: VpnService) {

    companion object {
        const val TAG = "DoHClient"
        private const val TIMEOUT = 10L
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
        .socketFactory(ProtectedSocketFactory(vpnService))
        .dns(HardcodedDns())
        .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun query(dnsWirePayload: ByteArray): ByteArray? {
        for (provider in PROVIDERS) {
            val result = doQuery(provider, dnsWirePayload)
            if (result != null) return result
        }
        Log.e(TAG, "All DoH providers failed")
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
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes()
                if (bytes == null || bytes.isEmpty()) null else bytes
            }
        } catch (e: Exception) {
            Log.w(TAG, "${provider.host}: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private class HardcodedDns : Dns {
        private val table: Map<String, List<InetAddress>> = buildMap {
            for (p in PROVIDERS) put(p.host, p.ips.map { InetAddress.getByName(it) })
        }

        override fun lookup(hostname: String): List<InetAddress> =
            table[hostname] ?: Dns.SYSTEM.lookup(hostname).also {
                Log.w(TAG, "HardcodedDns: unknown host '$hostname', falling back to system DNS")
            }
    }
}
