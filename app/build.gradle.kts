import org.gradle.api.tasks.Exec
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing credentials live in a git-ignored keystore.properties so the
// keystore and its passwords never enter version control (dev standards). When
// the file is absent — a fresh clone, CI, or the F-Droid build — the release
// build simply stays unsigned instead of failing.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "dev.norsehorse.vaultpony"
    compileSdk = 35
    // Pinned NDK: r27 has native 16 KB page-size support (see packaging
    // below) and is reproducible-build friendly. Install exactly this version
    // via the SDK Manager. cargoBuild passes this same NDK to cargo-ndk.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.norsehorse.vaultpony"
        // minSdk 26: openProxyFileDescriptor (planning doc §8).
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // armeabi-v7a stays in: this audience runs old hardware (doc §8).
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 verification on hardware is a formal phase gate before any
            // tester build (doc §8 — the provider parcelable incident).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed only when keystore.properties is present; otherwise the
            // release APK is emitted unsigned (see note at top of file).
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            // 16 KB page alignment is enforced at link time via RUSTFLAGS in
            // the cargoBuild task; keep uncompressed so alignment survives.
            useLegacyPackaging = false
        }
    }
}

// -- Rust core -------------------------------------------------------------
// Requires: rustup targets aarch64-linux-android, x86_64-linux-android,
// armv7-linux-androideabi + cargo-ndk. See README.md.
//
// The core lives in its own repo (VaultPonyCore). Resolve a local checkout in
// the same order RelayPony resolves AgePony: an explicit VAULTPONY_CORE env
// var, then a sibling checkout at ../VaultPonyCore (local development picks up
// edits immediately), then the git submodule at ./VaultPonyCore (fresh clone,
// CI, F-Droid). Clone with --recursive, or run
// git submodule update --init --recursive.
val vaultPonyCore: File = run {
    val env = System.getenv("VAULTPONY_CORE")?.let { file(it) }
    val sibling = rootProject.projectDir.parentFile.resolve("VaultPonyCore")
    val submodule = rootProject.projectDir.resolve("VaultPonyCore")
    when {
        env != null && env.resolve("Cargo.toml").exists() -> env
        sibling.resolve("Cargo.toml").exists() -> sibling
        submodule.resolve("Cargo.toml").exists() -> submodule
        else -> error(
            "VaultPonyCore not found. Set VAULTPONY_CORE, check out a sibling " +
                "at ../VaultPonyCore, or initialize the submodule: " +
                "git submodule update --init --recursive. See the README.",
        )
    }
}

val cargoBuild by tasks.registering(Exec::class) {
    workingDir = vaultPonyCore
    environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
    // Point cargo-ndk at the exact NDK AGP resolved, so the Rust build finds
    // it without any ANDROID_NDK_HOME in the user's shell environment.
    environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "x86_64", "-t", "armeabi-v7a",
        "-o", project.file("src/main/jniLibs").absolutePath,
        "build", "--release", "-p", "vault-ffi", "--locked",
    )
}

val generateBindings by tasks.registering(Exec::class) {
    dependsOn(cargoBuild)
    workingDir = vaultPonyCore
    // Bindgen reads metadata from any one of the built libraries.
    commandLine(
        "cargo", "run", "-p", "vault-ffi", "--features", "cli",
        "--bin", "uniffi-bindgen", "--",
        "generate",
        "--library",
        project.file("src/main/jniLibs/arm64-v8a/libvault_ffi.so").absolutePath,
        "--language", "kotlin",
        "--no-format",
        "--out-dir", project.file("src/main/kotlin").absolutePath,
    )
}

tasks.named("preBuild") {
    dependsOn(generateBindings)
}

// The generated UniFFI bindings land in src/main/kotlin, so every Kotlin
// compile must wait for them — hooking only preBuild isn't reliably ordered
// before compileDebugKotlin, which caused "Unresolved reference: uniffi".
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateBindings)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    // Icon set (folder/file/lock/fingerprint…). R8 tree-shakes unused icons.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    // ContextCompat.registerReceiver (screen-off auto-lock, API-safe flag).
    implementation("androidx.core:core-ktx:1.13.1")
    // Opt-in biometric unlock. NOTE: pulls in the USE_BIOMETRIC permission,
    // trading away the "zero permissions" posture — a deliberate, documented
    // THREAT_MODEL choice (see AndroidManifest.xml).
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric 1.1.0 transitively pins an ancient androidx.fragment whose
    // FragmentActivity rejects the >16-bit request codes the Compose
    // ActivityResultRegistry generates ("Can only use lower 16 bits for
    // requestCode"). Force a current fragment that fixed that.
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // ProcessLifecycleOwner: lock every session when the app leaves the
    // foreground (background or screen-off), so unlock is required on return.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // UniFFI-generated Kotlin needs JNA.
    implementation("net.java.dev.jna:jna:5.15.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
