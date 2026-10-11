package com.bydcollector.collector.ha

import android.os.Looper
import android.os.SystemClock
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedValue
import com.bydcollector.collector.data.normalized.NormalizedValueType
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorServiceController
import com.bydcollector.collector.service.CollectorSettings
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = BydCollectorApplication::class)
class HaRunSessionTest {
    private val app get() = RuntimeEnvironment.getApplication() as BydCollectorApplication
    private var serviceController: ServiceController<CollectorService>? = null
    private var service: CollectorService? = null
    private var nextStartId = 1

    @Test
    fun manualHaPermissionExpiresAtNextPowerSessionAndOwnerDoesNotRestartIt() {
        CollectorService.influxConnection.release()
        HaRunSession.process.restoreAfterTaskRemoval(mqtt = false, influx = false, lastPowerOn = null)
        val settings = CollectorSettings(app)
        settings.setPollingEnabled(false)
        settings.setDebugPollingEnabled(false)
        settings.setMqttEnabled(false)
        settings.setInfluxEnabled(false)
        settings.setInfluxAutoStartEnabled(false)
        settings.setInfluxManuallyStopped(false)
        settings.setTelegramEnabled(false)
        settings.setConnectivityRecoveryEnabled(false)

        val endpoint = LocalInfluxEndpoint()
        val store = BydCollectorApplication.store(app)
        val soc = NormalizedFieldCatalog.soc
        settings.setInfluxHost("127.0.0.1")
        settings.setInfluxPort(endpoint.port)
        settings.setInfluxCategoryEnabled("battery", true)
        insertSoc(store, soc, 73.0, Instant.now().toString())
        settings.observeHaPower(1L) // establish a real ON baseline before the manual grant

        try {
            createOwner()
            // Match the UI contract: commit desired state before requesting the manual session.
            settings.setInfluxManuallyStopped(false)
            settings.setInfluxEnabled(true)
            CollectorServiceController.startInfluxExport(app)
            dispatchStartedServices()
            await(
                condition = { endpoint.requests.size == 1 && store.pendingInfluxRows(setOf(soc.fieldKey), 10).isEmpty() },
                diagnostic = {
                    val state = store.influxExportState()
                    "requests=${endpoint.requests.size}, pending=${store.pendingInfluxRows(setOf(soc.fieldKey), 10).map { it.id }}, " +
                        "exportStatus=${state.status}, exported=${state.exportedRowsTotal}, " +
                        "runtime=${CollectorService.influxRuntimeStatus()}, owned=${CollectorService.influxConnection.owned}, " +
                        "stopping=${CollectorService.influxConnection.stopping}, route=${CollectorService.influxConnection.state.value.activeRoute}, " +
                        "demand=${settings.runtimeDemand().influx}, enabled=${settings.isInfluxEnabled()}, " +
                        "manualStop=${settings.isInfluxManuallyStopped()}, autoStart=${settings.isInfluxAutoStartEnabled()}, " +
                        "queuedStart=${shadowOf(app).nextStartedService?.action}, " +
                        "runtimeInitialized=${service?.let { ReflectionHelpers.getField<Boolean>(it, "runtimeInitialized") }}, " +
                        "influxDiagnostics=${CollectorService.influxRuntimeDiagnostics.snapshotLines().joinToString(" | ")}"
                }
            )
            assertTrue(settings.runtimeDemand().influx, "manual Start must authorize this power session")

            assertFalse(settings.observeHaPower(0L), "OFF must not end the current manual grant")
            assertTrue(settings.runtimeDemand().influx)
            assertTrue(CollectorService.influxConnection.owned, "manual export remains active through OFF")
            assertTrue(settings.observeHaPower(1L), "the observed OFF→ON edge starts a new session")
            assertFalse(settings.runtimeDemand().influx, "the new session must not inherit manual Start")
            assertFalse(settings.isInfluxAutoStartEnabled())

            // Robolectric does not run the KPI read worker; exercise its real observer result and
            // the owner's real stop path separately, without claiming the helper callback ran.
            ReflectionHelpers.callInstanceMethod<Unit>(
                requireNotNull(service),
                "stopInfluxExport",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false)
            )
            await { !CollectorService.influxConnection.owned && !settings.isInfluxEnabled() }
            serviceController?.destroy()
            serviceController = null
            service = null

            insertSoc(store, soc, 72.0, Instant.now().plusSeconds(1).toString())
            val second = store.pendingInfluxRows(setOf(soc.fieldKey), 10).single()
            createOwner()
            CollectorServiceController.reconcileInfluxExport(app)
            dispatchStartedServices()
            await { ReflectionHelpers.getField<Boolean>(requireNotNull(service), "runtimeInitialized") }
            await { !CollectorService.influxConnection.owned }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            assertEquals(1, endpoint.requests.size, "auto-reconcile after recreate must not send in the new session")
            assertEquals(second.id, store.pendingInfluxRows(setOf(soc.fieldKey), 10).single().id)
            assertFalse(settings.runtimeDemand().influx)

            // Auto-start is a control: enabling it in this new session authorizes reconcile.
            val resumeClock = MutableClock(System.currentTimeMillis())
            val resumedCoordinator = ReflectionHelpers.getField<com.bydcollector.collector.influx.InfluxExportCoordinator>(
                requireNotNull(service), "influxCoordinator"
            )
            ReflectionHelpers.setField(resumedCoordinator, "clock", resumeClock)
            settings.setInfluxAutoStartEnabled(true)
            CollectorServiceController.reconcileInfluxExport(app)
            dispatchStartedServices()
            await {
                store.influxExportState().status == "scheduled" &&
                    CollectorService.influxConnection.owned &&
                    ReflectionHelpers.getField<Boolean>(requireNotNull(service), "influxRetryScheduled")
            }
            // Advance both the persisted-clock comparison and the real Handler deadline.
            resumeClock.advanceBy(2_000L)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            await { endpoint.requests.size == 2 && store.pendingInfluxRows(setOf(soc.fieldKey), 10).isEmpty() }
            assertTrue(settings.runtimeDemand().influx)
            assertTrue(settings.isInfluxAutoStartEnabled())
        } finally {
            runCatching {
                if (CollectorService.influxConnection.owned) {
                    CollectorServiceController.stopInfluxExport(app)
                    dispatchStartedServices()
                }
            }
            runCatching { serviceController?.destroy() }
            serviceController = null
            service = null
            HaRunSession.process.restoreAfterTaskRemoval(mqtt = false, influx = false, lastPowerOn = null)
            endpoint.close()
        }
    }

    private fun insertSoc(
        store: com.bydcollector.collector.data.local.TelemetryStore,
        field: com.bydcollector.collector.data.normalized.NormalizedFieldDefinition,
        value: Double,
        observedAt: String
    ) {
        store.applyNormalizedObservations(listOf(NormalizedObservation(
        field = field,
        value = NormalizedValue(NormalizedValueType.NUMBER, number = value),
        quality = NormalizedQuality.OK,
        sourcePollId = null,
        sourceKey = field.sourceKeys.firstOrNull(),
        observedAt = observedAt
        )))
    }

    private fun createOwner() {
        if (serviceController != null) return
        shadowOf(app).grantPermissions("com.bydcollector.collector.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        serviceController = Robolectric.buildService(CollectorService::class.java).create()
        service = requireNotNull(serviceController).get()
        await { ReflectionHelpers.getField<Boolean>(requireNotNull(service), "runtimeInitialized") }
    }

    private fun dispatchStartedServices() {
        while (true) {
            val intent = shadowOf(app).nextStartedService ?: return
            val owner = service ?: run {
                createOwner()
                requireNotNull(service)
            }
            owner.onStartCommand(intent, 0, nextStartId++)
        }
    }

    private fun await(diagnostic: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) {
                "Timed out waiting for the HA service owner: ${diagnostic()}"
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private class MutableClock(@Volatile private var nowMs: Long) : Clock {
        override fun nowIso(): String = Instant.ofEpochMilli(nowMs).toString()
        override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
        fun advanceBy(deltaMs: Long) { nowMs += deltaMs }
    }

    private class LocalInfluxEndpoint : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val requests = CopyOnWriteArrayList<String>()
        private val worker = thread(isDaemon = true, name = "ha-session-influx-endpoint") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                socket.use {
                    it.soTimeout = 5_000
                    val reader = it.getInputStream().bufferedReader(Charsets.UTF_8)
                    requests += reader.readLine().orEmpty()
                    var length = 0
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            length = header.substringAfter(':').trim().toInt()
                        }
                    }
                    repeat(length) { reader.read() }
                    it.getOutputStream().write(
                        "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    )
                }
            }
        }

        override fun close() {
            server.close()
            worker.join(5_000L)
            check(!worker.isAlive)
        }
    }
}
