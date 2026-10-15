# Android OTG 外接 USB 摄像头 / HDMI 采集卡 开源方案调研报告

> 调研时间：2026-10-14（Asia/Shanghai）｜调研方式：联网检索取证（GitHub 仓库页 / commits.atom / tags / releases、JitPack、Maven Central、AOSP 与 Android 官方文档、Gitee），并实际下载 AAR 反查 native 库与 AndroidManifest。
> 所有结论均附来源 URL；star / 提交时间等为抓取时刻实测值。

---

## 0. 一句话结论

**能用、且最省事的最小可行路径 = 用 `ernestp/AndroidUSBCamera`（jiangdongguo 原项目的活跃维护 fork）的 JitPack AAR `com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0`**；
若更看重「官方 Maven Central + 存量中文资料」，第二选择是 `com.herohan:UVCAndroid:1.0.13`（仓库真名 **shiyinghan/UVCAndroid**，不叫 herohan/UVCAndroid）。
**不要走 CameraX / Camera2 的 EXTERNAL 路线**——那是 OEM 可选项，绝大多数手机没开（见 §7 证据）。

---

## 1. 候选总览对比表

| 项目（仓库全名） | Star | 最后提交 | 最近 release/tag | 2024-2026 仍在维护 | License | 依赖坐标 | 自带 .so | minSdk / target |
|---|---|---|---|---|---|---|---|---|
| [ernestp/AndroidUSBCamera](https://github.com/ernestp/AndroidUSBCamera) | 35 / 14 fork | 2026-05-09（158 天前） | 3.6.0（2026-05-09） | 是，活跃 | Apache-2.0 | JitPack `com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0` | 有，4 ABI 预编译 | 23 / 36 |
| [shiyinghan/UVCAndroid](https://github.com/shiyinghan/UVCAndroid) | 470 / 132 fork | 2026-06-24（112 天前） | 1.0.13（2026-06-10） | 是，活跃 | Apache-2.0 | Maven Central `com.herohan:UVCAndroid:1.0.13` | 有，4 ABI 预编译 | 21 / 34 |
| [jiangdongguo/AndroidUSBCamera](https://github.com/jiangdongguo/AndroidUSBCamera) | 2764 / 937 fork | 2024-09-02（772 天前） | 3.3.3（2023-02-13） | 基本停更 | Apache-2.0 | JitPack `com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.3.3` | 有，4 ABI 预编译 | 19 / 27（库清单） |
| [saki4510t/UVCCamera](https://github.com/saki4510t/UVCCamera) | 3218 / 1305 fork | 2018-10-02（2934 天前） | 无 release，仅 master | 否，停更 8 年 | Apache-2.0（jni/libjpeg,libusb,libuvc 另有许可） | 无官方坐标，只能源码引入 | 需 NDK 自编译 | API 12+（示例 18） |
| [Liuguihong/AndroidUVCCamera](https://github.com/Liuguihong/AndroidUVCCamera) | 247 / 73 fork | 2019-10-26（2545 天前） | tag 1.0.0（2019-01-18） | 否 | 仓库根无 LICENSE 文件 | README 写 JitPack 1.0.0，实测构建失败 | 源码内 libs | — |
| [yunianvh/usb-camera](https://gitee.com/yunianvh/usb-camera)（Gitee） | 12 / 7 fork | 2025-08-29（页面标注） | tag `libusbcamera-v1.2.7` | 低频 | MIT（Gitee 页标注） | JitPack `com.gitee.yunianvh:usb-camera:libusbcamera-v1.2.7` | 有，打成 AAR | — |
| [poorld/UVCCamera](https://github.com/poorld/UVCCamera) | 3 / 0 fork | 2025-09-13（396 天前） | V1.0（2025-09-13） | 极新/极小 | 仓库根无 LICENSE 文件 | 无坐标，源码 + CMake | CMake 自行编译 | 29 / 35 |
| [shlllshlll/USBCamera](https://github.com/shlllshlll/USBCamera) | 44 / 11 fork | 2020-04-19（2369 天前） | v2.0.7（2020-05-22） | 否，已 archive | MIT | 源码引入 | 需 NDK | 21 / 27 |
| [libuvc/libuvc](https://github.com/libuvc/libuvc)（底层库） | 1165 / 558 fork | 2026-10-01（13 天前） | v0.0.8（2026-09-13） | 是，活跃 | 无 SPDX 标准标识（NOASSERTION） | 非 Android 库，C/CMake | 需交叉编译 | — |

**候选核实中被证伪的条目（重要）**

| 你给的候选 | 核实结果 |
|---|---|
| `herohan/UVCAndroid`（`com.herohan.uvcapp`） | **仓库不存在**（GitHub 404）。`com.herohan` 是 Maven **groupId**，真实仓库是 [shiyinghan/UVCAndroid](https://github.com/shiyinghan/UVCAndroid)；`com.herohan.uvcapp` 只是该仓库 **app 示例模块**的 namespace（见其 [app/build.gradle](https://raw.githubusercontent.com/shiyinghan/UVCAndroid/master/app/build.gradle)）。 |
| `duvitech-llc/AndroidUSBCamera` | 存在但是**无关仓库**（2018-02-12 最后提交，1 star，不是该库的活跃 fork）。 |

---

## 2. 逐项详情

### 2.1 ernestp/AndroidUSBCamera（推荐首选）

- **URL**：https://github.com/ernestp/AndroidUSBCamera
- **规模/维护**：仓库页 35 star / 14 fork；[commits atom](https://github.com/ernestp/AndroidUSBCamera/commits/master.atom) 最新 2026-05-09；[tags](https://github.com/ernestp/AndroidUSBCamera/tags) 最新 3.6.0（2026-05-09）；[releases](https://github.com/ernestp/AndroidUSBCamera/releases) 3.6.0 为 Latest。README 自述「actively maintained, canonical fork」，并写明上游 jiangdongguo 最后提交 2024-09、最后 release 2023-02，与本报告实测一致。
- **License**：Apache-2.0（[LICENSE](https://raw.githubusercontent.com/ernestp/AndroidUSBCamera/master/LICENSE)，Copyright 2017-2023 Jiangdongguo / 2025-present Ernest Poletaev 及贡献者）。**可商用，无 copyleft 传染**。
- **技术架构**：Kotlin 重构版 AUSBC 3.x，模块 `libausbc` + `libuvc` + `libnative` + `libuvccommon`。
  - **AAR 实测**（下载 `libausbc-3.6.0.aar`、`libnative-3.6.0.aar` 解包）：`jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/libnativelib.so` **已预编译，不需要自己配 NDK**；AndroidManifest 中 `minSdkVersion=23`、`package=com.jiangdg.ausbc`，声明 `android.hardware.usb.host`、CAMERA、WAKE_LOCK、RECORD_AUDIO。
  - 源码构建需要 **NDK 28.1.13356709 + AGP 8.10.1 + compileSdk/targetSdk 36**（[build.gradle](https://raw.githubusercontent.com/ernestp/AndroidUSBCamera/master/build.gradle)）。
- **功能**：多路摄像头（`MultiCameraFragment` / `MultiCameraActivity`）；热插拔回调 `onCameraAttached/onCameraDetached/onCameraConnected/onCameraDisConnected`；分辨率枚举 `getAllPreviewSizes()` + `updateResolution(w,h)`；预览格式由 `CameraRequest.PreviewFormat` 指定（README 注 **MJPEG 推荐**）；拍照 `captureImage(callBack, savePath)`；录像 `captureVideoStart/Stop`（mp4/h264/yuv + pcm/mp3/aac）；裸流 `addEncodeDataCallBack`（H.264/AAC）、`setPreviewDataCallBack`（NV21/RGBA）；OpenGL ES 2.0 滤镜；4 ABI。
- **采集卡兼容性**：issue 区有真实采集场景反馈，如 [ernestp/AndroidUSBCamera#24「It doesn't play audio from my USB video capture」](https://github.com/ernestp/AndroidUSBCamera/issues/24)（2026-09-11，**画面可用、UAC 音频不工作**）、[#21「支持的摄像头总数有限制吗」](https://github.com/ernestp/AndroidUSBCamera/issues/21)（2026-08-05）。README 未对采集卡做专门承诺，属通用 UVC 支持。
- **集成方式**：JitPack，`implementation 'com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0'`（[JitPack 构建记录](https://jitpack.io/api/builds/com.github.ernestp/AndroidUSBCamera) 中 3.6.0 = ok）。groupId 与仓库名大小写必须一致。
- **已知坑**：
  - **黑屏先查 targetSdk**：FAQ 第 1 条「targetSdk>=28 需授予 CAMERA 权限；若仍不行要把 targetSdk 降到 28 以下」（[FAQ.md](https://raw.githubusercontent.com/ernestp/AndroidUSBCamera/master/FAQ.md)）。这是继承自上游的**过时忠告**，现代上架语境下不可行，本质是部分国产 ROM 的 USB 授权自定义问题。
  - **识别不到设备**时要自建 `res/xml/default_device_filter.xml`，默认放 `class=239 subclass=2`（UVC 通配），并补自己采集棒的 VID/PID（FAQ 第 2 条另给了 5 组已知非 239/2 设备的兜底 VID/PID）。
  - **热插拔 ANR**：FAQ 第 3 条，3.2.7+ 已优化。
  - 3.6.0 的主要改动是「启用 x86/x86_64 构建」「使用 MediaStore」（[release 3.6.0](https://github.com/ernestp/AndroidUSBCamera/releases/tag/3.6.0)）；README 宣称兼容 **Android 16 + 16K page size**。

### 2.2 shiyinghan/UVCAndroid（`com.herohan:UVCAndroid`，推荐次选）

- **URL**：https://github.com/shiyinghan/UVCAndroid
- **规模/维护**：470 star / 132 fork；[commits atom](https://github.com/shiyinghan/UVCAndroid/commits/master.atom) 最新 2026-06-24（近期提交含「移除 hutool 依赖」「修复 MediaTek 机型黑屏」）；[release 1.0.13](https://github.com/shiyinghan/UVCAndroid/releases/tag/1.0.13)（2026-06-10）。**是本批候选中最近仍在持续提交的项目**。
- **License**：Apache-2.0（[LICENSE](https://raw.githubusercontent.com/shiyinghan/UVCAndroid/master/LICENSE)；Maven POM 亦声明 Apache-2.0）。**可商用**。
- **技术架构**：saki4510t/UVCCamera 血统（Java 层 `com.serenegiant.uvccamera`），`libuvccamera` 模块用 **ndkBuild（Android.mk）** 编 JNI，`abiFilters armeabi-v7a, arm64-v8a, x86, x86_64`（[libuvccamera/build.gradle](https://raw.githubusercontent.com/shiyinghan/UVCAndroid/master/libuvccamera/build.gradle)）。
  - **AAR 实测**（下载 [UVCAndroid-1.0.13.aar](https://repo1.maven.org/maven2/com/herohan/UVCAndroid/1.0.13/UVCAndroid-1.0.13.aar)）：含 4 ABI 的 `libUVCCamera.so / libuvc.so / libusb1.0.so / libjpeg-turbo212.so / libyuv.so`（**预编译，无需 NDK**）；AndroidManifest `minSdkVersion=21`，声明 CAMERA / RECORD_AUDIO / MANAGE_EXTERNAL_STORAGE / READ|WRITE_EXTERNAL_STORAGE / **FOREGROUND_SERVICE**，并有 `uses-feature android.hardware.usb.host`。
  - 仓库自身：compileSdk/targetSdk **34**、`versionMin = 21`（[build.gradle](https://raw.githubusercontent.com/shiyinghan/UVCAndroid/master/build.gradle)），用 `com.vanniktech.maven.publish` 发布到 Maven Central。
- **功能**：单路/多路预览（示例含多路）、分辨率选择、拍照、录像（libuvccamera 内置录制辅助）、预览回调、热插拔（saki 血统的 connect/disconnect 回调）。格式支持由设备能力决定（libuvc 支持 MJPEG/YUYV），**H.264 需自行用 MediaCodec 对接**。
- **采集卡兼容性**：issue 区有直接相关条目——[#150「3104x3840 这种尺寸无法录制视频」](https://github.com/shiyinghan/UVCAndroid/issues/150)（2026-09-10，**超规格分辨率录制失败**）、[#147 MediaCodec (C2NodeImpl) leak on USB camera detach/reattach cycle](https://github.com/shiyinghan/UVCAndroid/issues/147)（2026-05-15，**反复插拔会泄漏，长跑需注意**）、[#151 部分华为/鸿蒙 4.0、4.2 机型异常](https://github.com/shiyinghan/UVCAndroid/issues/151)（2026-09-14）。另有 issue 指出 **`LIBUVC_PACKETS_PER_TRANSFER_MAX` 不宜设太大**，与 libuvc README 的 ENOMEM 警告一致。
- **集成方式**：Maven Central，无需 JitPack：`implementation 'com.herohan:UVCAndroid:1.0.13'`，另需 ProGuard 规则（见 §5.2）。
- **已知坑**：minSdk 21；AAR 已声明 FOREGROUND_SERVICE 权限，但 Android 14+ 的 `foregroundServiceType` 与「不能从后台启动」约束仍需 app 侧处理；华为/鸿蒙有 open issue；MediaCodec 泄漏在插拔循环下累积；x86 ABI 会让 APK 变大（建议 abiFilters / ABI splits 裁剪）。

### 2.3 jiangdongguo/AndroidUSBCamera（原项目，可用但已是遗产）

- **URL**：https://github.com/jiangdongguo/AndroidUSBCamera
- **规模/维护**：**2764 star / 937 fork（star 最多、资料最全）**；[commits atom](https://github.com/jiangdongguo/AndroidUSBCamera/commits/master.atom) 最新 **2024-09-02**（772 天前）；[tags](https://github.com/jiangdongguo/AndroidUSBCamera/tags) 最新 3.3.3（2023-02-13）。
- **License**：Apache-2.0（[JitPack POM](https://jitpack.io/com/github/jiangdongguo/AndroidUSBCamera/libausbc/3.3.3/libausbc-3.3.3.pom) 内 `<license>Apache License 2.0</license>`；README 尾部同）。
- **技术架构**：Kotlin AUSBC 3.x；`libausbc`(api `libnative`) + `libuvc`(内置 `libusbcommon_v4.1.1.aar`)。**AAR 实测**：`libausbc-3.3.3.aar` 只含 classes.jar 与一个 `minSdkVersion=19, targetSdkVersion=27` 的清单，**native 在 `libnative` 模块**（JitPack 会自动拉 `com.github.jiangdongguo.AndroidUSBCamera:libnative:3.3.3`）。
- **功能**：多路摄像头、480p/720p/1080p 预览、OpenGL ES 滤镜、拍照 jpg、录像 mp4·h264·yuv、音频 pcm·mp3·aac、H.264/AAC 裸流、旋转、离屏渲染、UAC 麦克风、分辨率/设备枚举、相机参数（zoom/sharpness/gain/AWB/AF）。**功能矩阵与 ernestp fork 基本相同**（fork 即其延续）。
- **采集卡兼容性**：issue 存量最多（[issues](https://github.com/jiangdongguo/AndroidUSBCamera/issues?q=is%3Aissue) 492 个），可搜到拍照无回调（[#754](https://github.com/jiangdongguo/AndroidUSBCamera/issues/754)，2026-05-09 仍有人报）、录像 `mMediaMuxer` 为 null 导致 `onBegin()` 不触发（[#742](https://github.com/jiangdongguo/AndroidUSBCamera/issues/742)，2025-09-11）、多路超过若干路不工作（#694 系列）。这些问题在活跃 fork 中部分被修。
- **集成方式**：`implementation 'com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.3.3'`（[JitPack 构建记录](https://jitpack.io/api/builds/com.github.jiangdongguo/AndroidUSBCamera) 3.2.x/3.3.x 均 ok）；也可按 README 源码引入 `:libausbc`/`:libuvc`/`:libnative`/`:libuvccommon` 四模块。
- **已知坑**：整体构建停留在 **AGP 4.2.2 + Kotlin 1.5.20 + compileSdk 29 + jcenter()**（[build.gradle](https://raw.githubusercontent.com/jiangdongguo/AndroidUSBCamera/master/build.gradle)），**jcenter 已关停**，源码引入需自行迁移到 mavenCentral；`versionTarget = 27` 意味着库清单 targetSdk 27，**在现代 AGP 8.x + targetSdk 35/36 项目里会触发清单合并与权限行为告警**；3.1.7 起官方**取消了 x86/x86_64**（[VERSION.md](https://raw.githubusercontent.com/jiangdongguo/AndroidUSBCamera/master/VERSION.md)），模拟器调试需自己编。

### 2.4 saki4510t/UVCCamera（鼻祖，只建议当参考实现）

- **URL**：https://github.com/saki4510t/UVCCamera｜3218 star / 1305 fork；[commits atom](https://github.com/saki4510t/UVCCamera/commits/master.atom) 最新 **2018-10-02**；无 tag、无 release。**已停更 8 年**。
- **License**：Apache-2.0，但 README 明确提示 **`jni/libjpeg`、`jni/libusb`、`jni/libuvc` 目录可能是不同许可，需逐文件确认**（[README](https://raw.githubusercontent.com/saki4510t/UVCCamera/master/README.md) 第 20-22 行）。这是它的**法务风险点**（libjpeg-turbo 为 IJG/BSD 混合条款）。
- **技术架构**：Java + JNI（`Android.mk` ndkBuild）+ libusb + libuvc + libjpeg-turbo；**必须本机 NDK 编译**（README 给了 Gradle 与 ndk-build 两条路径）。API 12+ 可用（部分示例需 18）。
- **功能**：预览（TextureView/SurfaceView）、帧回调 `IFrameCallback`、分辨率与帧率设置、bulk transfer 模式、MediaCodec+MediaMuxer 录像与抓帧（USBCameraTest2/3/4/5 示例）。
- **坑**：无维护、无 release 坐标；社区已知的拔插崩溃（[PR #454](https://github.com/saki4510t/UVCCamera/pull/454)）与 JNI 内存泄漏（[issue #259](https://github.com/saki4510t/UVCCamera/issues/259)）都是在**下游 fork** 里才修的。

### 2.5 Liuguihong/AndroidUVCCamera（曾是最简 API，但已死）

- **URL**：https://github.com/Liuguihong/AndroidUVCCamera｜247 star / 73 fork；[commits atom](https://github.com/Liuguihong/AndroidUVCCamera/commits/master.atom) 最新 **2019-10-26**；[tags](https://github.com/Liuguihong/AndroidUVCCamera/tags) 仅 1.0.0（2019-01-18）；仓库根**没有 LICENSE 文件**（raw 请求 404，README 也未声明许可）→ **商用前必须向作者确认授权**。
- **技术架构**：封装 saki4510t/UVCCamera，`UVCCameraProxy` 一个类走完预览/拍照；顶层 [build.gradle](https://raw.githubusercontent.com/Liuguihong/AndroidUVCCamera/master/build.gradle) 还是 **jcenter() + AGP 2.3.3**。
- **实测集成失败**：[JitPack 构建记录](https://jitpack.io/api/builds/com.github.Liuguihong/AndroidUVCCamera) 显示 `1.0.0 = Error`、`master = Error`，`https://jitpack.io/com/github/Liuguihong/AndroidUVCCamera/1.0.0/AndroidUVCCamera-1.0.0.pom` 返回 404 → **README 里写的依赖坐标现在拉不到**。
- **结论**：仅适合阅读其 API 设计（`requestPermission → connectDevice → openCamera → setPreviewSize → startPreview → takePicture` 状态机很清晰，可作自研封装参考），**不建议作为工程底座**。

### 2.6 yunianvh/usb-camera（Gitee，低延时取向）

- **URL**：https://gitee.com/yunianvh/usb-camera｜12 star / 7 fork；Gitee 项目页标注 **License MIT**、创建 2024-01-08、最后更新 2025-08-29；tag `libusbcamera-v1.2.7`（[JitPack 构建记录](https://jitpack.io/api/builds/com.gitee.yunianvh/usb-camera) 1.2.1~1.2.7 均 ok）。
- **技术架构**：基于 saki4510t/UVCCamera 精简，「去掉不必要代码只留核心功能并打成 aar」；核心类 `com.yunianvh.libusbcamera.USBCameraHelper`（单例）。
- **功能**：预览、`setPreviewSize(w,h,...)` 切分辨率、`startRecord(path)`/`stopRecord()` 录像、`isRecording()`；README 示例还演示 RTMP 推流（依赖 pedroSG94 的 rtplibrary OpenGlView）。
- **卖点**：优化预览延时、减少预览编码队列。
- **坑**：单作者、star 少、Gitee 生态（issue/CI 检索弱）；JitPack 坐标写法特殊（**artifactId 是模块名、version 是 tag 名**）：`implementation "com.gitee.yunianvh:usb-camera:libusbcamera-v1.2.7"`；实测用 `1.2.7` 作 version 会报 `Tag or commit not found`。

### 2.7 poorld/UVCCamera（V4L2 原生路线，与 UVC 主流完全不同）

- **URL**：https://github.com/poorld/UVCCamera｜3 star；[commits atom](https://github.com/poorld/UVCCamera/commits/main.atom) 最新 2025-09-13，tag V1.0。仓库根**无 LICENSE 文件** → 授权不明确。
- **技术架构（重点差异）**：**C++ + V4L2 直接与 USB 摄像头通信**（改造自 Bitbucket 的 SimpleWebCam），JNI 调用，**与 libuvc/libusb 体系无关**；[uvccamlib/build.gradle](https://raw.githubusercontent.com/poorld/UVCCamera/main/uvccamlib/build.gradle) 用 **CMake 3.22.1 + compileSdk 35 + minSdk 29**，**必须 NDK 自编译**。
- **功能**：只有一个 `com.yuy.uvccamlib.CameraPreview` 控件 + `startPreview()/stopPreview()/setPreviewRotated()/setAutoStartPreview()`。**没有**多路、没有格式选择、没有拍照/录像 API（要自己在预览回调里实现）。
- **风险**：V4L2 在 Android 上依赖 `/dev/video*` 与内核 UVC 驱动的暴露方式，**部分 ROM 上根本拿不到节点**；作者自述基于 SimpleWebCam 改造，star/issue 极少，遇到问题无人可问。**不建议用于「预览 + 拍照/录制」这一需求**。

### 2.8 shlllshlll/USBCamera（已归档的完整 App 参考）

- **URL**：https://github.com/shlllshlll/USBCamera｜44 star / 11 fork，仓库 **archived**；[commits atom](https://github.com/shlllshlll/USBCamera/commits/v2.atom) 最新 2020-04-19，tag v2.0.7。**License MIT**（[LICENSE](https://raw.githubusercontent.com/shlllshlll/USBCamera/master/LICENSE)）。
- **价值**：**兼容性表格**值得收藏——列了红米/小米/荣耀/华为/一加/VIVO/OPPO 十余款机型在 Android 6~10 的实测结果，并记录两类失败：**华为 MateS 调 SO 库报错**、**VIVO X21i 打开 OTG 后能枚举分辨率但无画面**（[README](https://raw.githubusercontent.com/shlllshlll/USBCamera/master/README.md) 第 98-116 行）。
- **风控/坑**：README 明示「**目标 SDK 不能超过 27**，否则无法正确获取 USB 摄像头权限（Android 9 API 特性变更）」（同文件第 142 行）——这正是 §2.3 FAQ 那条「降到 28 以下」的原始出处。该项目还列出它修掉的 saki 侧问题（拔插崩溃、JNI 内存泄漏、新版 NDK ABI 变动导致编译失败）。
- **集成**：无 Maven 坐标，`libusbcamera` 源码引入，依赖 `com.serenegiant:common:4.1.1`（AndroidX）或 2.12.4（AppCompat），且需要 `local.properties` 里的 `ndk.dir` 才能执行 `ndkBuild` task。

### 2.9 libuvc/libuvc（底层 C 库，非 Android SDK）

- 1165 star；[commits atom](https://github.com/libuvc/libuvc/commits/master.atom) 最新 2026-10-01（13 天前），tag v0.0.8（2026-09-13）。本项目所有 Android UVC 库的 native 层都源于它。
- **License**：GitHub 页面识别为 NOASSERTION（非 SPDX 标准许可）→ **若要自编 native，务必逐文件核对（历史上 libuvc 为 LGPL/BSD 混合，libusb 为 LGPL-2.1，libjpeg-turbo 为 IJG/BSD）**。
- **Android 关键参数**（[README](https://raw.githubusercontent.com/libuvc/libuvc/master/README.md)）：`LIBUVC_PACKETS_PER_TRANSFER_MAX` 默认 32，README **明确指出 Android 上常见 `submiturb failed, errno=12 (ENOMEM)`，需要把它调小（如 8）**；`LIBUVC_NUM_TRANSFER_BUFS` 默认 100（macOS 20）。这是「采集卡在高带宽模式下打不开流」的第一手依据。

---

## 3. 技术横评（对照 8 个关注点）

| 维度 | ernestp fork | shiyinghan UVCAndroid | jiangdongguo 原项目 | saki 原版 | Liuguihong | yunianvh | poorld |
|---|---|---|---|---|---|---|---|
| 预编译 .so，免 NDK | 是（libnative 4 ABI） | 是（UVCAndroid.aar 4 ABI） | 是（libnative 4 ABI） | 否，必须自编 | 源码内 | 是（AAR） | 否，CMake 自编 |
| 是否依赖 AndroidUSBCamera native 包 | 自带 | 自带 libuvccamera | 自带 | 自身即源头 | 依赖 saki | 精简自 saki | 完全无关（V4L2） |
| 最低 API | 23 | 21 | 19 | 12（约 18） | — | — | 29 |
| Kotlin / Java | Kotlin | Java | Kotlin | Java | Java | Java | Java |
| 多路摄像头 | 是 | 是 | 是 | 可（示例） | 否 | 有限 | 否 |
| 热插拔 | 是，回调齐全 | 是 | 是（3.2.7+ 优化 ANR） | 部分，拔插崩溃 | 是 | 是 | 手动 |
| 分辨率/格式选择 | 是，枚举+切换，MJPEG 推荐 | 是 | 是 | 是 | 是 | 是 | 否 |
| 拍照 | 是（jpg） | 是 | 是 | 是（示例自实现） | 是 | 需自实现 | 否 |
| 录像 | 是（mp4/h264/yuv） | 库内录制类 | 是 | 是（示例） | 否 | 是（mp4） | 否 |
| 采集卡专门支持 | 通用 UVC；有「采集卡无音频」issue | 通用 UVC；超高分辨率录制失败 / 插拔泄漏 issue | issue 存量多 | — | — | — | — |
| 集成成本 | 极低（JitPack 一行） | 极低（MavenCentral 一行） | 低（JitPack 一行） | 高 | 高（坐标失效） | 低（JitPack） | 高 |

---

## 4. 采集卡（MS2109 等 UVC 免驱 HDMI 采集棒）专题

1. **协议层可行**：MS2109 类采集棒就是标准 **UVC（Video Class）设备**；AOSP 确认 Android 平台可通过标准 Camera2/HAL 或应用直连 USB 访问 UVC（[AOSP External USB cameras](https://source.android.com/docs/core/camera/external-usb-cameras)）。上表所有库都走「应用直连 USB」，**不依赖 OEM 的 external camera provider**，因此不受机型是否内置 UVC 支持影响。
2. **实测正例**：ernestp issue #24 即 HDMI 采集场景，「画面能用、只是没声音」（[#24](https://github.com/ernestp/AndroidUSBCamera/issues/24)）。采集棒通常是 **UVC 视频 + UAC 音频**两个接口，音频不工作属预期内常见问题。
3. **实测负例 / 风险点**：
   - **ENOMEM 打不开流**：libuvc README 点名 Android 上的 `submiturb failed, errno=12`，需调小 `LIBUVC_PACKETS_PER_TRANSFER_MAX`（[来源](https://raw.githubusercontent.com/libuvc/libuvc/master/README.md)）。高分辨率 MJPEG 采集卡最易触发。
   - **超规格分辨率无法录制**：shiyinghan [#150](https://github.com/shiyinghan/UVCAndroid/issues/150)。
   - **反复插拔泄漏**：shiyinghan [#147](https://github.com/shiyinghan/UVCAndroid/issues/147) → 采集棒频繁拔插的长跑场景要自己加重建/看门狗逻辑。
   - **设备枚举过滤**：部分采集棒不报 `class=239 subclass=2`，需在 `default_device_filter.xml` 补 VID/PID（ernestp FAQ 第 2 条已给兜底清单）。
   - **国产 ROM 的 OTG 开关**：VIVO/OPPO 需在设置里手动打开 OTG（[shlllshlll 兼容表](https://raw.githubusercontent.com/shlllshlll/USBCamera/master/README.md) 第 114-116 行），否则枚举到了也没画面。
   - **华为/鸿蒙**：shiyinghan [#151](https://github.com/shiyinghan/UVCAndroid/issues/151) 报告鸿蒙 4.0/4.2 异常，华为机型需单独验证。
4. **没有找到**「MS2109 在这些库上有系统性不兼容」的证据；已知问题都落在**音频、超高分辨率、热插拔长跑、个别 ROM** 这四类，且均有 workaround。

---

## 5. 各候选 README 给出的最小集成片段

### 5.1 ernestp/AndroidUSBCamera（Kotlin）

```groovy
// settings.gradle
maven { url "https://jitpack.io" }
// app/build.gradle
implementation 'com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0'
```

```kotlin
class DemoFragment : CameraFragment() {
    private var vb: FragmentUvcBinding? = null
    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View? {
        if (vb == null) vb = FragmentUvcBinding.inflate(inflater, container, false)
        return vb?.root
    }
    override fun getCameraView(): IAspectRatio? = vb?.tvCameraRender   // 离屏渲染则 return null
    override fun getCameraViewContainer(): ViewGroup? = vb?.container
    override fun onCameraState(self: ICamera, code: ICameraStateCallBack.State, msg: String?) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> handleCameraOpened()
            ICameraStateCallBack.State.CLOSED -> handleCameraClosed()
            ICameraStateCallBack.State.ERROR  -> handleCameraError()
        }
    }
    override fun getGravity(): Int = Gravity.TOP
    // 可选：自定义请求
    override fun getCameraRequest() = CameraRequest.Builder()
        .setPreviewWidth(1280).setPreviewHeight(720)
        .setRenderMode(CameraRequest.RenderMode.OPENGL)
        .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_YUYV) // README 注：MJPEG 推荐
        .create()
}
// 拍照 / 录像
captureImage(callBack, savePath); captureVideoStart(callBack, path, durationInSec); captureVideoStop()
```

（来源：[README](https://raw.githubusercontent.com/ernestp/AndroidUSBCamera/master/README.md) 第 51-160 行）

### 5.2 shiyinghan/UVCAndroid（Java）

```groovy
dependencies { implementation 'com.herohan:UVCAndroid:1.0.13' }
// ProGuard（非 R8）
-keep class com.herohan.uvcapp.** { *; }
-keep class com.serenegiant.usb.** { *; }
-keepclassmembers class * implements com.serenegiant.usb.IButtonCallback {*;}
-keepclassmembers class * implements com.serenegiant.usb.IFrameCallback {*;}
-keepclassmembers class * implements com.serenegiant.usb.IStatusCallback {*;}
```

（来源：[README](https://raw.githubusercontent.com/shiyinghan/UVCAndroid/master/README.md)；示例见 [app/build.gradle](https://raw.githubusercontent.com/shiyinghan/UVCAndroid/master/app/build.gradle)）

### 5.3 jiangdongguo/AndroidUSBCamera

写法同 5.1，依赖换成 `implementation 'com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.3.3'`；多路用 `MultiCameraFragment`（`onCameraAttached/onCameraConnected/onCameraDisConnected/onCameraDetached`，`isAutoRequestPermission() = false` 时自行 `requestPermission(device)`）。（来源：[README](https://raw.githubusercontent.com/jiangdongguo/AndroidUSBCamera/master/README.md) 第 217-261 行）

### 5.4 saki4510t/UVCCamera

无坐标，源码/类库引入；README 给两条编译路径：`gradle build`（需 `local.properties` 中 `sdk.dir`/`ndk.dir`）或 `ndk-build`（产物在 `libuvccamera/build/src/main/libs`）。（来源：[README](https://raw.githubusercontent.com/saki4510t/UVCCamera/master/README.md) 第 24-64 行）

### 5.5 Liuguihong/AndroidUVCCamera（供参考，坐标已失效）

```java
UVCCameraProxy mUVCCamera = new UVCCameraProxy(this);
mUVCCamera.setPreviewTexture(mTextureView);
mUVCCamera.setConnectCallback(new ConnectCallback() {
    public void onAttached(UsbDevice d) { mUVCCamera.requestPermission(d); }
    public void onGranted(UsbDevice d, boolean granted) { if (granted) mUVCCamera.connectDevice(d); }
    public void onConnected(UsbDevice d) { mUVCCamera.openCamera(); }
    public void onCameraOpened() { mUVCCamera.setPreviewSize(640, 480); mUVCCamera.startPreview(); }
    public void onDetached(UsbDevice d) { mUVCCamera.closeCamera(); }
});
mUVCCamera.takePicture();
```

（来源：[README](https://raw.githubusercontent.com/Liuguihong/AndroidUVCCamera/master/README.md)）

### 5.6 yunianvh/usb-camera（Gitee）

```groovy
implementation "com.gitee.yunianvh:usb-camera:libusbcamera-v1.2.7"
```

```java
USBCameraHelper.getInstance().setPreviewSize(1920, 1080, 1024 * 1024 * 5, 0);
USBCameraHelper.getInstance().startPreview();     // / stopPreview()
USBCameraHelper.getInstance().startRecord(path);  // / stopRecord() / isRecording()
```

（来源：[Gitee README](https://gitee.com/yunianvh/usb-camera/raw/master/README.md)）

### 5.7 poorld/UVCCamera

```xml
<com.yuy.uvccamlib.CameraPreview
    android:id="@+id/camera_preview"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

```java
cameraPreview.setPreviewRotated(true);
cameraPreview.setAutoStartPreview(true);  // 或手动 startPreview()/stopPreview()
// onPause 里必须 stopPreview() 释放资源
```

编译 AAR：`gradlew :uvccamlib:assembleRelease`。（来源：[README](https://raw.githubusercontent.com/poorld/UVCCamera/master/README.md)）

---

## 6. A) 「手机无 OTG / 供电不足」属于软件范畴吗？

**不属于。这是纯硬件供电与 USB 角色问题，任何开源库都改不了。**

1. **角色限制**：手机要当 USB Host 必须有 OTG 能力，各库 README 都以此为前提（AndroidUSBCamera 开头即「confirming your Android device must support OTG function」）；AOSP 也把 `android.hardware.usb.host` 列为实现前提（[AOSP](https://source.android.com/docs/core/camera/external-usb-cameras)）。
2. **供电限制**：手机作为 host 需给外设供 VBUS，而手机 OTG 口通常只能提供数百 mA；**HDMI 采集棒 + 摄像头很容易超出**，表现是「能枚举但打不开流 / 反复掉线 / 黑屏卡顿」。
3. **软件侧能做与不能做**：
   - **不能**：通过 SDK/库提高 VBUS 供电上限、绕过 OTG 检测。
   - **能（仅为规避与容错）**：用**带外部供电的 USB Hub** 或 **Y 型 OTG 供电线**给外设独立供电；用**低带宽模式**（MJPEG 而非 YUYV、降分辨率/帧率）降低瞬时电流与 USB 带宽压力；调小 `LIBUVC_PACKETS_PER_TRANSFER_MAX` 避免 ENOMEM；对掉线做热插拔重连。
4. **结论**：把「供电 hub / Y 型线」写进方案 BOM，不要寄望于库。软件只能「降低功耗与带宽」并「提高掉线容错」。

---

## 7. 核心结论：CameraX / Camera2 原生支持外接 UVC 吗？

**结论：CameraX 本身不「自带」USB UVC 支持；Camera2 有 EXTERNAL 通路，但属 OEM 可选项，绝大多数手机未开启。**

1. **AOSP**：平台支持「通过标准 camera2 API + camera HAL 使用 USB 摄像头」，但需设备侧显式打开 **external camera provider**（内核 `CONFIG_USB_VIDEO_CLASS`、`PRODUCT_PACKAGES += android.hardware.camera.provider-V1-external-service`、Treble HAL manifest 加 `external/0`），且「Java 客户端 API 从 **API 28** 起才有 EXTERNAL hardware level」。（[AOSP External USB cameras](https://source.android.com/docs/core/camera/external-usb-cameras)）
2. **CDD（兼容性定义）**：外部摄像头是 **MAY** ——「Device implementations **MAY** include support for an external camera … If device implementations include support …, they MUST declare `android.hardware.camera.external`」。（[Android 16 CDD §7.5.3 External Camera](https://source.android.com/docs/compatibility/16/android-16-cdd)）→ **厂商可以完全不支持**。
3. **Camera2 API 文档**：`INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL`（Added in API level 28）存在，能力「**identical to a LIMITED level device**」，但可能不上报镜头/传感器信息、`sensor.orientation` 报 0、帧率稳定性无保证。（[CameraMetadata](https://developer.android.com/reference/android/hardware/camera2/CameraMetadata)）
4. **CameraX 文档**：明确写「**每个 OEM 自行决定是否支持外接摄像头设备**，因此在尝试使用外接摄像头之前，请先确认 `PackageManager.FEATURE_CAMERA_EXTERNAL` 已启用」，并给出按硬件等级排序选 EXTERNAL 摄像头的 `CameraSelector` 示例。（[CameraX 配置](https://developer.android.com/media/camera/camerax/configuration)）
5. **实践层面**：正因多数手机没开 external camera provider，社区才反复出现「USB 摄像头能被 UsbManager 看到，但 CameraX 相机列表里没有它」。（StackOverflow：[connecting USB UVC camera with CameraX](https://stackoverflow.com/questions/75169761/connecting-usb-uvc-camera-with-camerax)）

→ **因此「手机 OTG 外接 USB 摄像头 / 采集卡并把画面显示到屏幕」在通用设备上只能走「应用直连 USB + 自备 UVC 协议栈」（即上表这些库）。** 这也解释了为什么它们全都内置 native .so。

---

## 8. B) 推荐结论：最小可行方案排名（预览 + 简单拍照/录制）

| 排名 | 方案 | 为什么 | 代价 |
|---|---|---|---|
| **1** | **ernestp/AndroidUSBCamera（libausbc 3.6.0，JitPack）** | ① 一行依赖拿到**预编译 4 ABI .so**，零 NDK；② 继承 `CameraFragment`/`CameraActivity` 即得预览，拍照/录像有现成 API；③ **仍活跃**（2026-05 有提交与发版），README 自述兼容 Android 16 / 16K page；④ Apache-2.0 可商用；⑤ 恰好覆盖「预览 + 简单拍照/录制」全部需求 | 仓库 star 少（35），社区资料不如上游；依赖 JitPack 第三方构建 |
| **2** | **shiyinghan/UVCAndroid（com.herohan:UVCAndroid 1.0.13，Maven Central）** | ① **Maven Central** 依赖，企业内网/CI 更友好；② 470 star、2026-06 仍在提交，**最近仍活跃**；③ 预编译 4 ABI；④ Apache-2.0；⑤ Java 代码库、中文资料多 | 拍照/录像 API 不如 AUSBC 现成（录像多靠自己接 MediaCodec）；已知插拔泄漏与超分辨率录制问题 |
| 3 | jiangdongguo/AndroidUSBCamera 3.3.3 | star 最多、教程最多、功能同第 1 名（fork 的上游） | **2024-09 后无提交、2023-02 后无 release**；AGP 4.2.2 + jcenter 已死；库清单 targetSdk 27；x86 自 3.1.7 起取消 |
| 4 | yunianvh/usb-camera（Gitee） | MIT、低延时取向、AAR 即取即用 | 单作者、star 少、坐标写法特殊、生态弱 |
| 5 | saki4510t/UVCCamera | 鼻祖，想彻底自己掌控时的底料 | **2018 年后停更**；必须自编 NDK；子目录许可不同需逐文件核对；拔插崩溃/内存泄漏需自己修 |
| 参考 | Liuguihong / poorld / shlllshlll | 只建议当**参考实现与兼容性资料** | 前两者许可不明或坐标失效；后者 archived 且「targetSdk 不得超过 27」 |
| ✗ | CameraX / Camera2 EXTERNAL | — | OEM 可选，多数手机没开（§7） |

**只选一个：选 1。若环境禁止 JitPack（只能走 Maven Central）：选 2。**

---

## 9. 关键风险清单（按严重度）

1. **【兼容性/最高】CameraX·Camera2 路线基本不可用**：external camera 是 CDD 的 MAY 项，多数手机没开（[CDD §7.5.3](https://source.android.com/docs/compatibility/16/android-16-cdd)、[AOSP](https://source.android.com/docs/core/camera/external-usb-cameras)）。不要在这上面排期。
2. **【法务/高】许可分层**：上层库是 Apache-2.0 可商用（ernestp / shiyinghan / jiangdongguo / saki 主目录），但 **native 子目录（libjpeg-turbo / libusb / libuvc）许可不同**——saki README 明确提示逐文件核对（[来源](https://raw.githubusercontent.com/saki4510t/UVCCamera/master/README.md)），libuvc 在 GitHub 上是 NOASSERTION。**自编 native 或做合规审查时这步不能省**；走 AAR 依赖也建议在 NOTICE 中保留上游许可声明。
3. **【法务/高】Liuguihong 与 poorld 仓库根无 LICENSE 文件**：商用前必须取得作者书面授权。
4. **【工程/高】targetSdk 与权限**：
   - 老库（shlllshlll 及其文档链条）要求 **targetSdk ≤ 27** 才能拿 USB 权限——**在现代上架语境下不可行**，是这些库的根本性历史包袱；只有新 fork（ernestp targetSdk 36）解掉了。
   - Android 12+：所有 PendingIntent 必须显式声明可变性（USB 授权广播用 `FLAG_IMMUTABLE`，官方示例如此，见 [USB host 指南](https://developer.android.com/develop/connectivity/usb/host)、[Android 12 行为变更](https://developer.android.com/about/versions/12/behavior-changes-12)）——老代码不补会直接崩。
   - Android 14+：前台服务必须声明 `foregroundServiceType`，且**不能在后台启动**（[FGS 类型要求](https://developer.android.com/about/versions/14/changes/fgs-types-required)）。想要「锁屏/后台持续预览或录像」，必须自己写带 `camera`/`mediaProjection` 类型的前台服务，**这些库都不替你处理**。
   - shiyinghan 的 AAR 已声明 `MANAGE_EXTERNAL_STORAGE` / `READ|WRITE_EXTERNAL_STORAGE` / `FOREGROUND_SERVICE`（实测清单），**上架 Google Play 会因 MANAGE_EXTERNAL_STORAGE 触发额外审核**，建议在 app 侧用 `tools:node="remove"` 剔除不需要的项。
5. **【工程/中】native 与 ABI**：AAR 已含 4 个 ABI，**但 APK 会因此变大**；上架通常只保留 `arm64-v8a`（`abiFilters` / ABI splits）。jiangdongguo 3.1.7+ 取消 x86，模拟器调试会 `UnsatisfiedLinkError`。
6. **【稳定性/中】热插拔长跑**：ANR（FAQ 称 3.2.7+ 优化）、MediaCodec 泄漏（[shiyinghan #147](https://github.com/shiyinghan/UVCAndroid/issues/147)）→ 现场设备频繁拔插需「看门狗 + 重建」逻辑。
7. **【硬件/中】供电与 OTG**：见 §6，必须用外供电 hub / Y 线，软件无解。
8. **【兼容/中】国产 ROM 与华为**：VIVO/OPPO 要手动开 OTG；华为/鸿蒙有 open issue（[shiyinghan #151](https://github.com/shiyinghan/UVCAndroid/issues/151)）；VIVO X21i 曾有「能枚举无画面」记录（[shlllshlll 兼容表](https://raw.githubusercontent.com/shlllshlll/USBCamera/master/README.md)）。
9. **【构建/低】依赖源**：老项目仍引用 **jcenter()**（已关停），源码引入必须先迁移到 `mavenCentral()`（jiangdongguo / saki / shlllshlll / Liuguihong 均有此问题）。

---

## 10. 取证方式与实测记录（可复核）

- GitHub 仓库页在直连与代理（127.0.0.1:7897）下均可取，但 **GitHub REST API 返回 403（共享出口 IP 触发限流）**；因此 star / 提交时间改用三种同样可靠的替代源：① 仓库页内嵌 JSON 的 `stargazerCount`/`forksCount`/`watcherCount`；② `/commits/<branch>.atom` 的 `<updated>`；③ `/tags`、`/releases` 页面解析。
- 依赖坐标通过 **JitPack 构建记录 API** 与 **Maven Central POM** 双向核实（Liuguihong 的「坐标失效」结论即由此得出）。
- 能力判断通过**实际下载并解包 AAR**（自写 ZIP 解析）确认 native 库与 minSdk，而非只看 README。
- 官方文档来源：AOSP External USB cameras、Android 16 CDD、Camera2 CameraMetadata、CameraX Configuration、USB host 指南、Android 12/14 行为变更（链接见正文）。
