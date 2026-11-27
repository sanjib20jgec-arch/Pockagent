import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.github.takahirom.roborazzi")
    id("io.gitlab.arturbosch.detekt")
}

val robolectricInstrumentedVersion = "15-robolectric-14663667-2-i7"
val robolectricSdk by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
val prepareRobolectricSdk by tasks.registering(Copy::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
    rename { "android-all-instrumented-$robolectricInstrumentedVersion.jar" }
}

android {
    namespace = "com.example.pocketagent"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.example.pocketagent"
        minSdk = 28
        targetSdk = 35
        versionCode = 13
        versionName = "1.6.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    packaging { jniLibs.useLegacyPackaging = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.dependsOn(prepareRobolectricSdk)
                it.systemProperty(
                    "robolectric.dependency.dir",
                    layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath,
                )
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                )
            }
        }
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
roborazzi {
    outputDir.set(file("src/screenshots"))
}
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt.yml"))
}
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
}
dependencies {
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    implementation("com.dineshdev:pdfbox-android:2.0.27.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.18")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.79.0")
    robolectricSdk("org.robolectric:android-all-instrumented:$robolectricInstrumentedVersion")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
