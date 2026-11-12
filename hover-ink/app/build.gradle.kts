plugins {
    id("com.android.application")
}

android {
    namespace = "app.hoverink"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.hoverink"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

android.testOptions.unitTests.isIncludeAndroidResources = true

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

// Robolectric's own downloader doesn't work behind every proxy; let Gradle fetch
// the Android runtime jar and run Robolectric offline against it.
val robolectricRuntime: Configuration by configurations.creating
dependencies {
    robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
}
val robolectricJars = layout.buildDirectory.dir("robolectric-jars")
val copyRobolectricJars by tasks.registering(Copy::class) {
    from(robolectricRuntime)
    into(robolectricJars)
}
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricJars.get().asFile.absolutePath)
}
