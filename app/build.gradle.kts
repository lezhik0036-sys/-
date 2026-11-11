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

android {
    namespace = "app.mama"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mama"
        minSdk = 26
        targetSdk = 35
        versionCode = 54
        versionName = "0.8.22-integration2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
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
            isMinifyEnabled = false
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
