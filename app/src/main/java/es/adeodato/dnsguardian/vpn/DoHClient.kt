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
 * Orden de providers:
 *   1. AdGuard Family DNS  (94.140.14.15 / 94.140.15.16)
 *   2. CleanBrowsing Family (185.228.168.168 / 185.228.169.168)
 *   3. Cloudflare for Families (1.1.1.3 / 1.0.0.3)
 *
 * HardcodedDns impide que OkHttp use el resolver del sistema (que apuntaría
 * a nuestra propia VPN) para resolver los hostnames de los endpoints DoH.
 */
class DoHClient(@Suppress("UNUSED_PARAMETER") vpnService: VpnService) {

    companion object {
        const val TAG = "DoHClient"
        private const val TIMEOUT  = 8L          // segundos por intento
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
        .retryOnConnectionFailure(false)
        .build()

    /**
     * Envía [dnsWirePayload] al primer provider disponible.
     * Devuelve la respuesta en wire-format, o null si todos fallan.
     */
    fun query(dnsWirePayload: ByteArray): ByteArray? {
        Log.d(TAG, "query() payload=${dnsWirePayload.size}B — probando ${PROVIDERS.size} providers")
        for ((index, provider) in PROVIDERS.withIndex()) {
            Log.d(TAG, "  Provider ${index + 1}/${PROVIDERS.size}: ${provider.host}")
            val result = doQuery(provider, dnsWirePayload)
            if (result != null) {
                Log.d(TAG, "  ✓ Éxito con ${provider.host}: respuesta ${result.size}B")
                return result
            }
            Log.w(TAG, "  ✗ ${provider.host} falló, probando siguiente…")
        }
        Log.e(TAG, "TODOS los providers DoH fallaron.")
        return null
    }

    private fun doQuery(provider: Provider, payload: ByteArray): ByteArray? {
        val request = Request.Builder()
            .url(provider.url)
            .post(payload.toRequestBody(DNS_MEDIA_TYPE))
            .header("Accept", "application/dns-message")
            .build()

        return try {
            Log.d(TAG, "    Abriendo conexión a ${provider.url}…")
            val t0 = System.currentTimeMillis()

            client.newCall(request).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                Log.d(TAG, "    HTTP ${resp.code} en ${ms}ms, Content-Type=${resp.header("Content-Type")}")

                if (!resp.isSuccessful) {
                    Log.w(TAG, "    HTTP ${resp.code} — no exitoso")
                    return null
                }
                val bytes = resp.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    Log.w(TAG, "    Body nulo o vacío")
                    return null
                }
                Log.d(TAG, "    Body: ${bytes.size}B ✓")
                bytes
            }
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "    UnknownHostException: ${e.message} " +
                       "— ¿el HardcodedDns está devolviendo las IPs correctas?")
            null
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "    ConnectException: ${e.message} " +
                       "— ¿la IP de AdGuard es alcanzable desde la red del J6?")
            null
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "    SocketTimeoutException (${TIMEOUT}s agotado): ${e.message}")
            null
        } catch (e: javax.net.ssl.SSLException) {
            Log.e(TAG, "    SSLException: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "    Excepción inesperada (${e.javaClass.simpleName}): ${e.message}", e)
            null
        }
    }

    // Resuelve los hostnames de los endpoints directamente con IPs hardcodeadas;
    // así OkHttp no usa el DNS del sistema (que apuntaría a nuestra propia VPN).
    private class HardcodedDns : Dns {
        private val table: Map<String, List<InetAddress>> = buildMap {
            for (p in PROVIDERS) {
                val addrs = p.ips.map { ip ->
                    InetAddress.getByName(ip).also {
                        Log.d(TAG, "HardcodedDns: ${p.host} → $ip")
                    }
                }
                put(p.host, addrs)
            }
        }
        override fun lookup(hostname: String): List<InetAddress> {
            val known = table[hostname]
            return if (known != null) {
                Log.d(TAG, "HardcodedDns lookup('$hostname') → ${known.map { it.hostAddress }}")
                known
            } else {
                Log.w(TAG, "HardcodedDns lookup('$hostname') → delegando al DNS del sistema ⚠️")
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }
}
