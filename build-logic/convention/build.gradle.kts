import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "com.nexusts.app.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("nexusAndroidLibrary") {
            id = "nexusts.android.library"
            implementationClass = "com.nexusts.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("nexusAndroidCompose") {
            id = "nexusts.android.compose"
            implementationClass = "com.nexusts.buildlogic.AndroidComposeConventionPlugin"
        }
        register("nexusCargoNdk") {
            id = "nexusts.native.cargonative"
            implementationClass = "com.nexusts.buildlogic.CargoNdkConventionPlugin"
        }
    }
}
