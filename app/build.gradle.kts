plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tw.finevolume"
    compileSdk = 34

    defaultConfig {
        applicationId = "tw.finevolume"
        minSdk = 28          // DynamicsProcessing 需要 Android 9 以上
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // 固定的簽章金鑰：每次重新編譯都能直接覆蓋安裝更新，設定不會被清掉
    signingConfigs {
        create("fixed") {
            storeFile = file("finevolume.keystore")
            storePassword = "finevolume"
            keyAlias = "finevolume"
            keyPassword = "finevolume"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// 只用 Android 內建元件，不依賴其他函式庫
