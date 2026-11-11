import java.security.SecureRandom
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// App version + update repo, from version.properties
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersionName: String = versionProps.getProperty("versionName").trim()
val appVersionCode: Int = appVersionName.split('.').map { it.toInt() }.plus(listOf(0, 0, 0)).take(3)
    .let { (a, b, c) -> a * 10000 + b * 100 + c }
val updateRepo: String = versionProps.getProperty("updateRepo", "").trim()

// Signing key for release builds (kept next to this file, see keystore.properties)
val keyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// OpenAI key for automatic subtitles. It is put inside the app at build time and is never
// committed: it comes from android-app/openai.properties (apiKey=sk-...) on this computer,
// or from the OPENAI_API_KEY secret on GitHub. No key = the feature is off in that build.
// It is XOR-masked so it is not a readable string in the APK. This hides it from a casual
// look only; anyone determined can still get a key out of an app.
val openAiKey: String = (Properties().apply {
    val f = rootProject.file("openai.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("apiKey") ?: System.getenv("OPENAI_API_KEY") ?: "").trim()
val openAiPad = ByteArray(openAiKey.toByteArray().size).also { SecureRandom().nextBytes(it) }
fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
val openAiA = hex(openAiKey.toByteArray().mapIndexed { i, v -> (v.toInt() xor openAiPad[i].toInt()).toByte() }.toByteArray())
val openAiB = hex(openAiPad)

android {
    namespace = "com.khmerdubber.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.khmerdubber.app"
        minSdk = 26
        targetSdk = 36  // Google Play requires Android 16 (API 36) for new apps and updates
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "OPENAI_A", "\"$openAiA\"")
        buildConfigField("String", "OPENAI_B", "\"$openAiB\"")
    }

    // Two builds of the same app:
    //   github: sideloaded APK that updates itself from GitHub Releases (release.sh)
    //   play:   Google Play bundle, no self-update (Play policy) - updates come from Play
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        }
        create("play") {
            dimension = "store"
            buildConfigField("String", "UPDATE_REPO", "\"\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keyProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keyProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // re-encodes the picture when a logo or lower third is added
    implementation("androidx.media3:media3-transformer:1.9.4")
    implementation("androidx.media3:media3-effect:1.9.4")
    implementation("androidx.media3:media3-common:1.9.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")  // real JSON in unit tests (Android's is a stub there)
}
