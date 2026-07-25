package com.sapulpa.blemesh

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * On-device multi-hop store-and-forward mesh engine.
 * Tracks seen packet UUIDs to prevent flood loops,
 * decrements hop count on every relay,
 * holds a short-lived buffer of packets waiting to be advertised.
 */
class MeshEngine(
    val nodeId: String = UUID.randomUUID().toString().take(8)
) {
    private val seenIds = ConcurrentHashMap.newKeySet<UUID>()
    private val buffer = CopyOnWriteArrayList<MeshPacket>()
    private val neighbors = ConcurrentHashMap<String, Long>()

    var onLog: ((String) -> Unit)? = null
    var onNeighborCountChanged: ((Int) -> Unit)? = null
    var onPacketRelayed: ((MeshPacket) -> Unit)? = null

    fun injectLocalMessage(text: String): MeshPacket {
        val pkt = MeshPacket(
            hopCount = MeshPacket.MAX_HOPS,
            payload = text.take(MeshPacket.MAX_PAYLOAD_CHARS),
            originNodeId = nodeId
        )
        seenIds.add(pkt.id)
        buffer.add(pkt)
        log("TX local: \"${pkt.payload}\" hops=${pkt.hopCount} id=${pkt.id.toString().take(8)}")
        return pkt
    }

    fun onReceive(raw: ByteArray, peerAddress: String?): MeshPacket? {
        val pkt = MeshPacket.fromBytes(raw) ?: return null

        if (peerAddress != null) {
            val wasNew = !neighbors.containsKey(peerAddress)
            neighbors[peerAddress] = System.currentTimeMillis()
            if (wasNew) onNeighborCountChanged?.invoke(neighbors.size)
        }

        if (!seenIds.add(pkt.id)) {
            return null
        }

        log("RX: \"${pkt.payload}\" hops=${pkt.hopCount} from=${peerAddress ?: \"?\"} id=${pkt.id.toString().take(8)}")

        val next = pkt.decrementHop() ?: run {
            log("Drop (TTL expired): ${pkt.id.toString().take(8)}")
            return null
        }

        buffer.add(next)
        onPacketRelayed?.invoke(next)
        log("RELAY: \"${next.payload}\" hops left=${next.hopCount}")
        return next
    }

    fun nextToAdvertise(): MeshPacket? {
        if (buffer.isEmpty()) return null
        return buffer.removeAt(0)
    }

    fun neighborCount(): Int {
        val cutoff = System.currentTimeMillis() - 30_000
        neighbors.entries.removeIf { it.value < cutoff }
        return neighbors.size
    }

    fun pruneSeen(maxSize: Int = 500) {
        if (seenIds.size > maxSize) {
            seenIds.clear()
        }
    }

    private fun log(msg: String) {
        onLog?.invoke(msg)
    }
}
