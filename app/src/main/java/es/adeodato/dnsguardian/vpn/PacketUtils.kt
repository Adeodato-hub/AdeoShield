package es.adeodato.dnsguardian.vpn

/**
 * Construye paquetes IPv4/UDP para escribir respuestas DNS de vuelta al TUN.
 * No hace ninguna llamada de red; solo aritmética de cabeceras.
 */
object PacketUtils {

    /**
     * Envuelve [payload] (respuesta DNS en wire-format) en un paquete UDP/IPv4.
     * [srcIp]/[srcPort] → quien "responde" (nuestro servidor DNS virtual).
     * [dstIp]/[dstPort] → el cliente original (la app que hizo la pregunta).
     */
    fun buildUdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val ipLen  = 20 + udpLen
        val buf    = ByteArray(ipLen)

        // ── Cabecera IPv4 (20 bytes) ──────────────────────────────────────────
        buf[0]  = 0x45.toByte()                     // Versión=4, IHL=5
        buf[1]  = 0x00                              // DSCP/ECN
        buf[2]  = (ipLen shr 8).toByte()
        buf[3]  = (ipLen and 0xFF).toByte()
        buf[4]  = 0x00; buf[5] = 0x00              // Identificación
        buf[6]  = 0x40; buf[7] = 0x00              // Flag DF, offset=0
        buf[8]  = 64                                // TTL
        buf[9]  = 17                                // Protocolo UDP
        // bytes 10-11: checksum (se calcula abajo)
        System.arraycopy(srcIp, 0, buf, 12, 4)
        System.arraycopy(dstIp, 0, buf, 16, 4)

        val cs = ipChecksum(buf, 0, 20)
        buf[10] = (cs shr 8).toByte()
        buf[11] = (cs and 0xFF).toByte()

        // ── Cabecera UDP (8 bytes) ────────────────────────────────────────────
        buf[20] = (srcPort shr 8).toByte(); buf[21] = (srcPort and 0xFF).toByte()
        buf[22] = (dstPort shr 8).toByte(); buf[23] = (dstPort and 0xFF).toByte()
        buf[24] = (udpLen shr 8).toByte();  buf[25] = (udpLen and 0xFF).toByte()
        buf[26] = 0x00; buf[27] = 0x00             // Checksum UDP = 0 (opcional en IPv4)

        // ── Payload DNS ───────────────────────────────────────────────────────
        System.arraycopy(payload, 0, buf, 28, payload.size)

        return buf
    }

    /** Checksum de Internet (RFC 1071) sobre [length] bytes de [data] a partir de [offset]. */
    private fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
