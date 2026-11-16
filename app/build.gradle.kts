plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "sn.yeuk.tiktrad"
    compileSdk = 34
    defaultConfig {
        applicationId = "sn.yeuk.tiktrad"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }
    signingConfigs {
        create("tiktrad") {
            storeFile = file("tiktrad.keystore")
            storePassword = "tiktrad123"
            keyAlias = "tiktrad"
            keyPassword = "tiktrad123"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("tiktrad") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("tiktrad") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
}
