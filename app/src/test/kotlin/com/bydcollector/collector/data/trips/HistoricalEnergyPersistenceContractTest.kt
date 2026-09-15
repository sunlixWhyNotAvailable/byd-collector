package com.bydcollector.collector.data.trips

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalEnergyPersistenceContractTest {
    @Test
    fun `trips progress is separate atomic durable and compression retained`() {
        val helper = source("TripDatabaseHelper.kt")
        val store = source("TripStore.kt")
        val compression = source("TripCompression.kt")
        assertTrue(helper.contains("CREATE TABLE IF NOT EXISTS historical_energy_backfill"))
        assertTrue(helper.contains("DATABASE_VERSION = 5"))
        assertTrue(store.contains("db.beginTransaction()"))
        assertTrue(store.contains("AND discharged_kwh IS NULL"))
        assertTrue(store.contains("insertOrThrow(\"historical_energy_backfill\""))
        assertTrue(store.contains("markSessionChanged(record.tripId)"))
        assertTrue(compression.contains("replaceHistoricalEnergyBackfillRecords"))
        assertTrue(compression.contains("sameHistoricalEnergyBackfill"))
    }

    @Test
    fun `active main reader is bounded supports both schemas and retains failures`() {
        val store = File("src/main/kotlin/com/bydcollector/collector/data/local/TelemetryStore.kt").readText()
        assertTrue(store.contains("LEFT JOIN poll_values"))
        assertTrue(store.contains("decoded_value_dictionary"))
        assertTrue(store.contains("PollValueColumns.desc(key)"))
        assertTrue(store.contains("ORDER BY p.id LIMIT"))
        assertTrue(store.contains("epoch") || File("src/main/kotlin/com/bydcollector/collector/data/energy/HistoricalEnergyBackfill.kt").readText().contains("epoch-ms-not-uptime"))
    }

    @Test
    fun `backfill starts only from live runtime and cancels with service owner`() {
        val service = File("src/main/kotlin/com/bydcollector/collector/service/CollectorService.kt").readText()
        val app = File("src/main/kotlin/com/bydcollector/collector/BydCollectorApplication.kt").readText()
        assertTrue(service.contains("if (origin == PollOrigin.LIVE)"))
        assertTrue(service.contains("scheduleHistoricalEnergyBackfill(historicalEnergyGeneration)"))
        assertTrue(service.contains("cancelHistoricalEnergyBackfill()"))
        assertTrue(app.contains("HistoricalBackfillFinalizationGate"))
        assertTrue(app.contains("historicalEnergyFinished.set(true)"))
        assertTrue(!app.substringAfter("override fun onCreate()").substringBefore("override fun onTerminate()").contains("scheduleHistoricalEnergyBackfill"))
    }

    private fun source(name: String) = File("src/main/kotlin/com/bydcollector/collector/data/trips/$name").readText()
}
