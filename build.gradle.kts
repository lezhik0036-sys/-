// Plugins live on the root classpath so the Kotlin plugin and the Android
// plugin share one classloader. AGP is only pulled in when :app is included.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        if (findProject(":app") != null) {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}
