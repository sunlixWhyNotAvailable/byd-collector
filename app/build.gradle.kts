plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bydcollector.collector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bydcollector.collector"
        minSdk = 26
        targetSdk = 29
        versionCode = 137
        versionName = "1.3.4"
        manifestPlaceholders["collectorLabel"] = "BYD Collector"
        buildConfigField("String", "COLLECTOR_DISPLAY_NAME", "\"BYD Collector\"")
        buildConfigField("String", "COLLECTOR_DATABASE_NAME", "\"bydcollector_telemetry.db\"")
        buildConfigField("String", "UPDATE_RELEASES_API_URL", "\"https://api.github.com/repos/sunlixWhyNotAvailable/byd-collector/releases/latest\"")
        buildConfigField("String", "ACTION_PREFIX", "\"com.bydcollector.collector\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("androidx.core:core:1.13.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
}
