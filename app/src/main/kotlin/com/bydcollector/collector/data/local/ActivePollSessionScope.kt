package com.bydcollector.collector.data.local

//builds live-status poll queries only when the service has a current collection session
internal class ActivePollSessionScope private constructor(
    val sessionId: Long?
) {
    fun lastSuccessAtSql(): String? {
        return sessionPollSql { "SELECT ts FROM polls WHERE session_id = $it AND ok = 1 ORDER BY id DESC LIMIT 1" }
    }

    fun lastErrorSql(): String? {
        return sessionPollSql { "SELECT errors FROM polls WHERE session_id = $it AND ok = 0 AND errors IS NOT NULL ORDER BY id DESC LIMIT 1" }
    }

    fun lastErrorAtSql(): String? {
        return sessionPollSql { "SELECT ts FROM polls WHERE session_id = $it AND ok = 0 ORDER BY id DESC LIMIT 1" }
    }

    fun lastPollStatusSql(): String? {
        return sessionPollSql {
            "SELECT ts, ok, error_category, error_message FROM polls WHERE session_id = $it ORDER BY id DESC LIMIT 1"
        }
    }

    fun elapsedMsSql(): String? {
        return sessionPollSql { "SELECT elapsed_ms FROM polls WHERE session_id = $it ORDER BY id DESC LIMIT 1" }
    }

    fun requestCountSql(): String? {
        return sessionPollSql { "SELECT request_count FROM polls WHERE session_id = $it ORDER BY id DESC LIMIT 1" }
    }

    private fun sessionPollSql(template: (Long) -> String): String? {
        return sessionId?.let(template)
    }

    companion object {
        fun from(running: Boolean, activeSessionId: Long?): ActivePollSessionScope {
            return ActivePollSessionScope(activeSessionId.takeIf { running })
        }
    }
}
