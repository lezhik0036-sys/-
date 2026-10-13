import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// Shared MAMA business logic (series, FLEX, Restart, «Цена слова», wallet,
// 30-day wait, time rules, Trusted Exit). One code base for both apps:
// the Android app uses the JVM target, the iPhone app the MamaCore framework.
// No platform protection here: that stays native in each app.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm {
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }

    // iOS targets build only on macOS (Xcode); elsewhere Gradle skips them.
    val xcf = XCFramework("MamaCore")
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "MamaCore"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

// CI passes an available simulator: ./gradlew :core:iosSimulatorArm64Test -Pmama.simulator="iPhone 15"
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("mama.simulator").orNull?.let { device.set(it) }
}
