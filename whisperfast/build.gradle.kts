// Same whisper.cpp JNI sources as :app, compiled for ARMv8.2 with dot-product and FP16 instructions
// (most phones from ~2018 on). The app loads this library only when the CPU reports both features.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.clipforge.whisperfast"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCLIPFORGE_LIB=clipforge_whisper_fast",
                    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../app/src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
}
