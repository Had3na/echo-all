import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from keystore.properties (not committed); without it, release builds stay unsigned.
val signing = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

android {
    namespace = "fr.nacre.media"
    compileSdk = 36
    defaultConfig {
        applicationId = "fr.nacre.media"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "0.23.0"
    }
    signingConfigs {
        if (signing.containsKey("storeFile")) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            // Not debuggable: noticeably smoother UI than the debug APKs used up to 0.8.
            // Code shrinking (R8) stays off until a build has been tested on a phone: a crash at launch could not be rolled back without uninstalling.
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    buildFeatures { compose = true }
    // yt-dlp ships native Python and FFmpeg builds, so one APK per processor keeps the install small.
    // These filters replace ndk { abiFilters }: AGP rejects having both, and only accepted it
    // earlier because a universal APK silently skips that check.
    // No universal APK: it would weigh 120 Mo and nobody installs it.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }
    packaging {
        // youtubedl-android unpacks its Python and FFmpeg payloads at first run; they must not stay compressed in the APK.
        jniLibs { useLegacyPackaging = true }
        resources { excludes += listOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/*.kotlin_module") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses java.time and java.util.stream APIs beyond minSdk 26.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.webkit:webkit:1.15.0")
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // Search, playlists, channels and stream resolution (the library NewPipe itself uses).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // yt-dlp with its FFmpeg build, used only for downloads (best quality, tags and cover art).
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-39")
    if (System.getProperty("os.name").startsWith("Windows")) testRuntimeOnly("org.libtorrent4j:libtorrent4j-windows:2.1.0-39")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only a stub in JVM unit tests (studio migration and backup parsing).
    testImplementation("org.json:json:20240303")
}

// Bundle the same offline Notes workspace used on Windows.
val bundleNotes by tasks.registering(Sync::class) {
    from(rootProject.file("notes/web"))
    into("src/main/assets/notes")
    doFirst { check(rootProject.file("notes/web/vendor/pdf.mjs").exists()) { "Run npm ci and npm run vendor in notes/ before building." } }
}
tasks.named("preBuild") { dependsOn(bundleNotes) }
