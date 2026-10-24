import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// MAMA Trusted Exit backend. A standalone Kotlin/Ktor service, deployed and run
// separately from the Android APK — it is never a dependency of :app and never
// ships inside the app. It owns the whole Trusted Exit code lifecycle
// (generate, deliver via SMS, count attempts, expire, lock, verify); the phone
// only holds a handle. The SMS.RU API key lives only here, read from the
// environment, never in Git or the client.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val ktor = "2.3.13"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-gson-jvm:$ktor")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor")
    implementation("com.google.code.gson:gson:2.11.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:$ktor")
}

application {
    mainClass.set("app.mama.backend.ServerKt")
}

// One-shot SMS smoke check. Run on the server where MAMA_SMSRU_API_KEY is in the
// environment (never on the command line). Target number via -Pphone=...:
//   ./gradlew :backend:smokeSms -Pphone="+7XXXXXXXXXX"
tasks.register<JavaExec>("smokeSms") {
    group = "verification"
    description = "Send ONE test SMS via the configured provider to -Pphone=... (api_id from MAMA_SMSRU_API_KEY env)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.mama.backend.SmokeKt")
    (project.findProperty("phone") as String?)?.let { args(it) }
}

tasks.test {
    useJUnitPlatform()
}
