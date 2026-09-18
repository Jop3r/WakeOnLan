package com.iliasaw.wol

import android.net.LinkProperties
import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Сборка и отправка Wake-on-LAN magic packet:
 * 6 байт 0xFF, затем MAC-адрес, повторённый 16 раз.
 */
object WolSender {

    /** Принимает разделители ":", "-", пробел; возвращает 6 байт или null. */
    fun parseMac(raw: String): ByteArray? {
        val parts = raw.trim().split(":", "-", " ").filter { it.isNotBlank() }
        if (parts.size != 6) return null
        val bytes = ByteArray(6)
        for ((i, part) in parts.withIndex()) {
            val value = part.toIntOrNull(16) ?: return null
            if (value !in 0..255) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    fun magicPacket(mac: ByteArray): ByteArray {
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0..5) packet[i] = 0xFF.toByte()
        for (round in 0 until 16) {
            System.arraycopy(mac, 0, packet, 6 + round * 6, 6)
        }
        return packet
    }

    /** Разбирает "192.168.0" в 1–3 октета или null. */
    fun parseSubnet(raw: String): ByteArray? {
        val parts = raw.trim().split(".").filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.size > 3) return null
        val bytes = ByteArray(parts.size)
        for ((i, part) in parts.withIndex()) {
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    /** Подсеть телефона с учётом маски: первые 3 октета его IPv4-сети, например "192.168.0". */
    fun phoneSubnet(linkProperties: LinkProperties?): String? {
        for (la in linkProperties?.linkAddresses ?: emptyList()) {
            val addr = la.address as? Inet4Address ?: continue
            val masked = maskedNetworkBytes(addr.address, la.prefixLength) ?: continue
            return masked.joinToString(".") { (it.toInt() and 0xFF).toString() }
        }
        return null
    }

    /** Совпадает ли подсеть телефона (с учётом маски) с префиксом вида "192.168.0" (1–3 октета). */
    fun matchesSubnet(prefix: String, linkProperties: LinkProperties?): Boolean {
        val wanted = parseSubnet(prefix) ?: return false
        for (la in linkProperties?.linkAddresses ?: emptyList()) {
            val addr = la.address as? Inet4Address ?: continue
            val masked = maskedNetworkBytes(addr.address, la.prefixLength) ?: continue
            var matches = true
            for (i in wanted.indices) {
                if (masked[i] != wanted[i]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun maskedNetworkBytes(ip: ByteArray, prefixLength: Int): ByteArray? {
        if (prefixLength <= 0 || prefixLength > 32) return null
        var ipInt = 0
        for (b in ip) ipInt = (ipInt shl 8) or (b.toInt() and 0xFF)
        val mask = if (prefixLength == 32) -1 else -1 shl (32 - prefixLength)
        val net = ipInt and mask
        return byteArrayOf(
            (net ushr 24).toByte(),
            (net ushr 16).toByte(),
            (net ushr 8).toByte(),
        )
    }

    /** Направленный broadcast-адрес сети (например, 192.168.0.255) из её IPv4-адреса и маски. */
    fun subnetBroadcastAddress(linkProperties: LinkProperties?): String? {
        for (la in linkProperties?.linkAddresses ?: emptyList()) {
            val addr = la.address as? Inet4Address ?: continue
            val ip = addr.address
            var ipInt = 0
            for (b in ip) ipInt = (ipInt shl 8) or (b.toInt() and 0xFF)
            val hostBits = 32 - la.prefixLength
            val broadcast = ipInt or ((1 shl hostBits) - 1)
            val bytes = byteArrayOf(
                (broadcast ushr 24).toByte(),
                (broadcast ushr 16).toByte(),
                (broadcast ushr 8).toByte(),
                broadcast.toByte(),
            )
            return InetAddress.getByAddress(bytes).hostAddress
        }
        return null
    }

    /**
     * Шлёт magic packet по [network] (если задана) на направленный broadcast-адрес
     * этой сети и на 255.255.255.255. Возвращает человекочитаемый результат.
     */
    fun send(mac: String, port: Int, network: Network?, linkProperties: LinkProperties?): String {
        val macBytes = parseMac(mac) ?: return "Неверный MAC-адрес"
        val packet = magicPacket(macBytes)

        val targets = buildList {
            subnetBroadcastAddress(linkProperties)?.let { add(it) }
            add("255.255.255.255")
        }.distinct()

        return DatagramSocket().use { socket ->
            socket.broadcast = true
            try {
                network?.bindSocket(socket)
            } catch (_: Exception) {
                // не удалось привязать к Wi-Fi — шлём через активную сеть по умолчанию
            }
            val sent = mutableListOf<String>()
            for (target in targets) {
                try {
                    val address = InetAddress.getByName(target)
                    socket.send(DatagramPacket(packet, packet.size, address, port))
                    sent.add("$target:$port")
                } catch (_: Exception) {
                    // ошибка отправки на конкретный адрес не критична, есть второй адрес
                }
            }
            if (sent.isEmpty()) "Ошибка отправки" else "Отправлено на ${sent.joinToString(", ")}"
        }
    }
}
