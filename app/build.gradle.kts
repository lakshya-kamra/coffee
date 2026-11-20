import java.io.StringReader
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.androidx.baselineprofile)
}

// Release signing lives OUTSIDE the repo. Point Gradle at a keystore.properties with
//   -Pcoffee.signing=C:/Users/<you>/.coffee-signing/keystore.properties  (or that line in ~/.gradle/gradle.properties)
//   or the COFFEE_SIGNING environment variable (CI).
// Without either, ~/.coffee-signing/keystore.properties is used when it exists.
// It holds storeFile, storePassword, keyAlias, keyPassword (storeFile may be relative to it).
// Without it assembleRelease still builds, unsigned, so other machines and CI never break.
val signingPropsFile: File? = (
    providers.gradleProperty("coffee.signing").orNull
        ?: providers.environmentVariable("COFFEE_SIGNING").orNull
        ?: providers.systemProperty("user.home").orNull?.let { "$it/.coffee-signing/keystore.properties" }
    )?.let { file(it) }?.takeIf { it.isFile }
val signingProps: Properties? = signingPropsFile?.let { f ->
    Properties().apply {
        // fileContents keeps the configuration cache aware of the file.
        load(StringReader(providers.fileContents(layout.projectDirectory.file(f.absolutePath)).asText.get()))
    }
}

android {
    namespace = "com.laksh.coffee"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.laksh.coffee"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"
        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        // One copy of the catalogs and recipes for both apps: shared/ ships as assets, and the
        // JVM tests read the same files from the classpath.
        getByName("main") { assets.srcDir(rootProject.file("shared")) }
        getByName("test") { resources.srcDir(rootProject.file("shared")) }
        // The instrumented backup tests read the same fixtures the JVM and web tests do.
        getByName("androidTest") { assets.srcDir(rootProject.file("shared/fixtures")) }
        // The Room plugin adds app/schemas to the androidTest assets for MigrationTestHelper.
    }

    // Schema docs and the backup test fixtures stay out of the APK.
    androidResources { ignoreAssetsPatterns += listOf("!*.md", "!<dir>fixtures") }

    signingConfigs {
        if (signingProps != null) create("release") {
            val store = signingProps.getProperty("storeFile").orEmpty()
            storeFile = File(store).let { if (it.isAbsolute) it else File(signingPropsFile!!.parentFile, store) }
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingProps != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
        // The baseline-profile plugin's build types, declared here so they can have their own id and
        // the debug key: a benchmark run never touches the real install or its data. Declaring one
        // stops the plugin copying release into it, so initWith does that. benchmarkRelease is
        // minified like release (that is what gets timed); the profile is collected unminified.
        create("benchmarkRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".bench"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        create("nonMinifiedRelease") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".bench"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // BuildConfig.VERSION_NAME for backups and the User-Agent.
    buildFeatures { compose = true; buildConfig = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    lint {
        // AAPT2 will not resolve an adaptive launcher icon from a bare mipmap-anydpi folder,
        // so the v26 qualifier has to stay even though minSdk is 26. Verified by removing it
        // and watching the build fail.
        disable += "ObsoleteSdkInt"
        // Test sources are not linted. Skipping them also stops the three lintAnalyze tasks
        // racing each other for the same migrated-jars cache, which fails on Windows.
        ignoreTestSources = true
        checkTestSources = false
    }
}

// DebugTools lives only in src/debug (adb seeding) and src/release (no-op). When the DSL is
// finalised the baseline-profile plugin copies release's source dirs into its two build types,
// which would give them the no-op and a profile recorded on an empty app. This runs after the
// plugin's copy and swaps in the debug twin, so the generator and the benchmarks can seed
// realistic data (--ez seed true). Both sets: built-in Kotlin compiles from kotlin.directories.
androidComponents {
    finalizeDsl { ext ->
        listOf("benchmarkRelease", "nonMinifiedRelease").forEach { name ->
            ext.sourceSets.getByName(name) {
                listOf(java, kotlin).forEach { set ->
                    set.directories.remove("src/release/java")
                    set.directories.add("src/debug/java")
                }
            }
        }
    }
}

baselineProfile {
    // Generated on a device and committed (src/release/generated/baselineProfiles), so release
    // builds on machines and CI without a device still ship it.
    saveInSrc = true
    automaticGenerationDuringBuild = false
    // The startup profile also orders the DEX so start-up code is read first (needs R8).
    dexLayoutOptimization = true
}

composeCompiler {
    // Room entities, settings and domain models are immutable data classes; see the file.
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

// Exported schemas are what MigrationTest builds old databases from. Commit every version.
room { schemaDirectory("$projectDir/schemas") }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.animation.graphics)
    implementation(libs.androidx.material3.adaptive)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.exifinterface)

    // Writes the baseline profile on first launch for installs outside Play (GitHub APKs).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
