import de.gematik.zeta.sdk.buildlogic.isJvmEnabled
import de.gematik.zeta.sdk.buildlogic.isAndroidEnabled
import de.gematik.zeta.sdk.buildlogic.isIOSEnabled
import de.gematik.zeta.sdk.buildlogic.isMacOSEnabled
import de.gematik.zeta.sdk.buildlogic.setupBuildLogic
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("de.gematik.zeta.sdk.build-logic.app")
    id("de.gematik.zeta.sdk.build-logic.compose")
    id("de.gematik.zeta.sdk.build-logic.kmp")
    id("de.gematik.zeta.sdk.build-logic.xcframework")
    id("co.touchlab.skie")
    kotlin("plugin.serialization")
}

setupBuildLogic {
    kotlin {
        sourceSets.commonMain.dependencies {
            implementation(project(":zeta-sdk"))
            implementation(project(":common"))
            api(libs.ktor.client.logging)
            api(libs.ktor.kotlinx.serialization.json)
            api(libs.coroutines.core)
            api(compose.runtime)
            api(compose.material3)
            api(libs.androidx.lifecycle.runtime.compose)
            api(libs.androidx.lifecycle.viewmodel.compose)
            api(libs.reactivestate.compose)
            api(libs.okio)
            api(project(":zeta-sdk"))
        }

        if (project.isJvmEnabled) {
            sourceSets.jvmMain.dependencies {
                api(libs.coroutines.swing)
                api(compose.desktop.currentOs)
            }
        }

        if (project.isIOSEnabled) {
            sourceSets.iosMain.dependencies {
                api(libs.ktor.client.darwin)
            }
        }

        if (project.isAndroidEnabled) {
            sourceSets.androidMain.dependencies {
                api(libs.ktor.client.android)
                api(libs.androidx.core.ktx)
                api(libs.androidx.activity)
                api(libs.androidx.appcompat)
                api(libs.androidx.activity.compose)
                api(compose.material3)
            }
        }

        if (project.isJvmEnabled) {
            sourceSets.jvmTest.dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
                implementation(libs.ktor.client.mock)
                implementation(libs.mockk)
                implementation(libs.reactivestate.core.test)
            }
        }

        if (project.isMacOSEnabled) {
            macosArm64 {
                binaries {
                    executable {
                        entryPoint = "de.gematik.zeta.client.main"
                        baseName = "zeta-attestation-service"
                        linkerOpts += listOf("-framework", "Security", "-framework", "CoreFoundation")
                    }
                }
            }
        }
    }

    if (project.isJvmEnabled) {
        compose.desktop {
            application {
                mainClass = "de.gematik.zeta.client.ui.ZetaClientApp"

                nativeDistributions {
                    packageName = "Zero Sample"
                    packageVersion = "1.0.0"

                    targetFormats(
                        TargetFormat.Dmg,
                        TargetFormat.Exe,
                        TargetFormat.Deb,
                    )
                }
            }
        }
    }
}
