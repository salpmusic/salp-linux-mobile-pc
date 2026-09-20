plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.salp.mobilepc"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.salp.mobilepc"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

// GeckoView 154 declares compileSdk 37 / AGP 9.1; platform 37 is not yet
// available on the CI SDK channel, so keep toolchain on 35 and skip AAR checks.
tasks.configureEach {
    if (name.contains("checkAarMetadata", ignoreCase = true)) {
        enabled = false
    }
}

configurations.all {
    resolutionStrategy {
        force("androidx.core:core:1.15.0")
        force("androidx.core:core-ktx:1.15.0")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.mozilla.geckoview:geckoview-omni:154.0.20260814215756")
}
