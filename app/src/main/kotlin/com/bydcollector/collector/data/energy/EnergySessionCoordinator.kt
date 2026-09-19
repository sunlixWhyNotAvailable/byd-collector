package com.bydcollector.collector.data.energy

class EnergySessionCoordinator(
    private val storage: EnergyRuntimeStorage,
    private val openTripSeed: () -> EnergySessionSeed? = { null }
) {
    @Synchronized
    fun pendingProjection(): EnergyPendingProjection? = readStored()?.pending

    @Synchronized
    fun currentSnapshot(): EnergySnapshot? = readStored()?.state?.currentSnapshot

    @Synchronized
    fun process(receipt: EnergyReceipt): EnergyProcessResult {
        require(receipt.input.bootId.isNotBlank()) { "Energy source boot ID is blank" }
        require(receipt.input.elapsedMs >= 0L) { "Energy source elapsed time is negative" }
        val stored = readStored()
        val state = stored?.state ?: EnergyRuntimeState()
        val pending = stored?.pending
        if (state.recoveryState != EnergyRecoveryState.NONE) {
            return recoverAfterCorruption(state, receipt)
        }
        if (pending != null && pending.snapshot.sourceIdentity != receipt.sourceIdentity) {
            throw EnergyProjectionPendingException(pending)
        }
        if (state.lastSourceIdentity == receipt.sourceIdentity) {
            val snapshot = requireNotNull(state.currentSnapshot) { "Duplicate energy source has no snapshot" }
            // An inactive high-water-only receipt deliberately retains the final
            // session snapshot. Its ACK replay must not project that older time.
            val projection = pending ?: snapshot
                .takeIf { it.sourceIdentity == receipt.sourceIdentity }
                ?.let(::EnergyPendingProjection)
                ?.also { write(state, it, receipt.observedAt) }
            return EnergyProcessResult(snapshot, projection, duplicate = true)
        }
        if (
            state.lastSourceBootId == receipt.input.bootId &&
            state.lastSourceElapsedMs != null &&
            receipt.input.elapsedMs <= state.lastSourceElapsedMs
        ) {
            return EnergyProcessResult(state.currentSnapshot, null, stale = true)
        }
        if (!state.active && receipt.input.powerOn != true && state.currentSnapshot != null) {
            val next = state.copy(
                powerState = if (receipt.input.powerOn == false) EnergyPowerState.OFF else state.powerState,
                lastSourceIdentity = receipt.sourceIdentity,
                lastSourceBootId = receipt.input.bootId,
                lastSourceElapsedMs = receipt.input.elapsedMs,
                anchor = null
            )
            write(next, null, receipt.observedAt)
            return EnergyProcessResult(state.currentSnapshot, null)
        }

        val next = advance(state, receipt)
        val projection = EnergyPendingProjection(next.state.currentSnapshot!!)
        write(next.state, projection, receipt.observedAt)
        return EnergyProcessResult(
            snapshot = next.state.currentSnapshot,
            pendingProjection = projection,
            transition = next.transition
        )
    }

    @Synchronized
    fun confirmProjected(snapshotId: String): Boolean {
        val stored = readStored() ?: return false
        val pending = stored.pending
            ?: return stored.state.currentSnapshot?.snapshotId == snapshotId
        if (pending.snapshot.snapshotId != snapshotId) return false
        return storage.clearEnergyPending(
            expectedPendingJson = requireNotNull(stored.row.pendingProjectionJson),
            updatedAt = pending.snapshot.observedAt
        )
    }

    @Synchronized
    fun stageCurrentProjection(): EnergyPendingProjection? {
        val stored = readStored() ?: return null
        stored.pending?.let { return it }
        val snapshot = stored.state.currentSnapshot ?: return null
        return EnergyPendingProjection(snapshot).also { write(stored.state, it, snapshot.observedAt) }
    }

    private fun advance(state: EnergyRuntimeState, receipt: EnergyReceipt): AdvancedState {
        val nextPower = receipt.input.powerOn.toPowerState()
        var transition = EnergySessionTransition.NONE
        var active = state.active
        var sessionId = state.powerSessionId
        var startedAt = state.startedAt
        var totals = state.totals
        var anchor = state.anchor
        var forcedReason: String? = null

        if (!active && nextPower == EnergyPowerState.ON) {
            val knownBoundary = state.powerState == EnergyPowerState.OFF
            val seed = if (state.powerSessionId == null && state.powerState != EnergyPowerState.OFF) openTripSeed() else null
            sessionId = seed?.powerSessionId ?: "energy:${receipt.sourceIdentity}"
            startedAt = seed?.startedAt ?: receipt.observedAt
            totals = EnergyTotals(partial = !knownBoundary)
            anchor = null
            active = true
            transition = EnergySessionTransition.STARTED
            if (!knownBoundary) forcedReason = EnergyRuntimeState.REASON_STARTED_MID_POWER_SESSION
        }

        val integration = if (active && nextPower == EnergyPowerState.OFF) {
            BatteryEnergyIntegrator.finishSession(anchor, totals, receipt.input)
        } else if (active) {
            BatteryEnergyIntegrator.integrate(anchor, totals, receipt.input)
        } else {
            EnergyIntegrationResult(
                totals = totals,
                anchor = null,
                quality = EnergyIntegrationQuality.EXCLUDED,
                reason = if (nextPower == EnergyPowerState.OFF) {
                    EnergyIntegrationReason.POWER_OFF
                } else {
                    EnergyIntegrationReason.POWER_NOT_CONFIRMED
                }
            )
        }
        totals = integration.totals
        anchor = integration.anchor

        if (active && nextPower == EnergyPowerState.OFF) {
            active = false
            anchor = null
            transition = EnergySessionTransition.STOPPED
        }
        val reason = forcedReason ?: integration.reason.name.lowercase()
        val hasMeasuredInterval = totals.coveredMs > 0L
        val snapshot = EnergySnapshot(
            snapshotId = "energy:${receipt.sourceIdentity}",
            sourceIdentity = receipt.sourceIdentity,
            powerSessionId = sessionId,
            startedAt = startedAt,
            observedAt = receipt.observedAt,
            sourceBootId = receipt.input.bootId,
            sourceElapsedMs = receipt.input.elapsedMs,
            active = active,
            dischargedKwh = totals.dischargedKwh.takeIf { hasMeasuredInterval },
            regeneratedKwh = totals.regeneratedKwh.takeIf { hasMeasuredInterval },
            netKwh = totals.netKwh.takeIf { hasMeasuredInterval },
            energyCoveredMs = totals.coveredMs,
            energyUncoveredMs = totals.uncoveredMs,
            energyPartial = totals.partial,
            integrationQuality = integration.quality,
            reason = reason
        )
        return AdvancedState(
            EnergyRuntimeState(
                powerState = nextPower,
                powerSessionId = sessionId,
                startedAt = startedAt,
                active = active,
                lastSourceIdentity = receipt.sourceIdentity,
                lastSourceBootId = receipt.input.bootId,
                lastSourceElapsedMs = receipt.input.elapsedMs,
                anchor = anchor,
                totals = totals,
                integrationQuality = integration.quality,
                reason = reason,
                currentSnapshot = snapshot
            ),
            transition
        )
    }

    private fun recoverAfterCorruption(state: EnergyRuntimeState, receipt: EnergyReceipt): EnergyProcessResult {
        if (state.lastSourceIdentity == receipt.sourceIdentity) {
            return EnergyProcessResult(snapshot = null, pendingProjection = null, duplicate = true)
        }
        if (
            state.lastSourceBootId == receipt.input.bootId &&
            state.lastSourceElapsedMs != null &&
            receipt.input.elapsedMs <= state.lastSourceElapsedMs
        ) {
            return EnergyProcessResult(snapshot = null, pendingProjection = null, stale = true)
        }
        if (
            state.recoveryState == EnergyRecoveryState.WAITING_FOR_ON &&
            receipt.input.powerOn == true
        ) {
            val next = advance(state.copy(recoveryState = EnergyRecoveryState.NONE), receipt)
            val projection = EnergyPendingProjection(requireNotNull(next.state.currentSnapshot))
            write(next.state, projection, receipt.observedAt)
            return EnergyProcessResult(next.state.currentSnapshot, projection, transition = next.transition)
        }

        val confirmedOff = receipt.input.powerOn == false
        val next = state.copy(
            powerState = if (confirmedOff) EnergyPowerState.OFF else state.powerState,
            lastSourceIdentity = receipt.sourceIdentity,
            lastSourceBootId = receipt.input.bootId,
            lastSourceElapsedMs = receipt.input.elapsedMs,
            recoveryState = if (confirmedOff) {
                EnergyRecoveryState.WAITING_FOR_ON
            } else {
                state.recoveryState
            }
        )
        write(next, null, receipt.observedAt)
        return EnergyProcessResult(snapshot = null, pendingProjection = null)
    }

    private fun readStored(): Stored? {
        val row = storage.readEnergyRuntimeRow() ?: return null
        val state = decodeOrNull { EnergyStateCodec.decodeState(row.stateJson) }
        val pending = row.pendingProjectionJson?.let { value ->
            decodeOrNull { EnergyStateCodec.decodeProjection(value) }
        }
        if (state != null && (pending == null || state.currentSnapshot == pending.snapshot)) {
            if (row.pendingProjectionJson == null || pending != null) return Stored(row, state, pending)
        }

        val reason = when {
            state != null -> QUARANTINE_BAD_PENDING
            pending != null -> QUARANTINE_BAD_CHECKPOINT
            else -> QUARANTINE_BAD_RUNTIME
        }
        storage.quarantineEnergyRuntimeRow(row, reason, row.updatedAt)
        val recoveredPending = when {
            state != null -> state.currentSnapshot?.let(::EnergyPendingProjection)
            pending != null -> pending.withTechnicalGap()
            else -> null
        }
        val recoveredState = state ?: recoveredPending?.let(::reconstructFromPending) ?: quarantinedState()
        val recoveredRow = EnergyRuntimeRow(
            stateJson = EnergyStateCodec.encodeState(recoveredState),
            pendingProjectionJson = recoveredPending?.let(EnergyStateCodec::encodeProjection),
            updatedAt = row.updatedAt
        )
        storage.commitEnergyRuntimeRow(
            recoveredRow.stateJson,
            recoveredRow.pendingProjectionJson,
            recoveredRow.updatedAt
        )
        return Stored(recoveredRow, recoveredState, recoveredPending)
    }

    private fun reconstructFromPending(pending: EnergyPendingProjection): EnergyRuntimeState {
        val snapshot = pending.snapshot
        val totals = EnergyTotals(
            dischargedKwh = snapshot.dischargedKwh ?: 0.0,
            regeneratedKwh = snapshot.regeneratedKwh ?: 0.0,
            coveredMs = snapshot.energyCoveredMs,
            uncoveredMs = snapshot.energyUncoveredMs,
            partial = true
        )
        return EnergyRuntimeState(
            powerState = if (snapshot.active) EnergyPowerState.ON else EnergyPowerState.OFF,
            powerSessionId = snapshot.powerSessionId,
            startedAt = snapshot.startedAt,
            active = snapshot.active,
            lastSourceIdentity = snapshot.sourceIdentity,
            lastSourceBootId = snapshot.sourceBootId,
            lastSourceElapsedMs = snapshot.sourceElapsedMs,
            anchor = null,
            totals = totals,
            integrationQuality = EnergyIntegrationQuality.PARTIAL,
            reason = EnergyRuntimeState.REASON_RECOVERED_PENDING,
            currentSnapshot = snapshot
        )
    }

    private fun EnergyPendingProjection.withTechnicalGap() = EnergyPendingProjection(
        snapshot.copy(
            energyPartial = true,
            integrationQuality = EnergyIntegrationQuality.PARTIAL,
            reason = EnergyRuntimeState.REASON_RECOVERED_PENDING
        )
    )

    private fun quarantinedState() = EnergyRuntimeState(
        reason = EnergyRuntimeState.REASON_CORRUPT_STATE,
        recoveryState = EnergyRecoveryState.WAITING_FOR_OFF
    )

    private inline fun <T> decodeOrNull(block: () -> T): T? = try {
        block()
    } catch (interrupted: InterruptedException) {
        throw interrupted
    } catch (_: Exception) {
        null
    }

    private fun write(state: EnergyRuntimeState, pending: EnergyPendingProjection?, updatedAt: String) {
        storage.commitEnergyRuntimeRow(
            stateJson = EnergyStateCodec.encodeState(state),
            pendingProjectionJson = pending?.let(EnergyStateCodec::encodeProjection),
            updatedAt = updatedAt
        )
    }

    private fun Boolean?.toPowerState(): EnergyPowerState = when (this) {
        true -> EnergyPowerState.ON
        false -> EnergyPowerState.OFF
        null -> EnergyPowerState.UNKNOWN
    }

    private data class Stored(
        val row: EnergyRuntimeRow,
        val state: EnergyRuntimeState,
        val pending: EnergyPendingProjection?
    )

    private data class AdvancedState(
        val state: EnergyRuntimeState,
        val transition: EnergySessionTransition
    )

    private companion object {
        const val QUARANTINE_BAD_PENDING = "invalid_or_mismatched_pending_projection"
        const val QUARANTINE_BAD_CHECKPOINT = "invalid_checkpoint_recovered_from_pending"
        const val QUARANTINE_BAD_RUNTIME = "invalid_energy_runtime_state"
    }
}
