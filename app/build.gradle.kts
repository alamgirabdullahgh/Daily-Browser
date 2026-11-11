plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rahcare.termuxbrowser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rahcare.termuxbrowser"
        minSdk = 24
        targetSdk = 34
        versionCode = (findProperty("vc") as String?)?.toIntOrNull() ?: 1
        versionName = "0.1"
    }

    // স্থায়ী সাইনিং চাবি: ফাইলটা CI-তে GitHub Secrets থেকে বসে, রিপোতে থাকে না। তাই প্রতিটি বিল্ড একই চাবিতে সাইন হয়
    // এবং আগেরটার ওপর আপডেট হিসেবে বসে
    signingConfigs {
        create("fixed") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("KS_PASSWORD")
            keyAlias = "termuxbrowser"
            keyPassword = System.getenv("KS_PASSWORD")
            storeType = "pkcs12"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // আলাদা লগইন-প্রোফাইল (Multi-Profile API) — প্রতিটি ওয়ার্কস্পেসের কুকি/সেশন আলাদা রাখে
    implementation("androidx.webkit:webkit:1.16.0")
    // টেনে রিফ্রেশ (পেজের একদম ওপরে নামিয়ে টানলে রিলোড)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // কিবোর্ডের ক্লিপবোর্ড থেকে ছবি বসানো (InputConnectionCompat)
    implementation("androidx.core:core:1.13.1")
    // অফলাইন AI মডেল (ফোনের ভেতরে চলে; Gemma .task ফাইল দিলে)
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
}
