import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional release signing: drop a `keystore.properties` in the repo root with
//   storeFile=<path-to-keystore>
//   storePassword=...
//   keyAlias=rubec
//   keyPassword=...
val keystoreProperties = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) load(propsFile.inputStream())
}
val hasReleaseKeystore = keystoreProperties.containsKey("storeFile")

android {
    namespace = "dev.rubec.otoscope"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.rubec.otoscope"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "0.7.0"

        vectorDrawables { useSupportLibrary = true }
    }

    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Make the Unit tests step self-evident in CI logs: without this the task
// runs but prints nothing, so a passing run and a "no test sources" run
// look identical. Emitting one line per test result both proves the tests
// ran and pinpoints the failing test without downloading the HTML report.
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)

    // Unit tests run on the local JVM (no emulator, no Android runtime), which
    // is enough for the pure-Kotlin protocol parsers, the socket-driven fake
    // cameras, and the capture geometry / naming / encoder-timing rules. Any
    // test that needs real Android APIs would go in `androidTest/` instead, but
    // we're not there yet.
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}
