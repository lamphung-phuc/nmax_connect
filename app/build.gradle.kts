import java.util.Properties

/** Secrets come from the gitignored local.properties (or env vars on CI). See docs/setup-keys.md. */
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun secret(name: String): String = localProperties.getProperty(name) ?: System.getenv(name) ?: ""

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.lampp.nmaxconnect"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lampp.nmaxconnect"
        minSdk = 26
        targetSdk = 37
        versionCode = 25
        versionName = "1.6.14"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "VIETMAP_PROXY_URL", "\"${secret("VIETMAP_PROXY_URL")}\"")
        buildConfigField("String", "VIETMAP_PROXY_TOKEN", "\"${secret("VIETMAP_PROXY_TOKEN")}\"")
    }

    buildTypes {
        release {
            // shortcut: signed with the debug key so 1.0.0 installs over dev builds on one phone;
            // switch to a real release keystore before any public release (a key change forces a reinstall).
            signingConfig = signingConfigs.getByName("debug")
            // R8 code + resource shrinking with the default Android keep rules; app rules go in src/main/keepRules/*.keep.
            // 1.0.0 crashed with a partial packageScope setup (IllegalAccessError in kotlin.sequences), so keep it whole.
            // Keep app/build/outputs/mapping/release/mapping.txt per release to read crash stack traces.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.compose.material.icons.extended)
    // VietMap's JitPack POM declares no dependencies; these are what its classes reference (Timber is bundled in the AAR).
    implementation(libs.vietmap.maps.sdk)
    implementation(libs.vietmap.services.geojson)
    implementation(libs.vietmap.services.turf) // LatLng.distanceTo → location puck (crashed without it)
    implementation(libs.okhttp)
    implementation(libs.gson)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml") // pre-existing findings; regenerate with ./gradlew detektBaseline
}
ktlint {
    version.set(libs.versions.ktlint.get())
    android.set(true)
    verbose.set(true) // print rule ids, so a finding can be looked up or disabled in .editorconfig
    baseline.set(file("ktlint-baseline.xml")) // pre-existing findings; regenerate with ./gradlew ktlintGenerateBaseline
}
