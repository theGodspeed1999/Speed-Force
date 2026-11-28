import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // Unit tests: Android's Log calls do nothing instead of failing
    testOptions { unitTests.isReturnDefaultValues = true }
    namespace = "com.videograb.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.videograb.app"
        minSdk = 24
        targetSdk = 36
        // Goes up by itself on every build (minutes since 2026-01-01), so a new APK
        // always installs as an "Update" over the old one and keeps your data.
        val now = ZonedDateTime.now(ZoneId.of("UTC"))
        versionCode = Duration.between(
            ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC")), now
        ).toMinutes().toInt()
        versionName = "1.5.1 (" + now.withZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + ")"
    }

    // One permanent key (signing/videograb.jks) for every build. Android only accepts
    // an update if it is signed with the same key as the installed app.
    val keyProps = Properties().apply {
        val f = rootProject.file("signing/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        create("videograb") {
            storeFile = rootProject.file("signing/" + keyProps.getProperty("storeFile", "videograb.jks"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("videograb")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("videograb")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // One small APK per phone CPU type, plus a "universal" one that works everywhere
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    // yt-dlp's bundled Python must be extracted to disk to be executable
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:$ytdl")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
}
