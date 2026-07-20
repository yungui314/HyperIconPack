plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = providers.environmentVariable("HYPERICONPACK_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("HYPERICONPACK_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("HYPERICONPACK_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("HYPERICONPACK_KEY_PASSWORD").orNull
val releaseSigningReady = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "io.github.cl0ura.hypericonpack"
    // HyperOS 3 devices used by this module build against Android API 37.
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "io.github.cl0ura.hypericonpack"
        minSdk = 26
        targetSdk = 36
        versionCode = 43
        versionName = "0.9.32"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    if (releaseSigningReady) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    // Kept only for the legacy, no-longer-routed settings screen. Active UI
    // surfaces use Miuix components exclusively.
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.3")

    // Never package the framework API in the APK: LSPosed/Xposed supplies it at runtime.
    compileOnly("de.robv.android.xposed:api:82")
}
