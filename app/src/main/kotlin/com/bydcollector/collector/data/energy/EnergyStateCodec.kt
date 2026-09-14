package com.bydcollector.collector.data.energy

import org.json.JSONObject

object EnergyStateCodec {
    fun encodeState(state: EnergyRuntimeState): String = JSONObject().apply {
        put("schema_version", state.schemaVersion)
        put("power_state", state.powerState.name)
        putNullable("power_session_id", state.powerSessionId)
        putNullable("started_at", state.startedAt)
        put("active", state.active)
        putNullable("last_source_identity", state.lastSourceIdentity)
        putNullable("last_source_boot_id", state.lastSourceBootId)
        putNullable("last_source_elapsed_ms", state.lastSourceElapsedMs)
        putNullable("anchor", state.anchor?.let(::encodeAnchor))
        put("totals", encodeTotals(state.totals))
        put("integration_quality", state.integrationQuality.name)
        put("reason", state.reason)
        putNullable("current_snapshot", state.currentSnapshot?.let(::encodeSnapshotObject))
    }.toString()

    fun decodeState(value: String): EnergyRuntimeState {
        val json = JSONObject(value)
        require(json.getInt("schema_version") == EnergyRuntimeState.CURRENT_SCHEMA_VERSION) {
            "Unsupported energy state schema"
        }
        return EnergyRuntimeState(
            schemaVersion = json.getInt("schema_version"),
            powerState = enumValueOf(json.getString("power_state")),
            powerSessionId = json.stringOrNull("power_session_id"),
            startedAt = json.stringOrNull("started_at"),
            active = json.getBoolean("active"),
            lastSourceIdentity = json.stringOrNull("last_source_identity"),
            lastSourceBootId = json.stringOrNull("last_source_boot_id"),
            lastSourceElapsedMs = json.longOrNull("last_source_elapsed_ms"),
            anchor = json.objectOrNull("anchor")?.let(::decodeAnchor),
            totals = decodeTotals(json.getJSONObject("totals")),
            integrationQuality = enumValueOf(json.getString("integration_quality")),
            reason = json.getString("reason"),
            currentSnapshot = json.objectOrNull("current_snapshot")?.let(::decodeSnapshotObject)
        )
    }

    fun encodeProjection(projection: EnergyPendingProjection): String = JSONObject().apply {
        put("schema_version", EnergyRuntimeState.CURRENT_SCHEMA_VERSION)
        put("snapshot", encodeSnapshotObject(projection.snapshot))
    }.toString()

    fun decodeProjection(value: String): EnergyPendingProjection {
        val json = JSONObject(value)
        require(json.getInt("schema_version") == EnergyRuntimeState.CURRENT_SCHEMA_VERSION) {
            "Unsupported energy projection schema"
        }
        return EnergyPendingProjection(decodeSnapshotObject(json.getJSONObject("snapshot")))
    }

    private fun encodeAnchor(anchor: EnergyAnchor) = JSONObject()
        .put("boot_id", anchor.bootId)
        .put("elapsed_ms", anchor.elapsedMs)
        .put("power_kw", anchor.powerKw)

    private fun decodeAnchor(json: JSONObject) = EnergyAnchor(
        bootId = json.getString("boot_id"),
        elapsedMs = json.getLong("elapsed_ms"),
        powerKw = json.finiteDouble("power_kw")
    )

    private fun encodeTotals(totals: EnergyTotals) = JSONObject()
        .put("discharged_kwh", totals.dischargedKwh)
        .put("regenerated_kwh", totals.regeneratedKwh)
        .put("covered_ms", totals.coveredMs)
        .put("uncovered_ms", totals.uncoveredMs)
        .put("partial", totals.partial)

    private fun decodeTotals(json: JSONObject) = EnergyTotals(
        dischargedKwh = json.finiteDouble("discharged_kwh"),
        regeneratedKwh = json.finiteDouble("regenerated_kwh"),
        coveredMs = json.getLong("covered_ms"),
        uncoveredMs = json.getLong("uncovered_ms"),
        partial = json.getBoolean("partial")
    )

    private fun encodeSnapshotObject(snapshot: EnergySnapshot) = JSONObject().apply {
        put("snapshot_id", snapshot.snapshotId)
        put("source_identity", snapshot.sourceIdentity)
        putNullable("power_session_id", snapshot.powerSessionId)
        putNullable("started_at", snapshot.startedAt)
        put("observed_at", snapshot.observedAt)
        put("source_boot_id", snapshot.sourceBootId)
        put("source_elapsed_ms", snapshot.sourceElapsedMs)
        put("active", snapshot.active)
        putNullable("discharged_kwh", snapshot.dischargedKwh)
        putNullable("regenerated_kwh", snapshot.regeneratedKwh)
        putNullable("net_kwh", snapshot.netKwh)
        put("energy_covered_ms", snapshot.energyCoveredMs)
        put("energy_uncovered_ms", snapshot.energyUncoveredMs)
        put("energy_partial", snapshot.energyPartial)
        put("integration_quality", snapshot.integrationQuality.name)
        put("reason", snapshot.reason)
    }

    private fun decodeSnapshotObject(json: JSONObject) = EnergySnapshot(
        snapshotId = json.getString("snapshot_id"),
        sourceIdentity = json.getString("source_identity"),
        powerSessionId = json.stringOrNull("power_session_id"),
        startedAt = json.stringOrNull("started_at"),
        observedAt = json.getString("observed_at"),
        sourceBootId = json.getString("source_boot_id"),
        sourceElapsedMs = json.getLong("source_elapsed_ms"),
        active = json.getBoolean("active"),
        dischargedKwh = json.finiteDoubleOrNull("discharged_kwh"),
        regeneratedKwh = json.finiteDoubleOrNull("regenerated_kwh"),
        netKwh = json.finiteDoubleOrNull("net_kwh"),
        energyCoveredMs = json.getLong("energy_covered_ms"),
        energyUncoveredMs = json.getLong("energy_uncovered_ms"),
        energyPartial = json.getBoolean("energy_partial"),
        integrationQuality = enumValueOf(json.getString("integration_quality")),
        reason = json.getString("reason")
    )

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.longOrNull(key: String): Long? =
        if (isNull(key)) null else getLong(key)

    private fun JSONObject.objectOrNull(key: String): JSONObject? =
        if (isNull(key)) null else getJSONObject(key)

    private fun JSONObject.finiteDouble(key: String): Double =
        getDouble(key).also { require(it.isFinite()) { "Non-finite energy value: $key" } }

    private fun JSONObject.finiteDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else finiteDouble(key)
}
