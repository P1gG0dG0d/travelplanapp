import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// 高德 Key 放在本机 local.properties（不进 git）；别人从源码构建时换成自己的 Key
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val amapKey: String = localProps.getProperty("amap.key") ?: "YOUR_AMAP_KEY"

android {
    namespace = "com.haoqi.travel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.haoqi.travel"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.0"
        manifestPlaceholders["AMAP_KEY"] = amapKey
    }

    buildTypes {
        debug {
            // 自用：调试版也关掉 debuggable 标记，避免系统弹「可调试应用/16KB」兼容性警告
            isDebuggable = false
        }
        release {
            isMinifyEnabled = false
            // 个人自用：正式版也用调试密钥签名，行为与调试版一致、数据互通；
            // 好处是系统不再弹「可调试应用/16KB」兼容性警告，适合日常使用
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Room（本地数据库）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 高德地图（3D 地图 + 搜索/地理编码）
    // 注意：3dmap 用 9.8.3（10.0.600 与 search 9.7.1 存在 com.amap.apis.utils.core.api 类冲突）
    implementation("com.amap.api:3dmap:9.8.3")
    implementation("com.amap.api:search:9.7.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    // 本地单元测试里 android.jar 的 org.json 是桩（Stub），调用即抛异常；这里用真实现替代
    testImplementation("org.json:json:20240303")
}
