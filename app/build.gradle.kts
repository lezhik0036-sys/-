import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Private FastAPI base URL for the real account + Trusted Exit client. Empty by
// default (CI and any un-overridden build) => the client is disabled/fail-closed.
// A field test supplies it with `-PmamaBackendBaseUrl=https://<host>` (HTTPS is
// enforced at runtime); it is not a secret and never enables local OTP.
val mamaBackendBaseUrl: String = (project.findProperty("mamaBackendBaseUrl") as String?)?.trim().orEmpty()

// Real-server PILOT builds must fail at build time without an explicitly
// configured HTTPS FastAPI host. Regular debug CI remains offline/fake-only.
val pilotBuildRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':').contains("Pilot", ignoreCase = true)
}
if (pilotBuildRequested) {
    val uri = runCatching { URI(mamaBackendBaseUrl) }.getOrNull()
    require(
        uri != null &&
            uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
    ) {
        "MAMA Pilot requires -PmamaBackendBaseUrl=https://real-fastapi-host (HTTPS, no credentials/path/query/fragment)."
    }
}

// Release credentials are provisioned by the owner outside the repository.
val releaseStore = providers.environmentVariable("MAMA_RELEASE_KEYSTORE")
val releaseStorePassword = providers.environmentVariable("MAMA_RELEASE_STORE_PASSWORD")
val releaseAlias = providers.environmentVariable("MAMA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("MAMA_RELEASE_KEY_PASSWORD")
val approvedReleaseOrigin = "https://api.mama-app.ru"
val verifyMamaReleaseInputs = tasks.register("verifyMamaReleaseInputs") {
    doLast {
        require(mamaBackendBaseUrl == approvedReleaseOrigin) {
            "MAMA release requires -PmamaBackendBaseUrl=$approvedReleaseOrigin."
        }
        require(releaseStore.orNull?.let { file(it).isFile } == true) {
            "MAMA_RELEASE_KEYSTORE must point to the owner's upload keystore."
        }
        require(listOf(releaseStorePassword, releaseAlias, releaseKeyPassword).all {
            !it.orNull.isNullOrBlank()
        }) {
            "MAMA release requires the upload alias and both signing passwords."
        }
    }
}

tasks.matching {
    it.name in setOf("assembleRelease", "bundleRelease", "packageRelease", "packageReleaseBundle", "signReleaseBundle")
}.configureEach {
    dependsOn(verifyMamaReleaseInputs)
}

android {
    namespace = "app.mama"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mama"
        minSdk = 26
        targetSdk = 35
        versionCode = 56
        versionName = "0.8.24-pilot-int1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("rustoreRelease") {
            storeFile = releaseStore.orNull?.let { file(it) }
            storePassword = releaseStorePassword.orNull
            keyAlias = releaseAlias.orNull
            keyPassword = releaseKeyPassword.orNull
        }
    }

    buildTypes {
        debug {
            // Pilot test credits for the internal balance: debug builds only.
            buildConfigField("boolean", "PILOT_TEST_CREDITS", "true")
            // TEST/DEBUG safety: cap every lock to 15 min, show the Trusted Exit TEST CODE,
            // run the readiness check. Never true in a release/production build.
            buildConfigField("boolean", "TEST_MODE", "true")
            buildConfigField("String", "MAMA_BACKEND_BASE_URL", "\"$mamaBackendBaseUrl\"")
        }
        release {
            signingConfig = signingConfigs.getByName("rustoreRelease")
            isMinifyEnabled = false
            buildConfigField("boolean", "PILOT_TEST_CREDITS", "false")
            buildConfigField("boolean", "TEST_MODE", "false")
            buildConfigField("String", "MAMA_BACKEND_BASE_URL", "\"$mamaBackendBaseUrl\"")
        }
        // Real-server installable pilot (debug key for internal distribution ONLY).
        // NO local OTP, no TEST CODE and no simulated credits; backend URL is
        // mandatory and validated above when assemblePilot is requested.
        create("pilot") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            buildConfigField("boolean", "PILOT_TEST_CREDITS", "false")
            buildConfigField("boolean", "TEST_MODE", "false")
            buildConfigField("String", "MAMA_BACKEND_BASE_URL", "\"$mamaBackendBaseUrl\"")
        }

    }

    testOptions {
        // Robolectric startup tests use the real manifest, theme and resources.
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
