import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.pocketagent"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.example.pocketagent"
        minSdk = 28
        targetSdk = 35
        versionCode = 14
        versionName = "1.8.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint {
        checkReleaseBuilds = false
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += setOf(
                "DebugProbesKt.bin",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "kotlin/**.kotlin_builtins",
            )
        }
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
configurations.configureEach {
    if (
        name in setOf(
            "implementation",
            "debugCompileClasspath",
            "debugRuntimeClasspath",
            "releaseCompileClasspath",
            "releaseRuntimeClasspath",
        )
    ) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-debug")
    }
}
dependencies {
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    implementation("com.dineshdev:pdfbox-android:2.0.27.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
