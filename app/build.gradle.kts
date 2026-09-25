// AGP 9's built-in Kotlin is 2.2.0, too old to read pdfium's 2.4.x metadata, so it is disabled in
// gradle.properties (android.builtInKotlin=false) and the real Kotlin plugin is applied here.
// Imported explicitly: inside a build script, a bare `java.util.…` resolves against Gradle's own
// `java` extension instead of the package.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Self-signed key for personal sideloading, read from keystore.properties so the passwords stay
// out of the build script. Release falls back to unsigned if the file is missing.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "top.teamaos.pdfreader"
    // 37 because io.legere:pdfiumandroid-api:2.0.3 refuses to be consumed by anything older.
    // targetSdk deliberately stays a release behind so runtime behaviour is the well-tested one.
    compileSdk = 37

    defaultConfig {
        applicationId = "top.teamaos.pdfreader"
        minSdk = 24
        targetSdk = 36
        versionCode = 10
        versionName = "2.5"

        // Where "Install latest version" looks for new releases: owner/repository on GitHub.
        val updateRepo = (project.findProperty("updateRepo") as String?) ?: "mdzarifuddin/rapid-office-pro"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")

        ndk {
            // Keep the emulator (x86_64) and every real phone working, drop legacy 32-bit x86.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        // The settings screen shows the version name.
        buildConfig = true
    }

    lint {
        // The lint tool pulls a dependency set this machine's network blocks, and it gates every
        // release build. Nothing here ships to a store, so skip it.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.collection.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pdfiumandroid)
    implementation(libs.pdfiumandroid.core)
}
