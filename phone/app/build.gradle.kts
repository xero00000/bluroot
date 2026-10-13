plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bluroot.share"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bluroot.share"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    compileOnly(project(":halstub"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.github.topjohnwu.libsu:core:5.2.2")
    testImplementation("junit:junit:4.13.2")
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val halClasses = project(":halstub").layout.buildDirectory.file(
            "intermediates/compile_library_classes_jar/${variant.name}/bundleLibCompileToJar${cap}/classes.jar"
        )
        val proxyDex = tasks.register<bluroot.ProxyDexTask>("proxyDex${cap}") {
            dependsOn("compile${cap}JavaWithJavac")
            dependsOn(":halstub:bundleLibCompileToJar${cap}")
            classesDir.set(
                layout.buildDirectory.dir(
                    "intermediates/javac/${variant.name}/compile${cap}JavaWithJavac/classes"
                )
            )
            androidJar.set(android.sdkDirectory.resolve("platforms/android-34/android.jar"))
            halJar.set(halClasses)
            d8.set(android.sdkDirectory.resolve("build-tools/34.0.0/d8"))
            jarBin.set(File(System.getProperty("java.home"), "bin/jar"))
            script.set(layout.projectDirectory.file("scripts/proxy-dex.sh"))
            outputDir.set(layout.buildDirectory.dir("generated/proxyAssets/${variant.name}"))
        }
        variant.sources.assets!!.addGeneratedSourceDirectory(proxyDex) { it.outputDir }
    }
}
