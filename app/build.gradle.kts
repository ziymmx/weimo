plugins {
    alias(libs.plugins.android.application)
}

val releaseKeystore = rootProject.file("release.keystore")

android {
    namespace = "com.ziymmx.wx"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
            storePassword = "weimo123"
            keyAlias = "weimo"
            keyPassword = "weimo123"
        }
    }

    defaultConfig {
        applicationId = "com.ziymmx.wx"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // CI 会临时生成 release.keystore 用于签名；本地未生成时不签名。
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.dexkit)
    compileOnly(libs.libxposed.api)
}