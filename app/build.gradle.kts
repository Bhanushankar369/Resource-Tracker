import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---------------------------------------------------------------------------
// .env loading
//
// A .env file at the repository root is read at BUILD time and its values are
// baked into BuildConfig. Read .env -> SECURITY.md before shipping: anything in
// here is extractable from the APK. Never put a production secret here.
// Precedence: real environment variable > .env (repo root) > local.properties
// ---------------------------------------------------------------------------
val envProperties = Properties().apply {
    val f = rootProject.file(".env")
    if (f.exists()) f.inputStream().use { load(it) }
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String, default: String = ""): String {
    val raw = System.getenv(key)
        ?: envProperties.getProperty(key)
        ?: localProperties.getProperty(key)
        ?: default
    return raw.trim().removeSurrounding("\"").removeSurrounding("'")
}

val backendBaseUrl = secret("BACKEND_BASE_URL", "http://10.0.2.2:8000/")
val appEnv = secret("APP_ENV", "dev")

android {
    namespace = "com.resourcetracker"
    // 34, not 35: AGP 8.1.0 has no knowledge of API 35 and refuses to compile
    // against it. See the SDK-34 note in gradle/libs.versions.toml.
    compileSdk = 34

    defaultConfig {
        applicationId = "com.resourcetracker"
        minSdk = 26
        // Held at 34 in step with compileSdk. targetSdk 35 would still install
        // and run, but keeping them equal avoids a 35-only behaviour (the
        // Android 15 edge-to-edge enforcement) showing up in a build whose
        // libraries are all compiled against 34.
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
        buildConfigField("String", "APP_ENV", "\"$appEnv\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            // Left off deliberately for the MVP so Play builds behave identically to
            // debug. Turn on before you scale: isMinifyEnabled = true and keep the
            // rules in proguard-rules.pro (they are already written).
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.ext.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.mlkit.text.recognition)

    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

/** Prints what the build actually picked up, without leaking values. */
tasks.register("envSummary") {
    group = "help"
    description = "Shows which .env values are visible to the Android build."
    doLast {
        println("APP_ENV              = $appEnv")
        println("BACKEND_BASE_URL     = $backendBaseUrl")
    }
}
