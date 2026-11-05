# Outgoing HA Tailscale transport

Pinned: Tailscale v1.96.4, Go 1.26.10, Android NDK 30.0.15729638-beta2.
Gradle builds the JNI library for arm64-v8a, armeabi-v7a, x86_64 and x86.
Install the pinned Go/NDK, then run the normal Performance build with optional
`-PcollectorGo=<absolute go.exe>` and `-PcollectorGoModuleCache=<module cache>`.
PowerShell 7 is required. Generated source, caches, JNI and dependency notices
remain under `app/build`; no library binaries are committed.

The native server is owned by the runtime APP UID. It only performs outgoing
`tsnet.Server.Dial` calls. Its loopback bridge requires a random capability for
an exact host/port; neither caller credentials nor lease tokens reach logs.
There is no Android VpnService, incoming Serve/Funnel endpoint or physical-network
binding. HTTP forwards once; MQTT uses CONNECT while retaining Paho's own
protocol, QoS and acknowledgement handling.

State uses AES-GCM with a runtime Android-Keystore-wrapped random key. Atomic
encrypted replacement preserves damaged identity instead of overwriting it.
The two reviewed upstream patches disable automatic log upload and expose the
existing network monitor's event injection for Android connectivity callbacks.
The build verifies the pinned upstream file before applying these patches.

`build.ps1 -HostTest` runs real encrypted-state persistence checks. The Kotlin
`HaTransportGateTest` checks actual parallel operations and persisted completion
before handover. Neither substitutes transport outcomes. Authenticated delivery
and Android VPN coexistence require real user-owned endpoints.
