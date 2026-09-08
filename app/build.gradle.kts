import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing is read from keystore.properties at the repo root. If that file is
// absent the release build falls back to the debug key so the project still builds.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")?.let {
    rootProject.file(it).exists()
} == true

// Use the app name for build archives; release APKs also include ABI and version.
base {
    archivesName = "StarBase"
}

android {
    namespace = "StarBase.Android.Forum"
    // The backdrop/shapes aars declare minCompileSdk=36; targetSdk stays 34 so the
    // app keeps the runtime behaviour it was tested with.
    compileSdk = 36

    defaultConfig {
        applicationId = "StarBase.Android.Forum"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "1.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        localeFilters += listOf("zh", "en")
    }

    // Cronet ships a native library per ABI, ~3.5-5.8 MB each, and a universal APK
    // carrying all four is most of the download for nothing. Per-ABI APKs plus a
    // universal one: the arm ones are what phones install, and the universal build
    // stays available for a release asset that has to work everywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    applicationVariants.all {
        if (buildType.name == "release") {
            outputs.all {
                val abi = filters.firstOrNull { it.filterType == "ABI" }?.identifier ?: "universal"
                (this as BaseVariantOutputImpl).outputFileName = "StarBase-$abi-V$versionName.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val liveDoh = providers.environmentVariable("STARBASE_LIVE_DOH").orElse("0")
    inputs.property("liveDoh", liveDoh)
    outputs.upToDateWhen { liveDoh.get() != "1" }
    outputs.doNotCacheIf("Live DoH checks depend on the current network") { liveDoh.get() == "1" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    // LifecycleResumeEffect: screens re-fetch when the app returns to the foreground.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")

    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.navigation:navigation-compose:2.9.5")

    // Real Liquid Glass: the panels sample what is actually behind them.
    implementation("io.github.kyant0:backdrop:1.0.6")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Live data path: OkHttp fetches, Jsoup turns the server-rendered HTML into models.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    // Real avatars and post images now that the app is online.
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Bundled models work on devices without Play Services and keep screenshots local.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // Cronet (Chromium network stack): QUIC/HTTP3 fallback when TCP fails. Some networks
    // RST the TCP TLS handshake on SNI (ClientHello shows linux.sb), QUIC(UDP/443) passes.
    // OkHttp has no HTTP/3, so CronetFallback retries failed requests over QUIC. Embedded
    // version bundles native libs, doesn't depend on Play Services (many ROMs ship without).
    // 143 replaces the old 4 KB-aligned native library for 16 KB page-size devices.
    implementation("org.chromium.net:cronet-embedded:143.7445.0")
    // No androidx.webkit any more: it was here for ProxyController, which existed to
    // point the login WebView at a loopback proxy. Login is a native form now, and the
    // remaining WebView (ui/SitePage.kt, for pages the app does not render itself) only
    // needs the framework android.webkit.

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("com.google.zxing:core:3.5.3")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.8")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.8")
}
