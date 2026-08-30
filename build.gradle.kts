plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    // Kotlin 2.x ships the Compose compiler inside the Kotlin toolchain, so the old
    // composeOptions { kotlinCompilerExtensionVersion } pin becomes this plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
}
