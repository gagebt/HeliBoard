import com.android.build.api.variant.ApplicationVariant
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("plugin.serialization") version "2.4.0"
    kotlin("plugin.compose") version "2.4.0"
}

android {
    compileSdk = 37

    defaultConfig {
        applicationId = "helium314.keyboard.parakeetfuto"
        minSdk = 26
        targetSdk = 37
        versionCode = 4112
        versionName = "4.1-pf12"
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }

    buildTypes {
        release {
            // Native NoTune callbacks and the pinned FUTO AAR use JNI-visible class names.
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        create("nouserlib") { // same as release, but does not allow the user to provide a library
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        debug {
            // "normal" debug has minify for smaller APK to fit the GitHub 25 MB limit when zipped
            // and for better performance in case users want to install a debug APK
            isMinifyEnabled = true
            isJniDebuggable = false
            applicationIdSuffix = ".debug"
        }
        create("runTests") { // build variant for running tests on CI that skips tests known to fail
            isMinifyEnabled = false
            isJniDebuggable = false
        }
        create("debugNoMinify") { // for faster builds in IDE
            isDebuggable = true
            isMinifyEnabled = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
        }

        androidComponents.onVariants { variant: ApplicationVariant ->
            if (variant.buildType == "debug") {
                // got a little too big for GitHub after some dependency upgrades, so we remove the largest dictionary
                variant.androidResources.ignoreAssetsPatterns = listOf("main_ro.dict")
                variant.proguardFiles = emptyList()
                //noinspection ProguardAndroidTxtUsage we intentionally use the "normal" file here
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/dontoptimize.pro"))
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/proguard-rules.pro"))
            }
            variant.outputs.forEach { output ->
                if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                    output.outputFileName = "HeliBoard_${defaultConfig.versionName}-${variant.buildType}.apk"
                }
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
    ndkVersion = "28.0.13004108"

    packaging {
        jniLibs {
            // shrinks APK by 3 MB, zipped size unchanged
            useLegacyPackaging = true
        }
    }

    androidResources {
        noCompress += listOf("pte", "gguf", "combined")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // see https://github.com/HeliBorg/HeliBoard/issues/477
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "helium314.keyboard.latin"
    lint {
        abortOnError = true
    }
}

dependencies {
    implementation(files("libs/futo-swipe-release.aar"))

    // androidx
    implementation("androidx.core:core-ktx:1.17.0") // 1.18.0 requires minSdk 23
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2025.11.01")) // newer requires minSdk 23
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    "debugNoMinifyImplementation"("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("sh.calvin.reorderable:reorderable:3.1.0") // for easier re-ordering
    implementation("com.github.skydoves:colorpicker-compose:1.1.3") // for user-defined colors, newer requires minSdk 23

    // test
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:runner:1.7.0")
    testImplementation("androidx.test:core:1.7.0")
}

val parakeetModel = file("src/main/assets/builtin-model/parakeet-tdt-0.6b-v3-Q4_K_M.gguf")
val parakeetSha256 = "b68557be1e3c40207fd7c4bd9d63f1d3316b963f15325bfb0cc16a8bb0ffd181"

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

val prepareParakeetModel by tasks.registering {
    description = "Download and verify the pinned offline Parakeet model"
    notCompatibleWithConfigurationCache("Downloads and verifies a large external model asset")
    outputs.file(parakeetModel)
    outputs.upToDateWhen { parakeetModel.isFile && sha256(parakeetModel) == parakeetSha256 }
    doLast {
        parakeetModel.parentFile.mkdirs()
        val pending = File(parakeetModel.path + ".download")
        URI("https://huggingface.co/handy-computer/parakeet-tdt-0.6b-v3-gguf/resolve/main/${parakeetModel.name}?download=true")
            .toURL().openStream().use { input -> pending.outputStream().use(input::copyTo) }
        check(sha256(pending) == parakeetSha256) { "Parakeet model checksum mismatch" }
        Files.move(pending.toPath(), parakeetModel.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

tasks.named("preBuild") { dependsOn(prepareParakeetModel) }
