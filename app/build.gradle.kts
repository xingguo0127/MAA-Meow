import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

val gitVersionCode: Int by lazy {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}

val gitVersionName: String by lazy {
    val desc = providers.exec {
        commandLine("git", "describe", "--tags", "--always")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    val match =
        Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.]+))?(?:-(\d+)-g[0-9a-f]+)?$""").matchEntire(
            desc
        )
    if (match != null) {
        val (major, minor, patch, pre, distance) = match.destructured
        when {
            distance.isEmpty() && pre.isEmpty() -> "$major.$minor.$patch"
            distance.isEmpty() -> "$major.$minor.$patch-$pre"
            else -> "$major.$minor.${patch.toInt() + 1}-alpha.$distance"
        }
    } else {
        desc.removePrefix("v").ifEmpty { "0.0.0-dev" }
    }
}

android {
    namespace = "com.aliothmoon.maameow"
    compileSdk = 37


    defaultConfig {
        applicationId = "com.aliothmoon.maameow"
        minSdk = 33
        targetSdk = 36
        versionCode = gitVersionCode
        versionName = gitVersionName
        println("Build version: versionCode=$versionCode, versionName=$versionName")
        ndkVersion = "29.0.13113456"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // setup_maa_core.py deploy 时写入 .maaversion；缺失时为空串，运行时检查宽松放行
        val maaCoreVersion = rootProject.file(".maaversion")
            .takeIf { it.isFile }?.readText()?.trim().orEmpty()
        buildConfigField("String", "MAA_CORE_VERSION", "\"$maaCoreVersion\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }


        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: localProperties.getProperty("KEYSTORE_PATH", "")
            if (keystorePath.isNotEmpty()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: localProperties.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = System.getenv("KEY_ALIAS")
                    ?: localProperties.getProperty("KEY_ALIAS", "")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: localProperties.getProperty("KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: localProperties.getProperty("KEYSTORE_PATH", "")
            if (keystorePath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
                println("[Signing] Using release keystore: $keystorePath")
            } else {
                println("[Signing] No release keystore configured, release build will not be signed")
            }
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        aidl = true
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            pickFirsts += setOf(
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md"
            )
            // appfunctions-compiler 生成的 generated/ksp/<variant>/resources/assets/{app_functions.xml,app_functions_v2.xml}
            // 会被 Kotlin 的 java resources 流水线(processDebugJavaRes)自动拾取一份、
            // 又被上面 sourceSets.assets.srcDir 的显式登记再拾取一份 → packageDebug 撞名冲突(cannot overwrite)。
            // 只保留显式 assets 登记这一份(真正的 Android assets 语义)，排除 java resources 里的重复项。
            excludes += setOf(
                "assets/app_functions.xml",
                "assets/app_functions_v2.xml"
            )
        }
    }

    androidResources {
        localeFilters += listOf("zh", "en")
    }

    // KSP 生成的 app_functions.xml(系统索引数据源)默认不并入 android assets → 双变体显式登记
    listOf("debug", "release").forEach { v ->
        sourceSets.getByName(v).assets.srcDir(
            "${layout.buildDirectory.get().asFile}/generated/ksp/$v/resources/assets"
        )
    }

    lint {
        // AGP 9 强制使用 K2 UAST，其在分析 .gradle.kts 构建脚本时会崩溃
        // (findFirCompiledSymbol on non-compiled declaration)，导致 release
        // 构建的 lintVitalRelease 失败。旧的 android.lint.useK2Uast=false 开关
        // 在 AGP 9 已失效，故关闭 release 期间自动触发的 lint-vital 检查。
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// AppFunction 注解处理:聚合本模块 @AppFunction 生成元数据/服务
ksp {
    arg("appfunctions:aggregateAppFunctions", "true")
}

// mergeDebugAssets 必须在 kspDebugKotlin(生成 app_functions.xml)之后跑
afterEvaluate {
    listOf("Debug", "Release").forEach { v ->
        tasks.findByName("merge${v}Assets")?.dependsOn("ksp${v}Kotlin")
    }
}

dependencies {
    compileOnly(project(":hidden-api"))
    implementation(project(":annotation-api"))
    ksp(project(":ksp-processor"))

    // AppFunction 提供侧
    implementation(libs.appfunctions)
    implementation(libs.appfunctions.service)
    ksp(libs.appfunctions.compiler)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.exifinterface)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.window)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Third-party
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.fastjson2)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libsu)
    implementation(libs.device.compat)
    implementation(libs.xx.permissions)
    implementation(libs.floatingx)
    implementation(libs.sonner)
    implementation(libs.timber)
    implementation(libs.okhttp)
    implementation(libs.angus.mail)
    implementation(libs.angus.activation)
    implementation(libs.jakarta.activation.api)
    implementation(libs.reorderable)
    implementation(libs.compose.markdown)

    // sora-editor：JSON 语法高亮编辑器（TextMate + darcula 主题）
    implementation(platform(libs.bom))
    implementation(libs.editor)
    implementation(libs.editor.language.textmate)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Apply asset manifest generation script
apply(from = "asset-manifest.gradle.kts")

// Apply i18n strings consistency gate (verifyI18nStrings hooked to preBuild)
apply(from = "i18n-verify.gradle.kts")


abstract class GenerateAchievementStringResTask : DefaultTask() {

    @get:InputFile
    abstract val stringsFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private val nameRe = Regex("^achievement_([A-Za-z0-9]+)_(title|desc|condition)$")

    @TaskAction
    fun generate() {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(stringsFile.get().asFile)
        val nodes = doc.getElementsByTagName("string")

        val byField = linkedMapOf(
            "title" to sortedMapOf<String, String>(),
            "desc" to sortedMapOf(),
            "condition" to sortedMapOf(),
        )
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val m = nameRe.matchEntire(el.getAttribute("name")) ?: continue
            byField.getValue(m.groupValues[2])[m.groupValues[1]] = m.value
        }

        val code = buildString {
            appendLine("// AUTO-GENERATED by generate*AchievementStringRes. DO NOT EDIT.")
            appendLine("package com.aliothmoon.maameow.data.achievement")
            appendLine()
            appendLine("import com.aliothmoon.maameow.R")
            appendLine()
            appendLine("internal fun achievementStringResId(id: String, field: AchievementField): Int = when (field.key) {")
            for ((field, entries) in byField) {
                appendLine("    \"$field\" -> when (id) {")
                for ((id, resName) in entries) {
                    appendLine("        \"$id\" -> R.string.$resName")
                }
                appendLine("        else -> 0")
                appendLine("    }")
            }
            appendLine("    else -> 0")
            appendLine("}")
        }

        val pkgDir = outputDir.get().asFile.resolve("com/aliothmoon/maameow/data/achievement")
        pkgDir.mkdirs()
        pkgDir.resolve("AchievementStringResGenerated.kt").writeText(code)
        logger.lifecycle("Generated achievementStringResId: ${byField.values.sumOf { it.size }} entries")
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
        val genTask = tasks.register(
            "generate${variantName}AchievementStringRes",
            GenerateAchievementStringResTask::class.java,
        ) {
            description =
                "Generate achievement string-resource lookup (avoids Resources.getIdentifier)"
            group = "build"
            stringsFile.set(layout.projectDirectory.file("src/main/res/values/strings.xml"))
        }
        variant.sources.kotlin?.addGeneratedSourceDirectory(genTask) { it.outputDir }
    }
}
