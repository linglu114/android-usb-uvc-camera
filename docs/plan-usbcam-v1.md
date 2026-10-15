# 实施计划 — usbcam v1（归档）

批准时间：2026-10-15。本文件是已批准计划的归档，验收基准见 [docs/spec/usbcam-v1.md](spec/usbcam-v1.md)。

## 1. 已定稿决策（Q1–Q18）

| # | 决策 | 结论 |
| --- | --- | --- |
| Q1 | SDK 区间 | minSdk 23 / compileSdk 36 / targetSdk 34；主验证 Android 15/16（arm64），向下兼容 Android 6 |
| Q2 | 首版范围 | 预览 + 分辨率 + 旋转；拍照留后续；录像不做 |
| Q3 | 库选型 | `com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0`（JitPack）；备选 `com.herohan:UVCAndroid:1.0.13` |
| Q4 | USB 回调 | 先用 `CameraActivity` 默认流程，不补 `compileOnly libuvc` |
| Q5 | ABI | 只打包 `arm64-v8a` |
| Q6 | 录像 | 列入「不做」 |
| Q7 | 验证口径 | 自动项（构建/依赖树/ELF/ZIP）+ 真机最终验收 |
| Q8 | 目录 | 项目根 `D:\Data\Work\Project\AUSBC`；调研物归 `docs/`；git 仓库名 `android-usb-uvc-camera` |
| Q9 | 里程碑 | 最小可见画面优先 |
| Q10 | 工具链 | Gradle 9.3.1 + AGP 8.10.1 + Kotlin 2.2.10（对齐上游工程） |
| Q11 | 身份 | 包名 `com.linglu.usbcam`、应用名「USB 摄像头」、界面中文 |
| Q12 | 默认行为 | 单设备自动授权打开；多设备弹列表 |
| Q13 | 常亮 | `FLAG_KEEP_SCREEN_ON`，控制条可关 |
| Q14 | 多路 | 首版不做 |
| Q15 | 文档三分 | `AGENT.md` + `docs/spec/usbcam-v1.md` + `docs/plan-usbcam-v1.md` |
| Q16 | UI | 深色默认（跟随系统）/ 竖屏锁定 + 底部单行控制条 + BottomSheet / 不用 Dynamic Color |
| Q17 | Splash | 不做 |
| Q18 | 验收截图 | 真机（`adb exec-out screencap -p`），不使用模拟器 |

## 2. 事实基础（实测）

| 结论 | 证据 |
| --- | --- |
| 上游 jiangdongguo 版停更（2024-09-02），其 JitPack 产物 arm64 的 .so 全部 4 KB 对齐，且多处 RELRO 未对齐 | `llvm-readelf -l` 实测 + 官方 page-sizes 文档 |
| 其 `libausbc` POM 声明了无法解析的 `com.gyf.immersionbar`、`com.zlc.glide:webpdecoder`（源自作者 demo 污染） | POM 原文 + 分仓库探测 + issue #728 |
| ernestp 3.6.0 的 arm64-v8a / x86_64 的 .so 全部 16 KB 对齐（LOAD align=0x4000） | `llvm-readelf -l` 实测 |
| ernestp 依赖树干净：xlog 1.11.1 + kotlin-stdlib 2.2.10 + appcompat 1.7.1 + constraintlayout 2.2.1 + material 1.13.0 | POM 原文 |
| 已修 `FLAG_IMMUTABLE` 与 `RECEIVER_NOT_EXPORTED`（原版 3.2.7 在 targetSdk>=34 会崩） | `USBMonitor.java` 335-350 / 853-858 |
| 库的 `TransparentBottomSheetStyle` 继承 `Theme.Design.Light.BottomSheetDialog` → 主题父级须留在 MaterialComponents | `libausbc/res/values/styles.xml` |
| Gradle 9.3.1 发行包 sha256 与上游 wrapper 声明值一致 | `b266d5ff…aff06` 双向核对 |

## 3. 实施步骤

### S0 脚手架
git init（`android-usb-uvc-camera`）、`.gitignore`（排除 `_ref/`、`.research/`、`.parts/`、`build/`、`local.properties`）、`AGENT.md`、`docs/spec/`、`docs/plan`、wrapper 三件套、`settings.gradle.kts`、根 `build.gradle.kts`、`gradle.properties`、`local.properties`。

### S1 最小可见画面
`app/build.gradle.kts`（compileSdk 36 / minSdk 23 / targetSdk 34 / arm64-v8a）；`AndroidManifest.xml`（usb.host 非必需、`USB_DEVICE_ATTACHED` 过滤器、移除无效的 `USB_PERMISSION`、竖屏）；`MainActivity : CameraActivity`（`getRootView` / `getCameraView` / `getCameraViewContainer` / `getCameraRequest` / `getGravity` / `onCameraState`）；主题与配色令牌；`values-night`。

### S2 控制条与三态
底部控制条六项（设备/分辨率/旋转/镜像/常亮/更多）、状态条、Snackbar 反馈、空/错/载三态、insets 适配。镜像因库无公开 API 而隐藏按钮（记入 spec 已知限制）。

### S3 可选（未做）
拍照 `captureImage`。

## 4. 验证方案

| 层级 | 命令/方法 | 通过标准 | 需真机 |
| --- | --- | --- | --- |
| 构建 | `gradle.bat :app:assembleDebug` | BUILD SUCCESSFUL + APK | 否 |
| 依赖树 | `:app:dependencies --configuration debugRuntimeClasspath` | 无 immersionbar/webpdecoder/glide/mmkv | 否 |
| ELF + ZIP | `tools/check-16k.ps1` | 全部 align >= 2**14 且 zipalign 通过 | 否 |
| 安装与画面 | `adb install` → 插设备 → 截图 | 出画面、等比、无拉伸 | 是 |
| 插拔 | 拔下 → 重插 | 自动重连或回到空状态 | 是 |
| 分辨率/旋转 | 控制条切换 | 生效并更新状态条 | 是 |
| 暗色/字号 | `cmd uimode night` / `settings put system font_scale 1.3` | 可读、不裁剪 | 是 |

## 5. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| JitPack 不可达 | 仓库顺序含阿里云与 Maven Central；备选切 herohan |
| Theme.Material3 与库底表主题冲突 | 父级固定在 MaterialComponents，M3 仅作令牌层 |
| Gradle 9.3.1 首次下载失败 | 已下好并校验 sha256，本地解压于 `D:\Data\SDK\gradle\gradle-9.3.1` |
| 国产 ROM OTG/授权弹窗缺省 | 空状态三条指引；不依赖后台常驻 |
| 采集卡 UAC 音频不工作 | 首版不做音频 |
| 高分辨率带宽不足 `errno=12` | 默认 720p + 降级链 + 手动切换 |
| OTG 供电不足（硬件） | 指引使用带供电 Hub / Y 型线 |

## 6. 明确不做

录像、多路摄像头、音频、Splash、Dynamic Color、后台上传/推流（不含 libpush）、CameraX/Camera2 EXTERNAL、Flutter/Unity 封装、root/系统注入、用模拟器做验收。
