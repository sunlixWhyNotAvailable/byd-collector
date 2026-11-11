plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bydcollector.collector"
    compileSdk = 35
    ndkVersion = "30.0.15729638"

    defaultConfig {
        applicationId = "com.bydcollector.collector"
        testInstrumentationRunner = "com.bydcollector.collector.NativeSqliteInstrumentation"
        minSdk = 26
        targetSdk = 29
        versionCode = 334
        versionName = "3.5.3"
        manifestPlaceholders["collectorLabel"] = "BYD Collector"
        buildConfigField("String", "COLLECTOR_DISPLAY_NAME", "\"BYD Collector\"")
        buildConfigField("String", "COLLECTOR_DATABASE_NAME", "\"bydcollector_telemetry.db\"")
        buildConfigField("String", "UPDATE_RELEASES_API_URL", "\"https://api.github.com/repos/sunlixWhyNotAvailable/byd-collector/releases/latest\"")
        buildConfigField("String", "ACTION_PREFIX", "\"com.bydcollector.collector\"")
        buildConfigField("boolean", "RUNTIME_CLIENT", "false")
        buildConfigField("String", "RUNTIME_REVISION", "\"${rootProject.extra["runtimeRevision"]}\"")
    }

    testBuildType = "performance"

    buildTypes {
        create("performance") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Isolate process-owned runtime state between test classes.
        unitTests.all { it.forkEvery = 1 }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val tailscaleJniOutput = layout.buildDirectory.dir("generated/tailscaleJniLibs")
android.sourceSets.getByName("main").jniLibs.srcDir(tailscaleJniOutput)
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("tailscale/notices"))
val buildTailscaleNative by tasks.registering(Exec::class) {
    inputs.dir("src/main/native/tailscale")
    outputs.dir(tailscaleJniOutput)
    outputs.dir(layout.buildDirectory.dir("tailscale/notices"))
    commandLine("pwsh", "-NoProfile", "-File", file("src/main/native/tailscale/build.ps1").absolutePath,
        "-GoExecutable", providers.gradleProperty("collectorGo").getOrElse("go.exe"),
        "-AndroidNdk", android.sdkDirectory.resolve("ndk/${android.ndkVersion}").absolutePath,
        "-BuildRoot", layout.buildDirectory.dir("tailscale").get().asFile.absolutePath,
        "-OutputRoot", tailscaleJniOutput.get().asFile.absolutePath)
    providers.gradleProperty("collectorGoModuleCache").orNull?.let { args("-ModuleCache", it) }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildTailscaleNative) }

val bundledUi = tasks.register<Copy>("bundleRuntimeUi") {
    dependsOn(":collector-ui:assemblePerformance")
    from(project(":collector-ui").layout.buildDirectory.file("outputs/apk/performance/collector-ui-performance.apk"))
    into(layout.buildDirectory.dir("generated/runtimeUiAssets"))
    rename { "collector-ui.apk" }
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/runtimeUiAssets"))
tasks.named("preBuild") { dependsOn(bundledUi) }

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("androidx.core:core:1.13.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
