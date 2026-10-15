# AGENT.md — android-usb-uvc-camera 项目规范

> 本文件是本工作区的项目规范与唯一环境事实来源。任何会改变构建配置、依赖版本或 UI 约束的操作前必须先读本文件。

## 1. 项目目标

做一个 Android App：手机经 OTG 接外接 UVC 摄像头或 HDMI 采集卡，把画面实时显示到屏幕。首版只做预览 + 分辨率 + 旋转；不做录像、多路、音频、后台推流。

## 2. 文档与真源

| 内容 | 位置 |
| --- | --- |
| 验收规格（Spec） | `docs/spec/usbcam-v1.md` |
| 实施计划（归档） | `docs/plan-usbcam-v1.md` |
| 调研报告 | `docs/AUSBC-UVC-OTG-report-2026-10.md`、`docs/AUSBC-16KB-调研报告.md` |
| 上游源码与 AAR 证据（不入库） | `_ref/` |
| 真机截图证据 | `evidence/` |

## 3. 环境事实（本机实测）

| 项 | 值 |
| --- | --- |
| JDK | `D:\Program Files\Zulu\zulu-21`（也装了 17 / 25） |
| Android SDK | `D:\Data\SDK\Android`（platforms 26→37、build-tools 33→37） |
| NDK | 25.1.8937393、28.2.13676358 |
| CMake | 3.22.1（`D:\Data\SDK\Android\cmake`） |
| Gradle | 9.3.1（发行包 `D:\Data\SDK\gradle\gradle-9.3.1`，zip 的 sha256 与上游 wrapper 声明值一致） |
| GRADLE_USER_HOME | `D:\Data\SDK\gradle-home` |
| adb | `D:\Data\SDK\Android\platform-tools\adb.exe` v37.0.0 |
| 依赖下载源 | 阿里云镜像优先 → google → mavenCentral → jitpack（见 `settings.gradle.kts`） |

## 4. 硬约束（禁止违反）

1. **主题父级必须留在 `Theme.MaterialComponents.*`**：`libausbc` 的 `TransparentBottomSheetStyle` 继承 `Theme.Design.Light.BottomSheetDialog`，换成 `Theme.Material3.*` 会让底表主题解析失败。品牌只透过 color/type/shape 令牌表达。
2. **CAMERA 权限必须保留在清单中**（不要用 tools:node="remove" 去掉它）。实测（一加 9R / ColorOS 15 / Android 15）：移除 CAMERA 后，UsbManager.requestPermission 会被 ROM 静默拒绝且不弹授权对话框，日志表现为 onReceive(USB_PERMISSION, device=null)、权限永远 DENIED。依据 StackOverflow 61542540 高票答案与本次真机复现。
3. **不得引入 Glide / MMKV / ImmersionBar / webpdecoder / libpush**：这些是上游 demo 的依赖，不属于库本身；混入会重演 JitPack POM 污染问题。
4. **native 依赖必须为 16 KB 对齐版本**：`libausbc:3.6.0` 的 arm64/x86_64 已对齐，勿降级到 jiangdongguo 3.2.7（4 KB 对齐 + RELRO 未对齐，在 16 KB 设备上会段错误）。
5. **`abiFilters` 只留 `arm64-v8a`**（除非明确要上模拟器，才临时加 `x86_64`）。
6. **UI 遵循 `impeccable/reference/android.md` + craft-floor**：触控目标 ≥48dp、字号用 sp、edge-to-edge + window insets、单一强调色、禁纯黑/渐变文字/emoji 图标/kicker 小标签/iOS 惯例。
7. **验收截图必须来自真机**（`adb exec-out screencap -p`），不使用模拟器；暗色与 1.3× 字号场景在真机上切换后截图。
8. `_ref/`、`.research/`、`.parts/`、`build/`、`local.properties` 一律不入库。
9. **旋转 90/270 时必须同步交换预览容器宽高比**：AUSBC 的 setRotateType 只旋转 OpenGL 纹理，不改变视图比例（AspectRatioTextureView.kt:41-46 的横屏交换分支被作者注释掉了）。不做交换会让画面被横向拉伸。

## 5. 操作纪律

- 任何构建/验证命令都显式设置 `JAVA_HOME` 与 `GRADLE_USER_HOME`（见第 3 节），不要依赖环境变量默认值。
- 不安装新的 SDK/NDK 组件；依赖只落到 `D:\Data\SDK` 下。
- 每条验证命令记录退出码与关键输出；失败项也要记录。
- 不把未经真机验证的结论写成「已验证」。

## 6. 验收命令

```powershell
$env:JAVA_HOME='D:\Program Files\Zulu\zulu-21'
$env:GRADLE_USER_HOME='D:\Data\SDK\gradle-home'

# 构建
gradle.bat :app:assembleDebug            # 或 .\gradlew.bat
# 依赖树（确认无脏依赖）
gradle.bat :app:dependencies --configuration debugRuntimeClasspath
# 16 KB 对齐（ELF + ZIP）
.\tools\check-16k.ps1 -Apk app\build\outputs\apk\debug\app-debug.apk
```

## 7. 关键决策记录

| 决策 | 结论 | 依据 |
| --- | --- | --- |
| UVC 引擎 | `com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0` | arm64 的 .so 实测全部 16 KB 对齐；POM 依赖树干净；已修 `FLAG_IMMUTABLE` 与 `RECEIVER_NOT_EXPORTED` |
| 备选 | `com.herohan:UVCAndroid:1.0.13`（Maven Central，全 ABI 已对齐，API 不同需重写） | JitPack 不可达时的退路 |
| 排除 CameraX/Camera2 EXTERNAL | OEM 可选（Android 16 CDD §7.5.3 定为 MAY），绝大多数手机枚举不到 | 调研报告 |
| SDK 区间 | minSdk 23 / compileSdk 36 / targetSdk 34 | 库要求 minSdk 23；targetSdk 34 避开 Android 15 强制 edge-to-edge 与前台服务类型新要求 |
| 配色 | 深色 `#101012`（非纯黑）、强调色锈红 `#C4553D`、录制指示 `#E5484D` | UI 设计规格 |
| CAMERA 权限 | **保留**（换取 ROM 的 USB 授权通道） | ColorOS 15 真机复现 + SO 61542540 |
| 旋转几何 | 90/270 时交换容器宽高比 | 读库源码 + 真机截图对比 |
| 授权入口 | 空状态提供「申请 USB 授权」「重新检测设备」按钮 | 重装后 USB 授权被清时用户可自助恢复 |

## 8. 后续可用 skill

代码与 Spec 就绪后，`code-review` skill 可对 `HEAD` 起点做双轴审查：Standards 对照本文件，Spec 对照 `docs/spec/usbcam-v1.md`。
