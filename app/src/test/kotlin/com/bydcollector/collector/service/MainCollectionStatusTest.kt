package com.bydcollector.collector.service

import android.app.Application
import com.bydcollector.collector.data.callback.CallbackDrainResult
import com.bydcollector.collector.data.callback.CallbackIntakeCondition
import com.bydcollector.collector.data.callback.CallbackIntakeWorker
import com.bydcollector.collector.data.callback.CallbackQueuePhase
import com.bydcollector.collector.data.callback.CallbackQueueState
import com.bydcollector.collector.data.callback.CallbackQueueStatusTracker
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class MainCollectionStatusTest {
    @Test
    fun telegramBusinessFaultIsJournaledWithoutTurningHealthyMainRed() {
        val context = RuntimeEnvironment.getApplication()
        val database = "main-collection-status.db"
        val ready = CountDownLatch(1)
        val intake = CallbackIntakeWorker(
            threadName = "test-main-callback",
            drain = { CallbackDrainResult(true, 0, 0, 0, 0) },
            armBatchReady = { AutoCloseable {} },
            onStatus = { if (it.condition == CallbackIntakeCondition.RUNNING) ready.countDown() },
            queueStatusTracker = CallbackQueueStatusTracker(caughtUpStabilityMs = 0L)
        )
        try {
            TelemetryStore(context, TelemetryDatabaseHelper(context, database),
                operationalEventJournal = OperationalEventJournal(context)).use { store ->
                // Construct only the service; do not start Android lifecycle, a helper, or exports.
                val service = CollectorService()
                ReflectionHelpers.setField(service, "store", store)
                ReflectionHelpers.setField(service, "mainCallbackIntake", intake)
                fun queue() = ReflectionHelpers.callInstanceMethod<CallbackQueueState>(service, "mainCollectionQueueState")

                try {
                    assertTrue(intake.start())
                    assertTrue(ready.await(5, TimeUnit.SECONDS), "Raw intake did not report a healthy drained queue")
                    assertEquals(CallbackQueuePhase.HEALTHY, queue().phase)
                    val fault = IllegalStateException("Main business page committed without Telegram receipt")
                    ReflectionHelpers.callInstanceMethod<Any?>(
                        service, "recordBusinessInputFault", ReflectionHelpers.ClassParameter.from(Throwable::class.java, fault)
                    )
                    val event = store.recentEvents().single { it.category == "business_input_error" }
                    assertContains(event.detail.orEmpty(), fault.toString())
                    assertEquals(CallbackQueuePhase.HEALTHY, queue().phase)

                    ReflectionHelpers.setField(service, "mainNormalizationFault", "normalization failed")
                    assertEquals(CallbackQueuePhase.ERROR, queue().phase)
                } finally {
                    assertTrue(intake.stopAndJoin(5_000))
                }
            }
        } finally {
            context.deleteDatabase(database)
        }
    }
}
