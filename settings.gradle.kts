dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mama"

// Pure Kotlin domain logic: builds and tests anywhere with a JDK.
include(":core")

// The Android app needs an Android SDK. Without one the project still
// configures, so `./gradlew :core:test` keeps working on a bare JDK box.
val localProps = file("local.properties")
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    (localProps.exists() && localProps.readText().contains("sdk.dir"))
if (hasAndroidSdk) {
    include(":app")
} else {
    logger.warn("MAMA: Android SDK not found, skipping :app (only :core is built)")
}
