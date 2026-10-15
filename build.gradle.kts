// 顶层构建文件：统一 AGP 与 Kotlin 版本（对齐上游 AUSBC/ernestp 3.6.0 工具链）
plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}
