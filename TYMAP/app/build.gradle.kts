import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val versionFile = rootProject.file("../version.json")
var dynamicVersionCode = 6
var dynamicVersionName = "1.0.6"

if (versionFile.exists()) {
    try {
        val parsed = JsonSlurper().parseText(versionFile.readText()) as? Map<*, *>
        val appMap = parsed?.get("app") as? Map<*, *>
        if (appMap != null) {
            val code = (appMap["versionCode"] as? Number)?.toInt()
            val name = appMap["versionName"] as? String
            if (code != null && code > 0) dynamicVersionCode = code
            if (!name.isNullOrBlank()) dynamicVersionName = name
        }
    } catch (e: Exception) {
        println("Warning: Could not parse version.json: ${e.message}")
    }
}

android {
    namespace = "com.example.tymap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.tymap"
        minSdk = 30
        targetSdk = 35
        versionCode = dynamicVersionCode
        versionName = dynamicVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        viewBinding = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)

    implementation(libs.osmdroid)
    implementation(libs.nordic.ble)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.androidx.security.crypto)
    implementation(libs.material)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.fragment.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}