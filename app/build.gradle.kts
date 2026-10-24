import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Values come from local.properties (gitignored) or the environment, never from source.
fun localProp(key: String, fallback: String): String =
    localProps.getProperty(key) ?: System.getenv(key) ?: fallback

val valhallaBaseUrl: String = localProp("valhalla.baseUrl", "http://admins-mac-mini.taile1279d.ts.net")
val valhallaPublicUrl: String = localProp("valhalla.publicUrl", "https://valhalla1.openstreetmap.de")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.biketrailplanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.biketrailplanner"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // D5: self-hosted Valhalla over the tailnet. One-line change to point elsewhere.
        buildConfigField("String", "VALHALLA_BASE_URL", "\"$valhallaBaseUrl\"")
        // D12: public fallback, only used when the user opts in on the Plan screen.
        buildConfigField("String", "VALHALLA_PUBLIC_URL", "\"$valhallaPublicUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // MapLibre 13.6.1 ships Kotlin 2.2 metadata while this project is on Kotlin 2.0.21 (the
        // same toolchain as the user's store-gps project). The metadata check is about the library's
        // own annotations, not about our source, so it is skipped rather than bumping the whole
        // Kotlin/KSP/Compose stack for one dependency. Verified by building and running on device.
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // MapLibre ships four ABIs; the phone is arm64-v8a and the emulator is x86_64, so building one
    // APK per ABI keeps each install around a fifth of the size of a universal APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

// Live probes (LivePlanProbeTest) read -Dprobe.* from the Gradle command line, so forward those
// properties to the test JVM. Harmless for normal test runs, which set none of them.
tasks.withType(Test::class.java).configureEach {
    listOf(
        "probe.base", "probe.out", "probe.perTrail", "probe.maxCandidates", "probe.miles",
        // D40/D42 probe: it has to reach the state where nothing is compliant, which needs a chosen
        // origin and an achievable-but-tight caution cap, plus its own report path.
        "probe.dCapMiles", "probe.origin", "probe.quietOut",
    )
        .forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.maplibre.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // D27: DeepSeek (OpenAI-compatible) single-shot client + its transport engine, and the
    // encrypted preferences the rider's key is kept in. Nothing else is added for the AI seam;
    // docs/research/deepseek-android.md section 1 gives the exact coordinates.
    implementation(libs.aallam.openai)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.security.crypto)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// D63: export the Room schema for every version, so the next version bump can ship a real migration
// instead of the destructive fallback that erased a rider's recorded rides.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
