import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val startUrl = providers.gradleProperty("optoflow.startUrl").get()
val allowedHosts = providers.gradleProperty("optoflow.allowedHosts").get()
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
val startHost = URI(startUrl).host

// Release signing is read from android/keystore.properties (never committed).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.optoflow.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.optoflow.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "START_URL", "\"$startUrl\"")
        buildConfigField(
            "String[]", "ALLOWED_HOSTS",
            allowedHosts.joinToString(prefix = "{", postfix = "}") { "\"$it\"" },
        )
        manifestPlaceholders["deepLinkHost"] = startHost
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.browser)
    implementation(libs.material)
}
