import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.autojimaku"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.autojimaku"
        // 他アプリの再生音声の取得 (AudioPlaybackCapture) は Android 10 以降
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "2.4"

        ndk {
            // スマホ向け。エミュレーター (PC) で試す場合は "x86_64" を追加する
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    packaging {
        jniLibs {
            // 音声認識のネイティブライブラリを圧縮して APK を小さくする
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        // 個人利用向けの固定の署名鍵。毎回同じ鍵で署名するので、作り直した APK を上書きインストールできる
        create("personal") {
            storeFile = file("autojimaku-release.jks")
            storePassword = "autojimaku-local"
            keyAlias = "autojimaku"
            keyPassword = "autojimaku-local"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // 個人用ビルドなので、リリースビルド時の lint でビルドを止めない
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 端末内・軽量の音声認識 (Vosk)
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    // 端末内・多言語の音声認識 (sherpa-onnx: Whisper・タイ語/ベトナム語専用モデル・Silero VAD)
    implementation("com.k2fsa.sherpa.onnx:sherpa-onnx:1.13.8@aar")
    // 端末内の翻訳と言語判別 (Google ML Kit)
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
}
