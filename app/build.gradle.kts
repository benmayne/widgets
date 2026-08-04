plugins {
    id("com.android.application")
}

android {
    namespace = "dev.ben.aqiwidget"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ben.aqiwidget"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Test-only. No runtime dependencies by design.
    // org.json is required because android.jar's version is a stub in unit tests;
    // the real jar takes classpath precedence so parsing tests exercise real behavior.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
