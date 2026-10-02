plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// Same-version patches still require an exact client/runtime source pair.
val runtimeDigest = java.security.MessageDigest.getInstance("SHA-256")
fileTree(rootDir) {
    include("app/src/main/**", "collector-ui/src/main/**", "app/build.gradle.kts", "collector-ui/build.gradle.kts")
}.files.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }.forEach {
    runtimeDigest.update(it.relativeTo(rootDir).invariantSeparatorsPath.toByteArray())
    runtimeDigest.update(it.readBytes())
}
extra["runtimeRevision"] = runtimeDigest.digest().joinToString("") { "%02x".format(it) }
