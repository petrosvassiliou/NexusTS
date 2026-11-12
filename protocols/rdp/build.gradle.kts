plugins {
    id("nexusts.android.library")
    id("nexusts.android.compose")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.nexusts.app.protocols.rdp"
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        ndk {
            abiFilters += providers.gradleProperty("nexusts.ndk.abis")
                .get()
                .split(",")
                .map { it.trim() }
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++20",
                    "-Wall",
                    "-Wextra",
                    "-fexceptions",
                    "-frtti",
                    "-O3"
                )
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DNEXUSTS_ENABLE_FREERDP3=ON",
                    "-DNEXUSTS_ENABLE_H264_MEDIACODEC=ON"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = rootProject.file("native/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.vault)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.window)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
