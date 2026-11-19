import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Baseline-profile generator and frame-timing macrobenchmarks for :app. Runs on a connected
// device or emulator (API 28+, profile capture needs 33+ without root):
//   ./gradlew :app:generateReleaseBaselineProfile          writes app/src/release/generated/baselineProfiles
//   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest   frame timings (benchmarkRelease)
// Built-in Kotlin (AGP 9): no org.jetbrains.kotlin.android plugin here.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.laksh.coffee.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // benchmarkRelease and nonMinifiedRelease both install as com.laksh.coffee.bench, so a run
        // never touches the owner's real app or its data.
        testInstrumentationRunnerArguments["targetAppId"] = "com.laksh.coffee.bench"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

baselineProfile {
    // The owner's emulator or a phone over adb; no managed device is downloaded.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
