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

// The APK is named after the app rather than after the module, so the file that
// leaves the build is StarBase-release.apk instead of app-release.apk.
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
        versionCode = 1
        versionName = "1.0"
    }

    androidResources {
        localeFilters += listOf("zh", "en")
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
    implementation("androidx.navigation:navigation-compose:2.9.5")

    // Real Liquid Glass: the panels sample what is actually behind them.
    implementation("io.github.kyant0:backdrop:1.0.6")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Live data path: OkHttp fetches, Jsoup turns the server-rendered HTML into models.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    // Real avatars and post images now that the app is online.
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Login happens in a real WebView so the site's own captcha/PoW scripts run.
    implementation("androidx.webkit:webkit:1.11.0")

    testImplementation("junit:junit:4.13.2")
}
