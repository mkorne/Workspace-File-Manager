plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace  = "com.example.workspacefilemanager"
    compileSdk = 35

    defaultConfig {
        applicationId   = "com.example.workspacefilemanager"
        minSdk          = 26          // API 26 = Android 8.0 (required by media3 + jcifs-ng)
        targetSdk       = 35
        versionCode     = 1
        versionName     = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // ── JNI / CMake ──────────────────────────────────────────────────
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O2")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=ON"    // enable NEON SIMD for llama.cpp
                )
            }
        }
        ndk {
            // Only package arm64-v8a for production; add x86_64 for emulator testing
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // ── CMake link ────────────────────────────────────────────────────────
    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES}"
            pickFirsts += listOf("META-INF/DEPENDENCIES")
        }
        // Exclude duplicate native libs that come with Apache MINA / Sshd
        jniLibs { excludes += listOf("META-INF/**", "**/*.kotlin_module") }
    }
}

dependencies {
    // ── Kotlin & coroutines ───────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ── AndroidX core ─────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // ── Jetpack Compose Material 3 (BOM keeps versions aligned) ──────────
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // ── Markwon — Markdown rendering ──────────────────────────────────────
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    // image-loader is not published to Maven Central; we handle images via Coil inline

    // ── Media3 / ExoPlayer ────────────────────────────────────────────────
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")   // HLS streams

    // ── Embedded FTP Server (Apache MINA FTP Server) ──────────────────────
    implementation("org.apache.ftpserver:ftpserver-core:1.2.1")
    // MINA core (MINA FTP depends on it)
    implementation("org.apache.mina:mina-core:2.2.3")

    // ── Network clients ───────────────────────────────────────────────────
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.4")   // SMBv2/v3
    implementation("com.jcraft:jsch:0.1.55")            // SFTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // GGUF model downloads

    // ── PDF viewer — uses Android native PdfRenderer (no extra dep needed) ──

    // ── Coil — image loading for album art & thumbnails ────────────────────
    implementation("io.coil-kt:coil-compose:2.6.0")

    // ── Testing ───────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}