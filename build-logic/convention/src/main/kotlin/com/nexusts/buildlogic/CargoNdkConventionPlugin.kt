package com.nexusts.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations
import javax.inject.Inject

abstract class CargoNdkBuildTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {

    @get:Internal
    abstract val rustWorkspaceDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustSourceInputs: ConfigurableFileCollection

    @get:Input
    abstract val cratePackageName: Property<String>

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val releaseProfile: Property<Boolean>

    @get:Input
    abstract val minSdk: Property<Int>

    @get:OutputDirectory
    abstract val jniLibsOutputDir: DirectoryProperty

    @TaskAction
    fun compileRustForNdk() {
        val outputRoot = jniLibsOutputDir.get().asFile
        outputRoot.mkdirs()

        if (System.getenv("NEXUSTS_PREBUILT_RUST_JNILIBS") == "true") {
            logger.lifecycle("NexusTS: Using prebuilt Rust NDK jniLibs from CI artifact (NEXUSTS_PREBUILT_RUST_JNILIBS=true).")
            return
        }

        val profileFlag = if (releaseProfile.get()) listOf("--release") else emptyList()

        abis.get().forEach { abi ->
            val cmd = buildList {
                add("cargo")
                add("ndk")
                add("--platform")
                add(minSdk.get().toString())
                add("--target")
                add(abi)
                add("-o")
                add(outputRoot.absolutePath)
                add("build")
                add("-p")
                add(cratePackageName.get())
                addAll(profileFlag)
            }

            execOperations.exec {
                workingDir = rustWorkspaceDir.get().asFile
                // Enforce 16KB ELF segment alignment required by Android 15/16+ & Googlebook OS
                environment(
                    "CARGO_ENCODED_RUSTFLAGS",
                    "-C\u001flink-arg=-Wl,-z,max-page-size=16384\u001f-C\u001flink-arg=-Wl,-z,common-page-size=16384"
                )
                commandLine = cmd
            }
        }
    }
}

class CargoNdkConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val abisList = providers.gradleProperty("nexusts.ndk.abis")
                .map { raw -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() } }

            val jniGeneratedDir = layout.buildDirectory.dir("generated/cargoJniLibs")
            val rustRoot = rootProject.layout.projectDirectory.dir("native/rust")

            val cargoTask = tasks.register<CargoNdkBuildTask>("buildCargoNdk") {
                group = "native"
                description = "Builds Rust protocol and cryptography workspace crates via cargo-ndk with 16KB page alignment."
                rustWorkspaceDir.set(rustRoot)
                rustSourceInputs.from(
                    rootProject.fileTree("native/rust") {
                        exclude("target/**")
                    }
                )
                cratePackageName.set("nexusts-jni")
                abis.set(abisList)
                releaseProfile.set(true)
                minSdk.set(providers.gradleProperty("nexusts.minSdk").map { it.toInt() })
                jniLibsOutputDir.set(jniGeneratedDir)
            }

            extensions.configure<LibraryExtension> {
                ndkVersion = "28.0.13004108"
                defaultConfig {
                    ndk {
                        abiFilters.addAll(abisList.get())
                    }
                }
                sourceSets.getByName("main") {
                    jniLibs.srcDir(cargoTask.flatMap { it.jniLibsOutputDir })
                }
            }
        }
    }
}
