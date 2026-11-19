plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    id("publishing-convention")
}

android {
    namespace = "com.zomato.livetelemetry"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi()

    // Emit metadata readable by older Kotlin compilers and depend on an older stdlib, so apps
    // that are not on the latest Kotlin can still consume the library.
    coreLibrariesVersion = "2.2.0"
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

// Pure AndroidX + Compose: no other runtime dependencies.
dependencies {
    // Part of the public API: telemetryFlow / frameStageFlow are SharedFlows and
    // TrackTelemetryWindow is a @Composable.
    api(libs.kotlinx.coroutines.android)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.metrics.performance)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
}
