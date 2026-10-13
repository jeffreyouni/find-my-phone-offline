plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gujeff.findmyphone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gujeff.findmyphone"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // Vosk ships native libs for these ABIs; the S24 Ultra is arm64-v8a.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // sideload-friendly; replace for distribution
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // Model zips are already compressed; storing them avoids a second pass and keeps APK install fast.
    androidResources { noCompress += "zip" }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

// Fail early with a helpful message if the speech models were not downloaded.
tasks.register("checkModels") {
    doLast {
        val d = file("src/main/assets/models")
        if (!file("$d/model-en.zip").exists() || !file("$d/model-cn.zip").exists()) {
            throw GradleException("Speech models missing. Run ./scripts/fetch_models.sh first.")
        }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn("checkModels") }

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
