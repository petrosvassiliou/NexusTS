import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.nexusts.app"
    compileSdk = providers.gradleProperty("nexusts.compileSdk").get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.nexusts.app"
        minSdk = providers.gradleProperty("nexusts.minSdk").get().toInt()
        targetSdk = providers.gradleProperty("nexusts.targetSdk").get().toInt()
        versionCode = 10907
        versionName = "0.9.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Restrict resource packaging strictly to our 6 supported enterprise locales
        resourceConfigurations += listOf("en", "de", "it", "es", "el", "fr")

        val resolvedWebClientId = (
            System.getenv("NEXUSTS_GOOGLE_WEB_CLIENT_ID")?.takeIf { it.isNotBlank() }
                ?: providers.gradleProperty("nexusts.google.webClientId").orNull.orEmpty()
            ).replace("\"", "")
        buildConfigField("String", "GOOGLE_OAUTH_WEB_CLIENT_ID", "\"$resolvedWebClientId\"")

        ndk {
            abiFilters += providers.gradleProperty("nexusts.ndk.abis")
                .get()
                .split(",")
                .map { it.trim() }
        }
    }

    val resolvedKeystorePath = System.getenv("NEXUSTS_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty("nexusts.signing.storeFile").orNull?.takeIf { it.isNotBlank() }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
        create("release") {
            // Production signing key injected from CI/Enterprise keystore env vars or Gradle properties
            if (!resolvedKeystorePath.isNullOrBlank()) {
                storeFile = file(resolvedKeystorePath)
                storePassword = System.getenv("NEXUSTS_KEYSTORE_PASSWORD")
                    ?: providers.gradleProperty("nexusts.signing.storePassword").orNull
                keyAlias = System.getenv("NEXUSTS_KEY_ALIAS")
                    ?: providers.gradleProperty("nexusts.signing.keyAlias").orNull
                keyPassword = System.getenv("NEXUSTS_KEY_PASSWORD")
                    ?: providers.gradleProperty("nexusts.signing.keyPassword").orNull
            }
            // Enforce APK Signature Scheme v3/v4 for Play Protect & Enterprise PackageInstaller compliance
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val hasReleaseKey = !resolvedKeystorePath.isNullOrBlank()
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    bundle {
        language {
            // Keep all 6 languages bundled inside base APK so offline Per-App Language switching is instantaneous
            enableSplit = false
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.jvmArgs(
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED"
                )
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += "**/libnexusts_jni.so"
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES,INDEX.LIST}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "/META-INF/*.SF",
                "/META-INF/*.DSA",
                "/META-INF/*.RSA"
            )
        }
    }
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.vault)
    implementation(projects.core.gdriveSync)
    implementation(projects.protocols.rdp)
    implementation(projects.protocols.ssh)
    implementation(projects.protocols.vnc)
    implementation(projects.feature.workspace)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.window)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Play Protect Compliant In-App Updates API
    implementation(libs.play.app.update)
    implementation(libs.play.app.update.ktx)
    implementation(libs.kotlinx.coroutines.play.services)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

