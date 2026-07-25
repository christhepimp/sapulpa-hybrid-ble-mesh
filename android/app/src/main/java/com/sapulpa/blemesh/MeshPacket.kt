package com.sapulpa.blemesh

import java.util.UUID

/**
 * Compact mesh packet for BLE Manufacturer Specific Data.
 *
 * Wire layout (max ~24 bytes usable in legacy adv):
 *   [1]  version
 *   [1]  hop_count
 *   [16] packet UUID
 *   [n]  payload (UTF-8, truncated to fit)
 */
data class MeshPacket(
    val id: UUID = UUID.randomUUID(),
    val hopCount: Int = MAX_HOPS,
    val payload: String,
    val originNodeId: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val VERSION: Byte = 1
        const val MAX_HOPS = 8
        const val MAX_PAYLOAD_CHARS = 18
        const val COMPANY_ID = 0xFFFF

        fun fromBytes(data: ByteArray): MeshPacket? {
            if (data.size < 18) return null
            if (data[0] != VERSION) return null
            val hops = data[1].toInt() and 0xFF
            if (hops <= 0) return null
            val msb = data.copyOfRange(2, 10)
            val lsb = data.copyOfRange(10, 18)
            val uuid = UUID(bytesToLong(msb), bytesToLong(lsb))
            val payloadBytes = if (data.size > 18) data.copyOfRange(18, data.size) else byteArrayOf()
            val payload = try {
                String(payloadBytes, Charsets.UTF_8)
            } catch (_: Exception) {
                return null
            }
            return MeshPacket(id = uuid, hopCount = hops, payload = payload)
        }

        private fun bytesToLong(b: ByteArray): Long {
            var v = 0L
            for (i in b.indices) {
                v = (v shl 8) or (b[i].toLong() and 0xFF)
            }
            return v
        }

        private fun longToBytes(v: Long): ByteArray {
            val b = ByteArray(8)
            for (i in 0 until 8) {
                b[7 - i] = ((v shr (i * 8)) and 0xFF).toByte()
            }
            return b
        }
    }

    fun toBytes(): ByteArray {
        val truncated = payload.take(MAX_PAYLOAD_CHARS).toByteArray(Charsets.UTF_8)
        val out = ByteArray(18 + truncated.size)
        out[0] = VERSION
        out[1] = hopCount.coerceIn(0, 255).toByte()
        System.arraycopy(longToBytes(id.mostSignificantBits), 0, out, 2, 8)
        System.arraycopy(longToBytes(id.leastSignificantBits), 0, out, 10, 8)
        System.arraycopy(truncated, 0, out, 18, truncated.size)
        return out
    }

    fun decrementHop(): MeshPacket? {
        if (hopCount <= 1) return null
        return copy(hopCount = hopCount - 1)
    }
}
