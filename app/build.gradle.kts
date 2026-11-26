@file:Suppress("UnstableApiUsage")

import org.gradle.configurationcache.extensions.capitalized
import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
}

val appVerName: String by rootProject
val buildWithGitSuffix: String by rootProject

fun String.execute(currentWorkingDir: File = file("./")): String {
    val byteOut = ByteArrayOutputStream()
    exec {
        workingDir = currentWorkingDir
        commandLine = split(' ')
        standardOutput = byteOut
    }
    return String(byteOut.toByteArray()).trim()
}

val gitCommitCount = "git rev-list HEAD --count".execute().toInt()
val gitCommitHash = "git rev-parse --verify --short HEAD".execute()

android {
    namespace = "me.kofua.qmhelper"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "me.kofua.qmhelper"
        minSdk = 23
        targetSdk = 34
        versionCode = gitCommitCount
        versionName = appVerName

        if (buildWithGitSuffix.toBoolean())
            versionNameSuffix = ".r$gitCommitCount.$gitCommitHash"
    }

    signingConfigs {
        val keystoreFile = rootProject.file("keystore.properties")
        if (keystoreFile.isFile) {
            val prop = Properties().apply {
                keystoreFile.inputStream().use(this::load)
            }
            create("release").apply {
                storeFile = rootProject.file(prop.getProperty("keystore.path"))
                storePassword = prop.getProperty("keystore.password")
                keyAlias = prop.getProperty("key.alias")
                keyPassword = prop.getProperty("key.password")
            }
            // 复用同一把密钥给 debug，这样 debug / release 能互相覆盖安装，
            // 也省掉 AGP 去 ~/.android 生成默认 debug.keystore 的需求（本机那个目录不可写）。
            // 注意：AGP 8.x 会预置一个名为 debug 的 SigningConfig，所以用 maybeCreate。
            maybeCreate("debug").apply {
                storeFile = rootProject.file(prop.getProperty("keystore.path"))
                storePassword = prop.getProperty("keystore.password")
                keyAlias = prop.getProperty("key.alias")
                keyPassword = prop.getProperty("key.password")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        all {
            signingConfig = signingConfigs.run {
                find { it.name == "release" } ?: find { it.name == "debug" }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions",
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlin.contracts.ExperimentalContracts",
        )
    }

    packaging {
        resources {
            // META-INF/xposed/* 承载现代（libxposed）模块元数据：
            //   module.prop     —— 版本与目标 API
            //   java_init.list  —— 模块入口类（必须是 io.github.libxposed.api.XposedModule 的子类）
            //   scope.list      —— 作用域包名
            // 打包时绝对不能把这些排掉，否则框架根本不认这个模块。
            excludes += "/META-INF/*.version"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/kotlin/**"
            excludes += "/*.txt"
            excludes += "/*.bin"
        }
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            // DexKit 的 libdexkit.so 由模块自己按绝对路径 dlopen（宿主进程里
            // System.loadLibrary 找不到模块 APK 的 so），所以必须按原名解压出来。
            useLegacyPackaging = true
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    dependenciesInfo {
        includeInApk = false
    }

    androidResources {
        additionalParameters += arrayOf("--allow-reserved-package-id", "--package-id", "0x33")
    }
}

afterEvaluate {
    android.applicationVariants.forEach { variant ->
        if (variant.name != "release") return@forEach
        val variantCapped = variant.name.capitalized()
        val packageTask = tasks["package$variantCapped"]

        task<Sync>("sync${variantCapped}Apk") {
            into(variant.name)
            from(packageTask.outputs) {
                include("*.apk")
                rename(".*\\.apk", "QMHelper-v${variant.versionName}.apk")
            }
        }.let { packageTask.finalizedBy(it) }
    }
}

configurations.all {
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk7")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk8")
}

dependencies {
    compileOnly(libs.xposed.api)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.coroutines.android)
    implementation(libs.kotlin.coroutines.jdk)
    implementation(libs.androidx.annotation)
    implementation(libs.dexkit)
    implementation(libs.dexmaker)
}