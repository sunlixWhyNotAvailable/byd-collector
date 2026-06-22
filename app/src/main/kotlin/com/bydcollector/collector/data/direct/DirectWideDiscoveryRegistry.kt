package com.bydcollector.collector.data.direct

data class DirectWideDiscoveryCandidate(
    val dev: Int,
    val fid: Int,
    val tx: Int,
    val seedKey: String?,
    val source: String,
    val decoder: DirectValueDecoder?
) {
    val key: String = "discovery_${dev}_${fid}_${tx}"

    fun toEntry(): DirectFidEntry {
        return DirectFidEntry(
            key = key,
            dev = dev,
            fid = fid,
            tx = tx,
            decoder = decoder ?: if (tx == DirectFidRegistry.TX_GET_FLOAT) {
                DirectValueDecoder.FLOAT_RAW
            } else {
                DirectValueDecoder.INT_RAW
            },
            groupName = "direct_discovery"
        )
    }
}

object DirectWideDiscoveryRegistry {
    const val MAX_CANDIDATES = 6_500

    private val neighborOffsets = listOf(-16, -8, -4, -2, -1, 1, 2, 4, 8, 16)
    private val txValues = listOf(DirectFidRegistry.TX_GET_INT, DirectFidRegistry.TX_GET_FLOAT)

    val candidates: List<DirectWideDiscoveryCandidate> by lazy {
        buildCandidates()
    }

    private fun buildCandidates(): List<DirectWideDiscoveryCandidate> {
        val collected = linkedMapOf<String, DirectWideDiscoveryCandidate>()
        fun add(candidate: DirectWideDiscoveryCandidate) {
            if (collected.size >= MAX_CANDIDATES) return
            collected.putIfAbsent("${candidate.dev}:${candidate.fid}:${candidate.tx}", candidate)
        }

        DirectFidRegistry.entries.forEach { entry ->
            add(
                DirectWideDiscoveryCandidate(
                    dev = entry.dev,
                    fid = entry.fid,
                    tx = entry.tx,
                    seedKey = entry.key,
                    source = "stable_seed",
                    decoder = entry.decoder
                )
            )
        }

        val seedByFid = DirectFidRegistry.entries
            .groupBy { it.fid }
            .mapValues { (_, entries) -> entries.first() }

        (1000..1032).forEach { dev ->
            seedByFid.values.forEach { seed ->
                txValues.forEach { tx ->
                    add(
                        DirectWideDiscoveryCandidate(
                            dev = dev,
                            fid = seed.fid,
                            tx = tx,
                            seedKey = seed.key,
                            source = "known_fid_cross_dev",
                            decoder = seed.decoder
                        )
                    )
                }
            }
        }

        DirectFidRegistry.entries.forEach { seed ->
            neighborOffsets.forEach { offset ->
                val fid = safeOffset(seed.fid, offset) ?: return@forEach
                txValues.forEach { tx ->
                    add(
                        DirectWideDiscoveryCandidate(
                            dev = seed.dev,
                            fid = fid,
                            tx = tx,
                            seedKey = seed.key,
                            source = "seed_neighbor",
                            decoder = null
                        )
                    )
                }
            }
        }

        return collected.values.toList()
    }

    private fun safeOffset(fid: Int, offset: Int): Int? {
        val value = fid.toLong() + offset.toLong()
        return value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }
}
