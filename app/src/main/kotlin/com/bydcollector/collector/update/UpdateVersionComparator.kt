package com.bydcollector.collector.update

object UpdateVersionComparator {
    private val segmentRegex = Regex("\\d+")

    fun isNewer(remote: String, local: String): Boolean {
        val remoteSegments = segments(remote)
        val localSegments = segments(local)
        val maxSegments = maxOf(remoteSegments.size, localSegments.size)

        for (index in 0 until maxSegments) {
            val remoteValue = remoteSegments.getOrElse(index) { 0 }
            val localValue = localSegments.getOrElse(index) { 0 }
            if (remoteValue != localValue) {
                return remoteValue > localValue
            }
        }

        return false
    }

    private fun segments(value: String): List<Int> {
        //guard build suffixes from changing semantic version order
        val semanticPrefix = value.removePrefix("v").takeWhile { it.isDigit() || it == '.' }
        return segmentRegex.findAll(semanticPrefix)
            .map { it.value.toInt() }
            .toList()
    }
}
