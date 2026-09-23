import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * SDK levels are declared exactly once, here. Nothing else in the project
 * hardcodes an SDK number.
 */
object Sdk {
    const val MIN = 26
    const val COMPILE = 36
    const val TARGET = 36
}

/**
 * Reads an optional, developer supplied value. Resolution order:
 *   1. Gradle property (-PSUPABASE_URL=...)
 *   2. local.properties key (never committed to git)
 *   3. Environment variable
 *   4. Empty string, which makes the app run in offline-only mode.
 *
 * Only client-safe values belong here. The Supabase `service_role` key must
 * never be added to this file, to local.properties, or to the APK.
 */
fun secret(key: String): String {
    (project.findProperty(key) as? String)?.takeIf { it.isNotBlank() }?.let { return it }
    val localProps = rootProject.file("local.properties")
    if (localProps.exists()) {
        val props = Properties().apply { localProps.inputStream().use { load(it) } }
        (props.getProperty(key) ?: props.getProperty(key.lowercase()))
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }
    return System.getenv(key) ?: System.getenv(key.lowercase()) ?: ""
}

android {
    namespace = "com.memorymap"
    compileSdk = Sdk.COMPILE

    defaultConfig {
        applicationId = "com.memorymap"
        minSdk = Sdk.MIN
        targetSdk = Sdk.TARGET
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Arabic is the default UI language, English is optional (values-en).
        resourceConfigurations += listOf("ar", "en")

        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "MAP_TILE_SERVER", "\"https://tile.openstreetmap.org/{z}/{x}/{y}.png\"")
        buildConfigField("String", "MAP_ATTRIBUTION", "\"© OpenStreetMap contributors\"")
    }

    signingConfigs {
        // Release signing is intentionally NOT configured with committed
        // credentials. See docs/RELEASE.md for keystore creation and usage:
        //   ./gradlew assembleRelease -PMEMORYMAP_KEYSTORE=... -PMEMORYMAP_KEY_ALIAS=...
        create("releaseFromProperties") {
            val storePath = (project.findProperty("MEMORYMAP_KEYSTORE") as? String)
                ?.takeIf { it.isNotBlank() }
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = project.findProperty("MEMORYMAP_KEYSTORE_PASSWORD") as? String
                keyAlias = project.findProperty("MEMORYMAP_KEY_ALIAS") as? String
                keyPassword = project.findProperty("MEMORYMAP_KEY_PASSWORD") as? String
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseSigning = signingConfigs.getByName("releaseFromProperties")
            signingConfig = if (releaseSigning.storeFile != null) releaseSigning else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time is fully supported from API 26, which is our minSdk.
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        // Lint is a real gate: a new error must be fixed, not silenced here.
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

ksp {
    // Room schema history is exported and committed so migrations stay reviewable.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.documentfile)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Kotlin
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Supabase (platform BOM keeps the supabase artifacts aligned)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)
    implementation(libs.ktor.client.okhttp)

    // Media
    implementation(libs.coil.compose)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
