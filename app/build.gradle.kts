plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val remoteTestRelayPrefill = providers.gradleProperty("pocketmind.remoteRelayUrl").orElse("").get()
require(remoteTestRelayPrefill.isEmpty() || (remoteTestRelayPrefill.length <= 200 &&
    Regex("^https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?/?$").matches(remoteTestRelayPrefill))) {
    "pocketmind.remoteRelayUrl must be an HTTPS root URL without credentials, query, or fragment"
}

android {
    namespace = "com.pocketmind"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "27.2.12479018"
    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.pocketmind"
        minSdk = 30
        targetSdk = 30
        versionCode = 3
        versionName = "0.2.0"
        buildConfigField("String", "DEFAULT_REMOTE_RELAY_URL", "\"$remoteTestRelayPrefill\"")
        ndk { abiFilters += "armeabi-v7a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_PLATFORM=android-30",
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE"
                )
                cppFlags += listOf("-std=c++17")
                targets += "pocketmind"
            }
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
    androidResources { noCompress += "gguf" }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.github.requery:sqlite-android:3.49.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
