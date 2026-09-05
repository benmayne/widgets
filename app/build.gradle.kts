plugins {
    id("com.android.application")
}

// Release signing is driven entirely by environment variables so CI can sign without the
// keystore ever touching the repo, and local builds stay unsigned when they are absent.
// See "Releases" in the README.
val releaseKeystore = System.getenv("RELEASE_KEYSTORE_PATH")?.let(::file)?.takeIf { it.exists() }
val releaseVersionCode = System.getenv("RELEASE_VERSION_CODE")?.toIntOrNull()
val releaseVersionName = System.getenv("RELEASE_VERSION_NAME")

android {
    namespace = "dev.ben.aqiwidget"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ben.aqiwidget"
        minSdk = 31
        targetSdk = 37
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersionName ?: "1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
