plugins {
    id("rikkahub.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.tunnel"

    // cloudflared 是完整的 Go 可执行文件, 伪装成 .so 放进 jniLibs —— 系统会把它解到
    // 只读的 nativeLibraryDir, 那里允许执行(Android 10+ 禁止执行可写数据目录里的二进制)。
    // 这几个开关缺一不可, 否则二进制会被压缩或裁剪而无法运行。
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libcloudflared.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
}
