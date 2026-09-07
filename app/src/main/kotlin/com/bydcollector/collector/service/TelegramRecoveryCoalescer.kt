package com.bydcollector.collector.service

/**
 * Keeps Telegram recovery hints single-flight without owning an executor.
 *
 * A hint already represented by the active or pending pass is ignored. A
 * genuinely different hint received while a pass is running is retained as
 * one follow-up pass.
 */
internal data class TelegramRecoveryRequest(
    val token: Long,
    val reasons: Set<String>
) {
    val trigger: String = reasons.joinToString("+")
}

internal class TelegramRecoveryCoalescer {
    private data class Hint(
        val reason: String,
        val key: String,
        val replacePendingKey: String?
    )

    private var nextToken = 0L
    private var activeToken = 0L
    private var activeHints: List<Hint> = emptyList()
    private val pendingHints = linkedMapOf<String, Hint>()

    @Synchronized
    fun request(
        reason: String,
        key: String = reason,
        replacePendingKey: String? = null
    ): TelegramRecoveryRequest? {
        require(reason.isNotBlank()) { "Recovery reason must not be blank" }
        require(key.isNotBlank()) { "Recovery key must not be blank" }
        if (activeHints.any { it.key == key } || pendingHints.containsKey(key)) return null
        val hint = Hint(reason, key, replacePendingKey)
        if (activeHints.isEmpty()) {
            activeHints = listOf(hint)
            return newRequest(activeHints)
        }
        replacePendingKey?.let { prefix ->
            pendingHints.entries.removeIf { it.value.replacePendingKey == prefix || it.value.reason == prefix }
        }
        pendingHints[key] = hint
        return null
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = activeHints.isNotEmpty() && activeToken == token

    @Synchronized
    fun complete(token: Long): TelegramRecoveryRequest? {
        if (!isCurrent(token)) return null
        if (pendingHints.isEmpty()) {
            activeHints = emptyList()
            return null
        }
        activeHints = pendingHints.values.toList()
        pendingHints.clear()
        return newRequest(activeHints)
    }

    @Synchronized
    fun invalidate(token: Long? = null) {
        if (token != null && !isCurrent(token)) return
        activeHints = emptyList()
        pendingHints.clear()
    }

    private fun newRequest(hints: List<Hint>): TelegramRecoveryRequest {
        activeToken = ++nextToken
        return TelegramRecoveryRequest(activeToken, hints.map { it.reason }.toSet())
    }
}
