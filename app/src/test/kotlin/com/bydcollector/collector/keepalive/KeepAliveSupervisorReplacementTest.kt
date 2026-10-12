package com.bydcollector.collector.keepalive

import android.content.pm.PackageInfo
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import com.bydcollector.collector.runtime.RuntimePreferences
import com.bydcollector.collector.service.CollectorSettings
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class KeepAliveSupervisorReplacementTest {
    private lateinit var context: android.content.Context
    private lateinit var store: TelemetryStore
    private lateinit var settings: CollectorSettings
    private lateinit var packageInfo: PackageInfo
    private val supervisors = mutableListOf<KeepAliveSupervisor>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        assertTrue(RuntimePreferences.get(context).edit().clear().commit())
        store = TelemetryStore(
            context,
            TelemetryDatabaseHelper(context, "keepalive-replacement-${DATABASES.incrementAndGet()}.db"),
            operationalEventJournal = OperationalEventJournal(context)
        )
        settings = CollectorSettings(context, store)
        packageInfo = assertNotNull(shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName))
        packageInfo.lastUpdateTime = 1_800_000_000_000L
    }

    @After
    fun tearDown() {
        supervisors.forEach { it.shutdownAndAwait(100L) }
        store.close()
    }

    @Test
    fun legacyOrChangedInstalledIdentityForcesReplacementBeforeWarmCacheOrPidCheck() {
        settings.setConnectivityRecoveryEnabled(true)
        val shell = RecordingShell()
        val supervisor = supervisor(shell)
        val config = settings.keepAliveConfig()
        val identityPath = context.applicationInfo.sourceDir
        val originalUpdateTime = packageInfo.lastUpdateTime

        assertTrue(supervisor.reconcileBlocking(config))
        assertTrue(settings.keepAliveLaunchMatches(identityPath, originalUpdateTime))
        assertBefore(shell.commands, KeepAliveShellPlanner.daemonStopCommand(), KeepAliveShellPlanner.daemonLaunchCommand(identityPath))
        assertFalse(shell.commands.contains(KeepAliveShellPlanner.daemonStatusCommand()), "legacy stamp must not accept an old PID")

        shell.commands.clear()
        assertTrue(supervisor.reconcileBlocking(config))
        assertTrue(shell.commands.isEmpty(), "same installed APK may reuse the fresh liveness result")

        packageInfo.lastUpdateTime += 1L
        val changedUpdateTime = packageInfo.lastUpdateTime
        assertTrue(supervisor.reconcileBlocking(config))
        assertBefore(shell.commands, KeepAliveShellPlanner.daemonStopCommand(), KeepAliveShellPlanner.daemonLaunchCommand(identityPath))
        assertFalse(shell.commands.contains(KeepAliveShellPlanner.daemonStatusCommand()), "changed APK identity must be checked before pidof")
        assertTrue(settings.keepAliveLaunchMatches(identityPath, changedUpdateTime))
    }

    @Test
    fun failedStopNeverLaunchesOrConfirmsAndLaterStopCanRecover() {
        settings.setConnectivityRecoveryEnabled(true)
        val shell = RecordingShell(stopResults = listOf(false, true))
        val supervisor = supervisor(shell)
        val identityPath = context.applicationInfo.sourceDir

        assertFalse(supervisor.reconcileBlocking(settings.keepAliveConfig()))
        assertTrue(shell.commands.contains(KeepAliveShellPlanner.daemonStopCommand()))
        assertFalse(shell.commands.any(::isLaunchCommand))
        assertFalse(settings.keepAliveLaunchMatches(identityPath, packageInfo.lastUpdateTime))

        assertTrue(supervisor.reconcileBlocking(settings.keepAliveConfig()))
        assertTrue(shell.commands.any(::isLaunchCommand))
        assertTrue(settings.keepAliveLaunchMatches(identityPath, packageInfo.lastUpdateTime))
    }

    @Test
    fun explicitDisableAndExistingShutdownDoNotLaunchOrWriteLaunchStamp() {
        settings.setConnectivityRecoveryEnabled(true)
        val explicitDisableShell = RecordingShell()
        assertTrue(
            supervisor(explicitDisableShell).reconcileBlocking(KeepAliveConfig(false, false, false, false))
        )
        assertFalse(explicitDisableShell.commands.any(::isLaunchCommand))
        assertFalse(settings.keepAliveLaunchMatches(context.applicationInfo.sourceDir, packageInfo.lastUpdateTime))

        assertTrue(settings.setUserShutdownRequested(true))
        val shutdownShell = RecordingShell()
        assertTrue(supervisor(shutdownShell).reconcileBlocking(settings.keepAliveConfig()))
        assertTrue(shutdownShell.commands.contains(KeepAliveShellPlanner.daemonStopCommand()))
        assertFalse(shutdownShell.commands.any(::isLaunchCommand))
        assertFalse(settings.keepAliveLaunchMatches(context.applicationInfo.sourceDir, packageInfo.lastUpdateTime))
    }

    @Test
    fun settingsOrShutdownChangedDuringReplacementStopPreventsLaunch() {
        settings.setConnectivityRecoveryEnabled(true)
        val settingChangeShell = RecordingShell(onStop = { settings.setConnectivityRecoveryEnabled(false) })
        assertTrue(supervisor(settingChangeShell).reconcileBlocking(settings.keepAliveConfig()))
        assertFalse(settingChangeShell.commands.any(::isLaunchCommand))
        assertTrue(settingChangeShell.commands.contains("settings put global bydcollector_keep_wifi 0"))
        assertFalse(settings.keepAliveLaunchMatches(context.applicationInfo.sourceDir, packageInfo.lastUpdateTime))

        settings.setConnectivityRecoveryEnabled(true)
        val shutdownChangeShell = RecordingShell(onStop = { assertTrue(settings.setUserShutdownRequested(true)) })
        assertTrue(supervisor(shutdownChangeShell).reconcileBlocking(settings.keepAliveConfig()))
        assertFalse(shutdownChangeShell.commands.any(::isLaunchCommand))
        assertTrue(shutdownChangeShell.commands.contains("settings put global bydcollector_user_shutdown 1"))
        assertFalse(settings.keepAliveLaunchMatches(context.applicationInfo.sourceDir, packageInfo.lastUpdateTime))
    }

    @Test
    fun desiredSettingsChangedDuringPidCheckPreventsStaleLaunch() {
        settings.setConnectivityRecoveryEnabled(true)
        val path = context.applicationInfo.sourceDir
        assertTrue(settings.confirmKeepAliveLaunch(path, packageInfo.lastUpdateTime))
        val shell = RecordingShell(
            statusResult = false,
            onStatus = { settings.setConnectivityRecoveryEnabled(false) }
        )

        assertFalse(supervisor(shell).reconcileBlocking(settings.keepAliveConfig()))
        assertFalse(shell.commands.any(::isLaunchCommand))
        assertTrue(shell.commands.contains(KeepAliveShellPlanner.daemonStatusCommand()))
        assertTrue(settings.keepAliveLaunchMatches(path, packageInfo.lastUpdateTime))
    }

    @Test
    fun separateSupervisorsSerializeReplacementAcrossTheWholeProcess() {
        settings.setConnectivityRecoveryEnabled(true)
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val firstShell = RecordingShell(onStop = {
            stopEntered.countDown()
            check(releaseStop.await(3, TimeUnit.SECONDS))
        })
        val secondShell = RecordingShell()
        val config = settings.keepAliveConfig()
        val first = supervisor(firstShell)
        val second = supervisor(secondShell)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val firstResult = workers.submit<Boolean> { first.reconcileBlocking(config) }
            assertTrue(stopEntered.await(2, TimeUnit.SECONDS), "first replacement should reach its bounded stop")
            val secondStarted = CountDownLatch(1)
            val secondResult = workers.submit<Boolean> {
                secondStarted.countDown()
                second.reconcileBlocking(config)
            }
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            assertFalse(secondShell.firstCommandEntered.await(100, TimeUnit.MILLISECONDS), "a second supervisor must wait on the process-wide lock")

            releaseStop.countDown()
            assertTrue(firstResult.get(3, TimeUnit.SECONDS))
            assertTrue(secondResult.get(3, TimeUnit.SECONDS))
            assertFalse(secondShell.commands.contains(KeepAliveShellPlanner.daemonStopCommand()))
        } finally {
            releaseStop.countDown()
            workers.shutdownNow()
        }
    }

    private fun supervisor(shell: RecordingShell): KeepAliveSupervisor = KeepAliveSupervisor(
        context = context,
        store = store,
        shellFactory = { shell },
        clockMs = { 50_000L }
    ).also(supervisors::add)

    private fun assertBefore(commands: List<String>, first: String, second: String) {
        assertTrue(commands.indexOf(first) >= 0, "expected command: $first")
        assertTrue(commands.indexOf(second) > commands.indexOf(first), "$first must precede $second: $commands")
    }

    private fun isLaunchCommand(command: String): Boolean =
        command.contains("setsid app_process /system/bin --nice-name=bydcollector_keepalive")

    private class RecordingShell(
        stopResults: List<Boolean> = emptyList(),
        private val onStop: (() -> Unit)? = null,
        private val onStatus: (() -> Unit)? = null,
        private val statusResult: Boolean = true,
        private val retryStatusResult: Boolean = true
    ) : KeepAliveShell {
        val commands = CopyOnWriteArrayList<String>()
        val firstCommandEntered = CountDownLatch(1)
        private val stops = java.util.ArrayDeque<Boolean>().apply { addAll(stopResults) }

        override fun exec(command: String, timeoutMs: Int): KeepAliveShellResult {
            commands += command
            firstCommandEntered.countDown()
            val ok = when (command) {
                KeepAliveShellPlanner.daemonStopCommand() -> {
                    onStop?.invoke()
                    stops.pollFirst() ?: true
                }
                KeepAliveShellPlanner.daemonStatusCommand() -> {
                    onStatus?.invoke()
                    statusResult
                }
                KeepAliveShellPlanner.daemonStatusRetryCommand() -> retryStatusResult
                else -> true
            }
            return KeepAliveShellResult(ok, if (ok) "ok" else "failed", null, 1L)
        }
    }

    private companion object {
        val DATABASES = AtomicInteger()
    }
}
