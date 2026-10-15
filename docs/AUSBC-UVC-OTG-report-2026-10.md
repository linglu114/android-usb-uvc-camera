# Android OTG 外接 UVC 摄像头 / HDMI 采集卡 —— 框架选型与落地调研报告

> 调研日期：**2026-10-14**（Asia/Shanghai）
> 调研对象：jiangdongguo/AndroidUSBCamera（AUSBC）及其生态
> 证据分级：**[实测]** = 本轮直接下载 AAR / 源码文件并解析得出；**[官方]** = Android/AOSP 官方文档；**[仓库]** = GitHub 页面/源码；**[issue]** = 社区 issue。
> 所有结论均附来源 URL。凡无法证实的，明确标注为"未证实"。

---

## 0. 结论摘要（10 行）

1. **上游 AUSBC 已实质停更**：`jiangdongguo/AndroidUSBCamera` master 最后一次 commit 是 **2024-09-02**，最后一个 tag/release 是 **3.3.3（2023-02-13）**；issue 区仍活跃（最新 #758 创建于 2026-09-02），但维护者不再回应，社区已在 issue 里自荐替代品（#729）。**[仓库]**
2. **JitPack 上 3.2.7 是唯一"依赖闭包完整"的版本**：3.3.0/3.3.1/3.3.2/3.3.3/3.2.9 的 `libuvc` 制品全部 404（JitPack 构建日志：3.3.3 = `No build artifacts found`）；3.2.8/3.2.10 也缺 `libuvc`。**[实测]**
3. **3.2.7 不满足 Google Play「16 KB 页大小」要求**：实测 `libuvc-3.2.7.aar` / `libnative-3.2.7.aar` 的 **arm64-v8a 全部 5 个 `.so` 的 PT_LOAD `p_align=4096`**（要求 16384）。**[实测]** + **[官方]**
4. **3.2.7 在 targetSdk≥34 上会崩**：`USBMonitor.register()` 的动态广播注册 **没有传 `RECEIVER_NOT_EXPORTED`**（#651 / #667 / #722）。`FLAG_IMMUTABLE` 这一项 3.2.7 已自行修好。**[实测]** + **[issue]**
5. **建议改用活跃 fork：`ernestp/AndroidUSBCamera` 3.6.0**（JitPack `com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0`，Apache-2.0，最后提交 2026-05-09，tag 3.6.0 同日）。实测其 **arm64-v8a 与 x86_64 的 `.so` 全部 `p_align=16384` ✔**，并已修复 `RECEIVER_NOT_EXPORTED`。**[实测]** + **[仓库]**
6. **3.6.0 的依赖闭包干净**：只剩 `xlog` + `libnative`(api) + `libuvc`(runtime) + kotlin-stdlib 2.2.10 + 3 个 androidx；**已剔除** immersionbar / glide / mmkv / webpdecoder / libpush（正是 #684 / #711 报错的来源）。**[实测]**
7. **备选（只要预览）推荐序**：ernestp 3.6.0 ＞ `com.herohan:UVCAndroid:1.0.13`（Maven Central，2026-06-10，全 ABI 16384 ✔）＞ 上游 3.2.7（仅在必须锁死上游时）＞ saki4510t（2018 停更，需 NDK）＞ Liuguihong（2019 停更，JitPack 1.0.0 = Error）。**[实测]** + **[仓库]**
8. **CameraX / camera2 不能作为主路径**：AOSP 的"外部 USB 相机"能力依赖**设备侧 camera HAL 的 external provider**，属 OEM 可选项；普通手机上 CameraX 枚举不到 OTG 摄像头。**[官方]** + **[社区]**
9. **HDMI 采集卡（MS2109 等）就是标准 UVC 设备**，同一套 API 即可；MS2109 为 USB 2.0 采集芯片，1080p 场景**必须走 MJPEG**，YUYV 会被带宽打死（绿屏/卡顿/丢帧）。**[实测建议]** + **[厂商]**
10. **两处必须自己写的代码**：Android 14+ 若要在后台维持采集，需自建 `foregroundServiceType="connectedDevice"` 前台服务；以及实现 `IDeviceConnectCallBack` 时必须显式添加 `libuvc` 依赖（上游 POM 把它标成 `runtime`，编译期不可见 —— #756）。**[官方]** + **[issue]**

---

## 1. AUSBC 维护活跃度（问题 1）

### 1.1 冷数据：代码早已停更

| 指标 | 值 | 来源 |
|---|---|---|
| master 最后一次 commit | **2024-09-02T15:43:31+08:00**（作者 jiangdongguo） | [commits/master](https://github.com/jiangdongguo/AndroidUSBCamera/commits/master) |
| 最后一次 commit 之前 | 2024-06-10 / 2024-03-11 / 2024-03-10 / 2024-01-17 / 2023-12-05 | 同上 |
| 最新 tag | **3.3.3 = 2023-02-13**（其后 3.3.2 2023-02-11、3.3.1 2023-02-11、3.3.0 2023-02-11、3.2.10 2022-10-10） | [/tags](https://github.com/jiangdongguo/AndroidUSBCamera/tags) |
| 最新 release | **3.3.3 = 2023-02-13T07:35** | [/releases](https://github.com/jiangdongguo/AndroidUSBCamera/releases) |
| open issues | 约 **492**（抓取时 issue 列表页计数） | [/issues](https://github.com/jiangdongguo/AndroidUSBCamera/issues) |
| 全部 issues（含已关） | **723**，29 页 | [/issues?q=is:issue&sort=updated-desc](https://github.com/jiangdongguo/AndroidUSBCamera/issues?q=is%3Aissue+sort%3Aupdated-desc) |

### 1.2 热数据：issue 区仍在动，但"只有社区，没有维护者"

按"最近更新"排序的前 12 条（抓取于 2026-10-14）：

| # | 状态 | 创建 | 最近更新 | 标题 |
|---|---|---|---|---|
| 758 | OPEN | 2026-09-02 | 2026-09-02 | 切换裁切后的分辨率导致闪退 2400 x 1350 |
| 729 | OPEN | 2025-06-21 | 2026-08-03 | 大佬，这个库还维护吗？我们的人脸识别SDK依赖发现新系统不少问题 |
| 757 | OPEN | 2026-07-28 | 2026-07-28 | 根本就依赖不上 |
| 756 | OPEN | 2026-06-27 | 2026-06-27 | Bug: libuvc listed as 'runtime' scope in libausbc POM… |
| 755 | OPEN | 2026-06-22 | 2026-06-22 | Fix crash on rapid reconnect. |
| 754 | OPEN | 2026-05-09 | 2026-05-09 | 最新版本，captureImage，拍照无回调问题 |
| 552 | OPEN | 2023-04-10 | 2026-04-30 | No preview after accepting USB permissions |
| 735 | CLOSED | 2025-07-18 | 2026-03-24 | Support 16 KB page sizes - Google Play compatibility requirement |
| 501 | OPEN | 2022-11-30 | 2026-03-15 | unsupported preview size |
| 744/745/746 | OPEN | 2025-09-24 | 2026-03-14 | Support 16 KB Size / 16 kb issue in google play console / 16KB Page Size Support… |
| 732 | OPEN | 2025-07-13 | 2025-11-26 | Android 14, 15, Camera on but display bank, fps 1 |

**结论（明确判定）**：
- **代码层面：停滞**。自 2024-09 起 25 个月零提交，自 2023-02 起 43 个月零发版。issue #729 的直接答复就是社区劝退："**貌似不维护了，用这个吧 https://github.com/shiyinghan/UVCAndroid**"。
- **issue 层面：活跃，但是"单向活跃"**——新问题持续产生（2026-09 仍有 #758），16KB、依赖、Android 14/15 崩溃类问题集中在 2025-09～2026-06 爆发，**没有任何维护者回复或修复**。
- issue 里 16KB 相关 #735 被 CLOSED（2026-03-24），但那更像"关闭而非修复"——因为 **2025-09 又有 #744/#745/#746 三连**（同样内容，仍 OPEN），且 **JitPack 3.3.3 之后再无任何新版本**（[JitPack builds](https://jitpack.io/api/builds/com.github.jiangdongguo/AndroidUSBCamera)：3.3.3 之后无 3.3.4/3.4.x）。

---

## 2. 版本可用性与 Android 12/13/14/15/16 兼容性（问题 2）

### 2.1 先解决"到底该用哪个版本"：JitPack 制品实测

对 JitPack 上每个版本的 4 个模块逐一发 HEAD 请求（`https://jitpack.io/com/github/jiangdongguo/AndroidUSBCamera/<模块>/<版本>/<模块>-<版本>.aar`）：**[实测]**

| 版本 | libausbc | libuvc | libnative | libpush | 可用性 |
|---|---|---|---|---|---|
| 3.3.3 | 200 | **404** | **404** | **404** | ❌ 依赖闭包残缺 |
| 3.3.2 | 404 | 404 | 404 | 404 | ❌ |
| 3.3.1 | 404 | 404 | 404 | 404 | ❌ |
| 3.3.0 | 404 | 404 | 404 | 404 | ❌ |
| 3.2.10 | 200 | **404** | 200 | 200 | ❌ 缺 libuvc |
| 3.2.9 | 404 | 404 | 404 | 404 | ❌ |
| 3.2.8 | 200 | **404** | 200 | 200 | ❌ 缺 libuvc |
| **3.2.7** | **200** | **200** | **200** | **200** | ✅ **唯一完整** |

JitPack 官方构建日志印证：
```json
{"version":"3.3.3","status":"Error","message":"No build artifacts found. See the log: ...","isTag":true}
```
来源：`https://jitpack.io/api/builds/com.github.jiangdongguo/AndroidUSBCamera/3.3.3`

> **重要更正**：团队以为"3.2.7 是可选版本之一"，实际它是 **JitPack 上唯一能解析成功的版本**（至少 3.2.7~3.3.3 区间内）。这既是"只能用 3.2.7"的理由，也是"应尽快迁移到 3.6.0"的理由。

### 2.2 USB 设备权限（UsbManager.requestPermission + FLAG_IMMUTABLE）

**已修复项**：[实测] 读 `libuvc/src/main/java/com/serenegiant/usb/USBMonitor.java` @ tag 3.2.7 第 170-184 行：

```java
@SuppressLint("UnspecifiedImmutableFlag")
public synchronized void register() throws IllegalStateException {
    ...
    if (Build.VERSION.SDK_INT >= 31) {
        mPermissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
    } else {
        mPermissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), 0);
    }
    final IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
    filter.addAction(ACTION_USB_DEVICE_ATTACHED);
    filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    context.registerReceiver(mUsbReceiver, filter);   // ← 注意这里
}
```
来源：[USBMonitor.java @3.2.7](https://raw.githubusercontent.com/jiangdongguo/AndroidUSBCamera/3.2.7/libuvc/src/main/java/com/serenegiant/usb/USBMonitor.java)

→ **`FLAG_IMMUTABLE` 在 3.2.7 已存在（SDK≥31 分支）**，所以"必须加 FLAG_IMMUTABLE"这一条不是 3.2.7 的坑。

**未修复项（真正的坑）**：同一处 `registerReceiver(mUsbReceiver, filter)` **没有三参数重载、没有 `RECEIVER_NOT_EXPORTED`**。

- 官方要求：Android 12 起 targetSdk 31 必须显式声明 PendingIntent 可变性 —— [behavior-changes-12](https://developer.android.com/about/versions/12/behavior-changes-12)（"如果应用以 Android 12 为目标平台，必须为创建的每个 PendingIntent 对象指定可变性"）。
- 社区证据（指向同一行代码）：
  - [#651](https://github.com/jiangdongguo/AndroidUSBCamera/issues/651)（2024-03-21）："**please set RECEIVER_NOT_EXPORTED or RECEIVER_EXPORTED in USBMonitor.java:181**"
  - [#667](https://github.com/jiangdongguo/AndroidUSBCamera/issues/667)（2024-06-01）：Android 14 / targetSdk 34 崩溃 `java.lang.IllegalArgumentException: Targeting U+ (version 34 and above) disallows creating or retrieving a PendingIntent with FLAG_MUTABLE, an implicit Intent within…`
  - [#722](https://github.com/jiangdongguo/AndroidUSBCamera/issues/722)（2025-03-06）：`New mutable implicit PendingIntent: pkg=…, action=com.serenegiant.USB_PERMISSION.88345595 … This will be blocked once the app targets U+ for security reasons`
  - [#638](https://github.com/jiangdongguo/AndroidUSBCamera/issues/638)（2024-01）：Pixel 7 / Redmi 10 prime 在 Android 13/14 上只有黑屏

**ernestp 3.6.0 的修复**（[实测] 源码对比）：
```java
// libuvc/src/main/java/com/jiangdg/usb/USBMonitor.java  (ernestp 3.6.0)
int flags = PendingIntent.FLAG_UPDATE_CURRENT;
// Android 14+ detected, using FLAG_IMMUTABLE
flags |= PendingIntent.FLAG_IMMUTABLE;                 // L341-342
flags |= PendingIntent.FLAG_MUTABLE;                   // L345-346 (Android 12+ 分支)
mPermissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags);   // L350
...
int RECEIVER_NOT_EXPORTED = 4;
context.registerReceiver(mUsbReceiver, filter, RECEIVER_NOT_EXPORTED);       // L377-378
```
来源：[ernestp USBMonitor.java @master](https://raw.githubusercontent.com/ernestp/AndroidUSBCamera/master/libuvc/src/main/java/com/jiangdg/usb/USBMonitor.java)

**⚠️ 包名不兼容**：3.2.7 是 `com.serenegiant.usb.USBMonitor`；ernestp 3.6.0 改成了 `com.jiangdg.usb.USBMonitor`（对应 `IDeviceConnectCallBack.onConnectDev(device, ctrlBlock: com.jiangdg.usb.USBMonitor.UsbControlBlock?)`）。**迁移时 import 必须改**。

### 2.3 Android 14+ 前台服务类型 connectedDevice

[官方] 自 Android 14（API 34）起，每个前台服务都必须在 manifest 声明类型：**[service-types](https://developer.android.com/develop/background-work/services/fgs/service-types)**

| 项 | 值 |
|---|---|
| manifest 声明 | `android:foregroundServiceType="connectedDevice"` |
| 需声明权限 | `FOREGROUND_SERVICE_CONNECTED_DEVICE` |
| `startForeground()` 常量 | `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` |
| 运行时前置条件 | "At least one of the following conditions must be true: Declare at least one of the following permissions…"（该节在页面上动态渲染，**具体权限列表未能在静态 HTML 中完整取得，编码前请以该页当前内容为准**） |

**对项目的直接影响**：AUSBC **本身不提供 Service**。因此：
- 只要 App 保持前台（Activity 可见）做预览 → **不需要**前台服务，不受此变更影响。
- 一旦要"后台继续录制/推流" → 必须自建前台服务并声明上述类型+权限，否则 Android 14+ 直接抛 `SecurityException` / `MissingForegroundServiceTypeException`。

### 2.4 targetSdk 35/36 编译与 16 KB 页大小（**本项目最大阻塞项**）

**官方要求** [page-sizes](https://developer.android.com/guide/practices/page-sizes)：
- "Beginning with Android 15, AOSP supports devices that are configured to use a page size of 16 KB"
- "If your app uses any NDK libraries… then you will need to **rebuild** your app for it to work on these 16 KB devices."
- "**all apps targeting Android 15 (API level 35) and higher must support 16 KB memory page sizes on 64-bit devices on Google Play**"
- "**Starting February 1, 2027**, if your app updates don't support 16 KB memory page sizes, you won't be able to release these updates."
- "If the app uses only Java/Kotlin code… it already supports 16 KB devices."（即：**纯 Java/Kotlin 没事，带 .so 才出事**）

**实测结论（自主研发的 ELF 解析脚本，读取每个 `.so` 的 PT_LOAD 段 `p_align`）**：

| AAR | ABI | .so | PT_LOAD p_align | 判定 |
|---|---|---|---|---|
| libuvc-3.2.7.aar | arm64-v8a | libUVCCamera.so / libjpeg-turbo1500.so / libusb100.so / libuvc.so | **4096**（各 3 段） | ❌ |
| libnative-3.2.7.aar | arm64-v8a | libnativelib.so | **4096**（2 段） | ❌ |
| libuvc-3.2.7.aar | armeabi-v7a | 同上 4 个 | 4096（32 位，不受约束） | — |
| libnative-3.2.7.aar | armeabi-v7a | libnativelib.so | 4096（32 位） | — |
| **libuvc-3.6.0.aar (ernestp)** | **arm64-v8a** | 4 个 | **16384** | ✅ |
| **libuvc-3.6.0.aar (ernestp)** | **x86_64** | 4 个 | **16384** | ✅ |
| libuvc-3.6.0.aar (ernestp) | armeabi-v7a / x86 | 4 个 | 4096（32 位） | — |
| **libnative-3.6.0.aar (ernestp)** | arm64-v8a / x86_64 / x86 / armeabi-v7a | libnativelib.so | **16384（全部）** | ✅ |
| **UVCAndroid-1.0.13.aar** | arm64-v8a / x86_64 | libUVCCamera / libjpeg-turbo212 / libusb1.0 / libuvc / libyuv | **16384（全部）** | ✅ |

> 注：16 KB 要求**只针对 64 位设备**（官方原文 "on 64-bit devices"），所以 ernestp 3.6.0 的 32 位 .so 仍是 4096 **不构成问题**。

**社区证据**（与实测完全一致）：[#735](https://github.com/jiangdongguo/AndroidUSBCamera/issues/735) / [#744](https://github.com/jiangdongguo/AndroidUSBCamera/issues/744) / [#745](https://github.com/jiangdongguo/AndroidUSBCamera/issues/745) / [#746](https://github.com/jiangdongguo/AndroidUSBCamera/issues/746) 原文列出：
```
Some libraries have LOAD segments not aligned at 16 KB boundaries:
lib/arm64-v8a/libUACAudio.so, libUVCCamera.so, libjpeg-turbo1500.so, libnativelib.so, libusb100.so, libuvc.so
```

**可执行结论**：
- **只要 App 上架 Google Play 且 targetSdk ≥ 35 → 3.2.7 直接不合格**（无论你怎么写业务代码，`.so` 是预编译的）。
- 若不上架 Play（纯内部分发/侧载）：**Android 15 的 16KB 设备上 3.2.7 可能在启动/采集时崩溃**。
- **修复路径二选一**：① 换 ernestp 3.6.0（推荐，已 16384）；② 自己用 NDK r27+ 重编 3.2.7 的 libuvc/libnative（工作量大，且社区已在做：#723、#735 讨论里提到自编译 .so 替换 jniLibs）。

### 2.5 minSdk 19 与 AGP 8.x 的关系

[实测] 3.2.7 的构建配置（tag 3.2.7 / master 均一致）：[build.gradle](https://raw.githubusercontent.com/jiangdongguo/AndroidUSBCamera/master/build.gradle)
```groovy
buildscript {
    repositories { google(); jcenter(); maven { url "https://jitpack.io" } }   // ← jcenter 已关停
    dependencies {
        classpath 'com.android.tools.build:gradle:4.2.2'                      // ← AGP 4.2.2
        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:1.5.20'
    }
}
ext {
    versionCompiler = 29     // compileSdk 29
    versionTarget   = 27     // targetSdk 27
    minSdkVersion   = 19
    versionNameString = '3.3.3'
}
```

关键点：
1. **这是"库自己的构建脚本"，不是你的**。你用 JitPack AAR 时，它不参与你的构建；但 `minSdkVersion` 与依赖坐标会通过 **AAR 的 POM / manifest 向上传播**。
2. **minSdk 19 本身对 AGP 8.x 不是硬错误**：消费方自己的 `minSdk` 若 ≥ 21，AGP 只会提示"依赖的 minSdk 更低"，不会拒绝合并。**本轮未检索到 AGP 8.x 拒绝 minSdk 19 的官方明文**（标注为未证实）。
3. **AGP 8.x 的真正障碍是 jcenter 依赖消失**：
   - [#684](https://github.com/jiangdongguo/AndroidUSBCamera/issues/684)（2024-09）："This library use dependencies like `com.gyf.immersionbar:immersionbar:3.0.0`、`com.zlc.glide:webpdecoder:1.6.4.9.0` that are located in JCenter repository, AndroidUSBCamera **cannot be used in Android project with gradle 8.0 and above**"
   - [#711](https://github.com/jiangdongguo/AndroidUSBCamera/issues/711)（2024-12）：`Could not GET 'https://jitpack.io/com/zlc/glide/webpdecoder/1.6.4.9.0/webpdecoder-…pom'. Received status code 401: Unauthorized`
   - 这两条正是 3.2.7 POM 里那串 `runtime` 依赖（immersionbar / glide / okhttp3-integration / webpdecoder / mmkv / libpush）造成的。
4. **ernestp 3.6.0 已彻底解决**：[实测] 其 POM 只剩 `xlog 1.11.1` + `libnative` + `kotlin-stdlib 2.2.10` + `appcompat 1.7.1` + `constraintlayout 2.2.1` + `material 1.13.0` + `libuvc`；构建脚本用 **AGP 8.10.1 / Kotlin 2.2.10 / compileSdk 36 / targetSdk 36 / minSdk 23 / NDK 28.1.13356709**，仓库是 `google() + mavenCentral() + jitpack`（无 jcenter）。

---

## 3. libausbc 依赖闭包（问题 3）

### 3.1 AAR 内的 .so 与 ABI（**[实测]** —— 直接下载并解析 zip）

| 模块 AAR | 大小 | .so | ABI |
|---|---|---|---|
| `libausbc-3.2.7.aar` | 393,496 B | **无任何 .so**（27 个条目：classes.jar / R.txt / AndroidManifest.xml / proguard.txt） | — |
| `libuvc-3.2.7.aar` | 653,299 B | libUVCCamera.so、libjpeg-turbo1500.so、libusb100.so、libuvc.so | **arm64-v8a、armeabi-v7a** |
| `libnative-3.2.7.aar` | 244,928 B | libnativelib.so | **arm64-v8a、armeabi-v7a** |
| `libpush-3.2.7.aar` | 200 | （RTMP 推流，本项目不需要） | — |
| `ern-libausbc-3.6.0.aar` | 384,784 B | 无 .so | — |
| `ern-libuvc-3.6.0.aar` | 1,843,430 B | libUVCCamera.so、libjpeg-turbo313.so、libusb100.so、libuvc.so | **arm64-v8a、armeabi-v7a、x86、x86_64** |
| `ern-libnative-3.6.0.aar` | 483,660 B | libnativelib.so | **arm64-v8a、armeabi-v7a、x86、x86_64** |

**回答"libausbc 的 AAR 里包含哪些 .so / ABI"**：**libausbc 自身不含 .so**；.so 全在 `libuvc`（相机驱动，4 个）与 `libnative`（1 个）里。3.2.7 只有 **arm64-v8a + armeabi-v7a** 两个 ABI；ernestp 3.6.0 拓展到 **4 个 ABI**（含模拟器用的 x86/x86_64）。

### 3.2 除 libausbc 外是否需要显式依赖其它模块？

**需要，而且这是一个已知缺陷。**

[实测] `libausbc-3.2.7.pom` 的完整依赖表：

| 依赖 | scope | 说明 |
|---|---|---|
| com.gyf.immersionbar:immersionbar:3.0.0 | compile | 状态栏沉浸（JCenter，已失效） |
| **libnative:3.2.7** | **compile** | ✅ 自动带入 |
| org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.5.20 | compile | 自动带入 |
| androidx.appcompat:1.3.1 | runtime | 自动带入 |
| androidx.core:core-ktx:1.3.2 | runtime | |
| kotlinx-coroutines-android:1.3.9 | runtime | |
| androidx.constraintlayout:2.0.4 | runtime | |
| com.google.android.material:material:1.3.0 | runtime | |
| com.github.bumptech.glide:glide:4.10.0 | runtime | |
| com.github.bumptech.glide:okhttp3-integration:4.10.0 | runtime | |
| com.zlc.glide:webpdecoder:1.6.4.9.0 | runtime | JitPack 401（#711） |
| com.tencent:mmkv:1.2.12 | runtime | |
| **libuvc:3.2.7** | **runtime** ⚠️ | **编译期不可见** |
| **libpush:3.2.7** | **runtime** | RTMP 推流，可不关心 |

**⚠️ 核心坑（#756）**：[#756](https://github.com/jiangdongguo/AndroidUSBCamera/issues/756) 描述得极准确：
```
libausbc POM declares libuvc as a runtime-scoped dependency, but two methods of the IDeviceConnectCallBack interface (which lives in libausbc) use a type from libuvc as a method parameter. This means any consumer that implements IDeviceConnectCallBack cannot compile unless they manually add libuvc as a separate compileOnly dependency.
```
[实测] 验证一致：`libausbc-3.2.7` 的 `IDeviceConnectCallBack` 签名参数是 `com.serenegiant.usb.USBMonitor.UsbControlBlock`，而该类在 `libuvc` 里（`com/serenegiant/usb/USBMonitor$UsbControlBlock`），scope 却是 `runtime`。

**修复写法**：
```groovy
implementation 'com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7'
// 让编译期能看到 USBMonitor.UsbControlBlock（POM 里被标成 runtime）
compileOnly   'com.github.jiangdongguo.AndroidUSBCamera:libuvc:3.2.7'
```
（若你也直接用 `UVCCamera` / `USBMonitor` 类，则用 `implementation`。ernestp 3.6.0 同样是 `runtime` scope，同样需要这一行，只是包名换成 `com.jiangdg.usb`。）

### 3.3 是否需要 NDK？

**不需要**（用 JitPack 依赖时）。证据：
- [实测] `.so` 已经预编译并打包在 AAR 内（上表），Gradle 直接合并进 APK。
- [仓库] README 只要求"确认你的 Android 设备支持 OTG"，无任何 NDK / CMake 步骤：[README.md](https://raw.githubusercontent.com/jiangdongguo/AndroidUSBCamera/master/README.md)。
- [实测] `gradle.properties` 里的 `android.useDeprecatedNdk=true` 与 `ndkVersion` 只影响库自身源码构建，消费方不触发。

**例外**：若要**自己修 16 KB 对齐**，就需要 NDK（r27+ 默认 16 KB 对齐）重编 `libuvc`/`libnative` 并替换 `jniLibs`（社区讨论见 #723/#735）。

---

## 4. API 最小可用路径（问题 4）

### 4.1 两条路线的 API 差异（**必须先选对版本，否则代码白写**）

| 维度 | 上游 3.2.7 | ernestp 3.6.0 |
|---|---|---|
| 客户端类 | `CameraClient`（单摄） | `MultiCameraClient`（多摄） |
| Fragment 基类 | `com.jiangdg.ausbc.base.CameraFragment` | 同名，但基于 MultiCameraClient |
| `getCameraView()` | **abstract**（必须重写） | **abstract**（必须重写） |
| `getCameraViewContainer()` | **abstract**（必须重写） | **abstract**（必须重写） |
| `getCameraRequest()` | **private**（❌ 不可重写） | **open**（✅ 可重写） |
| `getRootView()`（来自 BaseFragment） | **abstract**（必须重写） | **abstract**（必须重写） |
| 渲染模式 | 固定 GLES（`setEnableGLES`） | `CameraRequest.RenderMode.NORMAL / OPENGL` |
| 预览格式 | **无此参数**，内部先 MJPEG 后 YUYV | `CameraRequest.PreviewFormat.FORMAT_MJPEG / FORMAT_YUYV` |
| 旋转 | `CameraClient.Builder.setDefaultRotateType` | `CameraRequest.defaultRotateType` |
| USB 回调包名 | `com.serenegiant.usb.USBMonitor` | `com.jiangdg.usb.USBMonitor` |

**BaseFragment 的契约**（[3.2.7 BaseFragment.kt](https://raw.githubusercontent.com/jiangdongguo/AndroidUSBCamera/3.2.7/libausbc/src/main/java/com/jiangdg/ausbc/base/BaseFragment.kt)）：
```kotlin
abstract class BaseFragment : Fragment() {
    override fun onCreateView(...)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?)   // → 调 initView() + initData()
    protected abstract fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View?
    protected open fun initView() {}
    protected open fun initData() {}
    protected open fun clear() {}
}
```

**CameraFragment.initData() 的内部流程**（[3.2.7 源码](https://raw.githubusercontent.com/jiangdongguo/AndroidUSBCamera/3.2.7/libausbc/src/main/java/com/jiangdg/ausbc/base/CameraFragment.kt)）：
```kotlin
override fun initData() {
    mCameraClient = getCameraClient() ?: getDefault()
    when (val cameraView = getCameraView()) {
        is AspectRatioTextureView -> handleTextureView(cameraView)   // → 打开相机
        is AspectRatioSurfaceView -> handleSurfaceView(cameraView)
        else -> null
    }?.let { view ->
        getCameraViewContainer()?.apply {
            removeAllViews()
            addView(view, getViewLayoutParams(this))
        }
    }
}
```
→ **推论：`getCameraView()` 必须返回 `AspectRatioTextureView` 或 `AspectRatioSurfaceView` 的实例**，返回其它 `IAspectRatio` 实现会导致"静默不预览"（else → null，无任何报错）。

### 4.2 CameraRequest 关键参数怎么选

**MJPEG vs YUYV**：
- 3.2.7 **没有这个开关**：`CameraUvcStrategy.createCamera()`（[源码 L137-172](https://raw.githubusercontent.com/jiangdongguo/AndroidUSBCamera/3.2.7/libausbc/src/main/java/com/jiangdg/ausbc/camera/CameraUvcStrategy.kt)）**先试 MJPEG，失败才回退 YUYV**：
```kotlin
mUVCCamera = UVCCamera().apply {
    setPreviewSize(w, h, UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.DEFAULT_BANDWIDTH)
}
// catch: setPreviewSize failed → 换 UVCCamera.FRAME_FORMAT_YUYV 再试
```
- ernestp 3.6.0 可以用 `previewFormat` 显式指定。

**推荐配置（HDMI 采集卡 MS2109 / 通用 UVC 摄像头）**：

| 场景 | previewFormat | 分辨率 | 建议 |
|---|---|---|---|
| MS2109 等 USB2.0 采集卡 1080p | **MJPEG** | 1920×1080@30 | YUYV 1080p@30 需 ~1.5 Gbps，USB2.0 只有 ~480 Mbps，**必然失败或掉帧** |
| 720p | MJPEG 优先 | 1280×720 | 同上 |
| 低延迟/小分辨率 | YUYV 可 | 640×480 | YUYV 无解码开销，延迟更低 |
| 双摄同开 | **混合格式** | — | [#751](https://github.com/jiangdongguo/AndroidUSBCamera/issues/751) 实测：两路同格式会互相抢带宽，仅一路显示 |

**RenderMode**：
- `OPENGL`（默认）：走 GL 管线，支持旋转/滤镜/水印，但多一次纹理拷贝。
- `NORMAL`：直接投到 Surface，**延迟最低、兼容性最好**，但不支持渲染特效。
- 只要"简单预览" → 若遇红米/荣耀系黑屏（#468/#584/#638），**优先试 `NORMAL`**。

**旋转**：`RotateType.ANGLE_0 / 90 / 180 / 270 / FLIP_UP_DOWN / FLIP_LEFT_RIGHT`（[RotateType.kt](https://raw.githubusercontent.com/jiangdongguo/AndroidUSBCamera/master/libausbc/src/main/java/com/jiangdg/ausbc/render/env/RotateType.kt)）。注意 [#590](https://github.com/jiangdongguo/AndroidUSBCamera/issues/590)：OPENGL 模式下旋转 90° 会**拉伸变形**（未按显示比例重算 aspect）。

### 4.3 USB 权限请求流程（库内部已封装）

[实测] 3.2.7 的 `CameraUvcStrategy` 内部：
```kotlin
private fun requestCameraPermission(device: UsbDevice?) { ... }   // L733
// 与 USBMonitor.requestPermission(device) → UsbManager.requestPermission(device, mPermissionIntent)
```
[实测] `USBMonitor.requestPermission()`（3.2.7 L435-448）：
```java
public synchronized boolean requestPermission(final UsbDevice device) {
    ...
    mUsbManager.requestPermission(device, mPermissionIntent);
}
```
→ **你不需要自己写 `UsbManager.requestPermission`**；只要 `register()` 被调用（库在 `CameraUvcStrategy.register()` 里做）并监听 `IDeviceConnectCallBack`。

**必须在 App manifest 里加**（库的 manifest 不会帮你声明设备过滤）：
```xml
<uses-feature android:name="android.hardware.usb.host" android:required="true" />
<!-- 若希望插上设备自动唤起 App -->
<activity ...>
    <intent-filter>
        <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
    </intent-filter>
    <meta-data android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
               android:resource="@xml/device_filter" />
</activity>
```
（`device_filter` 用 `<usb-device vendor-id=… product-id=… />` 过滤；UVCAndroid 的 AAR 里就自带 `res/xml/device_filter.xml` —— [实测] 见其 45 个条目。）

### 4.4 相机断开 / 热插拔处理

[实测] 接口定义（[IDeviceConnectCallBack.kt](https://raw.githubusercontent.com/jiangdongguo/AndroidUSBCamera/master/libausbc/src/main/java/com/jiangdg/ausbc/callback/IDeviceConnectCallBack.kt)）：
```kotlin
interface IDeviceConnectCallBack {
    fun onAttachDev(device: UsbDevice?)          // 设备插入
    fun onDetachDec(device: UsbDevice?)          // 设备拔出
    fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock? = null)   // 授权成功、可打开
    fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock? = null)
    fun onCancelDev(device: UsbDevice?)          // 用户拒绝授权
}
```
状态回调（[ICameraStateCallBack.kt](https://raw.githubusercontent.com/jiangdongguo/AndroidUSBCamera/master/libausbc/src/main/java/com/jiangdg/ausbc/callback/ICameraStateCallBack.kt)）：
```kotlin
interface ICameraStateCallBack {
    fun onCameraState(self: MultiCameraClient.ICamera, code: State, msg: String? = null)
    enum class State { OPENED, CLOSED, ERROR }
}
```

**热插拔已知问题**：
- [#755](https://github.com/jiangdongguo/AndroidUSBCamera/issues/755)（2026-06）：**快速重插 + 立刻授权会崩溃**，根因是 `UVCStatusCallback.cpp` 里 `vm->DetachCurrentThread()` 在错误线程调用；issue 附了社区补丁。
- [#552](https://github.com/jiangdongguo/AndroidUSBCamera/issues/552)（2023-04，最新回复 2026-04）：插入后首次授权 → **黑屏，必须重启 App** 才显示。

**工程建议**：热插拔后强制走一遍 `closeCamera() → 延迟 300~500ms → openCamera()`，并在 `onCameraState(ERROR)` 时做"销毁 client 重建"的重试（库自身不会自动恢复）。

### 4.5 可编译骨架（Kotlin）

> **A. 若继续用上游 3.2.7**（注意 `getCameraRequest()` 是 private，只能通过 `getCameraClient()` 定制）：

```kotlin
// build.gradle(app)
// implementation 'com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7'
// compileOnly   'com.github.jiangdongguo.AndroidUSBCamera:libuvc:3.2.7'   // 修 #756

package com.example.uvc

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.camera.CameraUvcStrategy
import com.jiangdg.ausbc.CameraClient
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio

class PreviewFragment : CameraFragment() {

    private lateinit var mContainer: FrameLayout
    private lateinit var mTextureView: AspectRatioTextureView

    /** 1) 必须重写：提供根布局 */
    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        val root = inflater.inflate(R.layout.fragment_preview, container, false)
        mContainer = root.findViewById(R.id.preview_container)
        return root
    }

    /** 2) 必须重写：返回预览控件（只能是 AspectRatioTextureView / AspectRatioSurfaceView） */
    override fun getCameraView(): IAspectRatio {
        mTextureView = AspectRatioTextureView(requireContext())
        return mTextureView
    }

    /** 3) 必须重写：返回承载预览的容器 */
    override fun getCameraViewContainer(): ViewGroup = mContainer

    /** 4) 可选：3.2.7 里 getCameraRequest() 是 private，只能通过 client 定制 */
    override fun getCameraClient(): CameraClient? {
        return CameraClient.newBuilder(requireContext())
            .setEnableGLES(true)                     // 遇黑屏可改 false（走 NORMAL 渲染）
            .setRawImage(true)                       // 需要 NV21 原始数据做算法时开
            .setCameraStrategy(CameraUvcStrategy(requireContext()))
            .setDefaultRotateType(null)              // 需要旋转时传 RotateType.ANGLE_90
            .openDebug(true)                         // 出问题时看 [AUSBC] 日志
            .build()
    }
}
```
布局：
```xml
<!-- res/layout/fragment_preview.xml -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent">
    <FrameLayout android:id="@+id/preview_container"
        android:layout_width="match_parent" android:layout_height="match_parent" />
</FrameLayout>
```

> **B. 推荐：改用 ernestp 3.6.0**（`getCameraRequest()` 可重写，参数可控）：

```kotlin
// implementation 'com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0'
// compileOnly   'com.github.ernestp.AndroidUSBCamera:libuvc:3.6.0'   // 同样是 runtime scope

import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio

class PreviewFragment : CameraFragment() {

    private lateinit var mContainer: FrameLayout

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        val root = inflater.inflate(R.layout.fragment_preview, container, false)
        mContainer = root.findViewById(R.id.preview_container)
        return root
    }

    override fun getCameraView(): IAspectRatio = AspectRatioTextureView(requireContext())

    override fun getCameraViewContainer(): ViewGroup = mContainer

    /** 3.6.0 中是 open，可直接重写 */
    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(1920)
            .setPreviewHeight(1080)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)   // 采集卡必选 MJPEG
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.NONE)
            .setAspectRatioShow(true)
            .create()
    }
}
```

> ⚠️ 骨架中的 `CameraRequest.Builder()` 方法名来自对 3.3.x/3.6.0 源码字段与 Builder 结构的核对；**首次编译若报方法不存在，请以 `CameraRequest.kt` 实际 Builder 为准**（文件路径：`libausbc/src/main/java/com/jiangdg/ausbc/camera/bean/CameraRequest.kt`）。

**拍照**：`protected fun captureImage(callBack: ICaptureCallBack, savePath: String? = null)`（由 Fragment/Activity 基类提供）。注意 [#754](https://github.com/jiangdongguo/AndroidUSBCamera/issues/754)（2026-05，"captureImage 拍照无回调问题"）。

---

## 5. 常见坑与社区反馈（问题 5）

### 5.1 预览黑屏 / 绿屏

| 现象 | 证据 | 根因 / 应对 |
|---|---|---|
| 首次插入授权后黑屏，重启 App 才正常 | [#552](https://github.com/jiangdongguo/AndroidUSBCamera/issues/552)（2023-04，2026-04 仍有回复） | 授权广播与 surface 就绪时序竞争；应对：`onConnectDev` 后再 `openCamera`，或权限授予后重建 client |
| Android 11 + Redmi Note 10 黑屏（能拿到预览尺寸） | [#468](https://github.com/jiangdongguo/AndroidUSBCamera/issues/468) | 与 targetSdk 有关；同一现象在原生 `UVCCamera` 上不出现 |
| Android 13/14 黑屏（Pixel 7 / Redmi 10 prime） | [#638](https://github.com/jiangdongguo/AndroidUSBCamera/issues/638) | 应对：关 OPENGL（`setEnableGLES(false)`）、降分辨率、试 YUYV |
| 荣耀 X30i / Android 12 黑屏但 nativeConnect 返回 0 | [#584](https://github.com/jiangdongguo/AndroidUSBCamera/issues/584)、[#583](https://github.com/jiangdongguo/AndroidUSBCamera/issues/583) | 相机已连但不出图，属 ROM 侧 Surface/GL 兼容问题 |
| Android 14/15 "Camera on but display blank, fps 1" | [#732](https://github.com/jiangdongguo/AndroidUSBCamera/issues/732)（2025-07） | 疑似新系统 + 旧 native 的帧回调问题 |
| **录制出绿屏**（预览/拍照正常） | [#498](https://github.com/jiangdongguo/AndroidUSBCamera/issues/498)（MIUI 13 / Redmi 10C / Android 11） | 编码器颜色格式不匹配；只在录制路径出问题 |
| 华为 Mate60/P50 切换分辨率后黑屏 | [#699](https://github.com/jiangdongguo/AndroidUSBCamera/issues/699) | 切分辨率未正确重开 preview |

### 5.2 旋转 90/180

| 现象 | 证据 |
|---|---|
| 预览是倒置 180° | [#221](https://github.com/jiangdongguo/AndroidUSBCamera/issues/221)（CLOSED；注意 `android:rotation="180"` 只转预览，**拍出的照片/视频仍倒置**） |
| `setRotateType(ANGLE_90)` 后**图像被拉伸变形** | [#590](https://github.com/jiangdongguo/AndroidUSBCamera/issues/590)（2023-08，2026-01 仍有活动） |
| 旋转屏幕后黑屏，报 -99 | [#472](https://github.com/jiangdongguo/AndroidUSBCamera/issues/472) |
| 多路摄像头无旋转设置入口 | [#486](https://github.com/jiangdongguo/AndroidUSBCamera/issues/486) |

**应对**：旋转必须在**容器层**做（`AspectRatioTextureView` 的父容器用 `ViewPropertyAnimator`/`Matrix` 按真实宽高比缩放），不要只依赖 `setRotateType`；库的 OPENGL 路径在旋转后不重算 aspect。

### 5.3 分辨率不支持导致 open 失败

| 现象 | 证据 |
|---|---|
| `getAllPreviewSizes = []` → `unsupported preview size(640,480)` → `could not negotiate with camera: err=-51` | [#501](https://github.com/jiangdongguo/AndroidUSBCamera/issues/501)（**3.2.10 坏了，3.2.8 正常** —— 典型的版本回归） |
| `unsupported preview size` | [#522](https://github.com/jiangdongguo/AndroidUSBCamera/issues/522) |
| 分辨率 >1280×720 后帧率骤降、卡顿 | [#512](https://github.com/jiangdongguo/AndroidUSBCamera/issues/512) |
| 切换分辨率触发带宽失败 | [#683](https://github.com/jiangdongguo/AndroidUSBCamera/issues/683) |
| 切换到 2400×1350 闪退 | [#758](https://github.com/jiangdongguo/AndroidUSBCamera/issues/758)（2026-09） |

**正确做法**：**先用 `getAllPreviewSizes()` 取设备真实能力，再从白名单里选**，不要硬编码 640×480。注意 3.2.7 的 `getAllPreviewSizes()` 内部是按 **YUYV** 格式查询的（`getSupportedSizeList(UVCCamera.FRAME_FORMAT_YUYV)`，源码 L351），因此可能返回比 MJPEG 更少的档位。

### 5.4 MJPEG vs YUYV 带宽

- **物理约束**：USB 2.0 OTG 实际可用带宽约 480 Mbps（理论），1080p YUYV@30 ≈ 1920×1080×2×30 ≈ **1.49 Gbps** → 必然失败。1080p MJPEG@30 典型 20–60 Mbps → 可行。
- 库内证据：3.2.7 的 `createCamera()` 先 MJPEG 后 YUYV 的顺序本身就是这个逻辑（[源码](https://raw.githubusercontent.com/jiangdongguo/AndroidUSBCamera/3.2.7/libausbc/src/main/java/com/jiangdg/ausbc/camera/CameraUvcStrategy.kt)）。
- 双摄带宽：[#751](https://github.com/jiangdongguo/AndroidUSBCamera/issues/751)（Galaxy Tab A7 / Android 12）实测——**两路同格式（都 MJPEG 或都 YUYV）只有一路稳定显示**，混合格式能同时显示但跑久了崩溃。
- 采集卡侧：MS2109 是 USB 2.0 HD 音视频采集芯片，输出即 USB2.0 带宽受限 → [Macrosilicon 官方产品页](http://www.macrosilicon.com/info.asp?base_id=2&third_id=111)。

### 5.5 OTG 供电不足

- 现象：画面间歇性掉线、`err=-51` 协商失败、设备反复 attach/detach。
- 本轮**未能检索到该库针对供电问题的专门 issue 或官方说明**（标注为未证实/经验项）。
- 工程可验证的间接证据：`#683`（带宽失败导致打不开）与 `#456`（`USBMonitor already destroyed`）都与"设备侧异常"相关。
- **建议做法（非事实断言）**：使用带外部供电的 OTG Hub / 采集卡自供电；在 `onDetachDec` 里做"3 秒内重连"的退避重试。

### 5.6 国产 ROM（MIUI / ColorOS / HyperOS）USB 权限弹窗与后台限制

- 直接证据有限。仓库内 MIUI 关键词仅命中 3 条：[#498](https://github.com/jiangdongguo/AndroidUSBCamera/issues/498)（MIUI 13 录制绿屏）、[#188](https://github.com/jiangdongguo/AndroidUSBCamera/issues/188)、[#85](https://github.com/jiangdongguo/AndroidUSBCamera/issues/85)。
- 更常见的是"权限相关"问题族（45 条），其中典型的国产 ROM 现象：
  - [#484](https://github.com/jiangdongguo/AndroidUSBCamera/issues/484)："为什么集成您的库，没有弹出 USB 连接弹框呢"
  - [#534](https://github.com/jiangdongguo/AndroidUSBCamera/issues/534)：Skip USB confirm popup error
  - [#530](https://github.com/jiangdongguo/AndroidUSBCamera/issues/530)：MultiCameraFragment ask for permission nonstop
  - [#648](https://github.com/jiangdongguo/AndroidUSBCamera/issues/648)：如何把 APP 设为 UVC 设备的默认 APP
  - [#707](https://github.com/jiangdongguo/AndroidUSBCamera/issues/707)：Android 13 预览失败
- **应对（工程建议）**：
  1. 弹窗被 ROM 拦截时，用 `UsbManager` 的 **"默认打开本应用"** 开关（设置 → 应用 → 默认打开 → 支持 USB 设备）引导用户手动授权；
  2. 后台被杀 → 前台服务 + 电池优化白名单引导（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）；
  3. 每次 attach 都重新 `requestPermission`（不要假设权限长期有效）。

---

## 6. 备选方案对比结论（问题 6）

### 6.1 各库活跃度实测（**[实测]** 抓仓库页面）

| 库 | 默认分支 | 最后 commit | 最新 tag / 坐标 | Stars | 结论 |
|---|---|---|---|---|---|
| **ernestp/AndroidUSBCamera** | master | **2026-05-09** | **3.6.0（2026-05-09）**；JitPack `com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0`（制品 3/3 齐全） | 35 | ✅ **最活跃** |
| **shiyinghan/UVCAndroid**（原 herohan） | **main**（不是 master！） | **2026-06-24** | **1.0.13（2026-06-10）**；**Maven Central** `com.herohan:UVCAndroid:1.0.13` | 470 | ✅ 活跃 |
| jiangdongguo/AndroidUSBCamera | master | 2024-09-02 | 3.3.3（2023-02-13） | — | ⚠️ 停更 |
| saki4510t/UVCCamera | master | **2018-10-02** | 需 NDK 自编 | 3218 | ❌ 停更 8 年 |
| Liuguihong/AndroidUVCCamera | master | **2019-10-26** | JitPack `1.0.0` = **Error**（仅 `-b420ccf1ab-1` 可用） | 247 | ❌ 停更 + 坐标不可用 |
| poorld/UVCCamera | **main** | 2025-09-13 | JitPack **无任何构建记录** | 3 | ❌ 无发布 |

来源：[ernestp commits](https://github.com/ernestp/AndroidUSBCamera/commits/master)、[ernestp tags](https://github.com/ernestp/AndroidUSBCamera/tags)、[shiyinghan/UVCAndroid](https://github.com/shiyinghan/UVCAndroid/tags)、[saki4510t/UVCCamera](https://github.com/saki4510t/UVCCamera/commits/master)、[Liuguihong](https://github.com/Liuguihong/AndroidUVCCamera/commits/master)、[poorld](https://github.com/poorld/UVCCamera/commits/main)、JitPack API 各库 builds。

> **坐标核实**：
> - `herohan/UVCAndroid` 实际仓库已更名为 **`shiyinghan/UVCAndroid`**，**默认分支是 `main`**（GitHub API 返回 `defaultBranch=main`），JitPack 上 `com.github.shiyinghan/UVCAndroid` 的 tag 构建**几乎全是 Error**（1.0.0~1.0.12 = Error），**因为它已迁移到 Maven Central**（`com.herohan:UVCAndroid`）。**不要走 JitPack 坐标。**
> - `Liuguihong/AndroidUVCCamera` 的 JitPack 1.0.0 = Error，仅 `-b420ccf1ab-1` 这种奇怪版本号可用 → **任务书里给的 `com.github.Liuguihong:AndroidUVCCamera:1.0.0` 实际不可用**。

### 6.2 只要"预览"场景的推荐排名

**第 1 名：ernestp/AndroidUSBCamera 3.6.0** ⭐ 推荐
- 理由：① 与团队已有认知/代码结构**同源**（`com.jiangdg.ausbc` 包名不变，Fragment/回调 API 兼容度最高）；② **实测 arm64-v8a/x86_64 .so 全部 16384 ✔**，直接满足 16 KB；③ 已修 `RECEIVER_NOT_EXPORTED`（Android 14 崩溃）；④ 依赖闭包干净（无 JCenter 残留），AGP 8.10.1/Kotlin 2.2.10/compileSdk 36/targetSdk 36/minSdk 23；⑤ JitPack 制品 3/3 齐全；⑥ 自述"actively maintained, canonical fork"，README 明确写 "Compatibility with Android 16 and 16K page size"。
- 代价：包名 `com.serenegiant.usb` → `com.jiangdg.usb` 需改 import；star 数少（35），长期维护依赖单一维护者。

**第 2 名：com.herohan:UVCAndroid:1.0.13（Maven Central）** 
- 理由：① **最活跃**（tag 1.0.13 = 2026-06-10，master 提交 2026-06-24）；② **Maven Central 发布**（比 JitPack 稳定，JitPack 是"按需构建"，失败率高——本项目已亲历 3.3.x 全 404）；③ **实测全 ABI（含 32 位）全部 16384 ✔**；④ 单 AAR 自包含（5 个 .so 打包在一起），无子模块坑；⑤ API 清晰（`CameraHelper` + `ICameraHelper.StateCallback` + `addSurface()`）。
- 代价：与 AUSBC API 完全不同，要重写 UI 层；依赖多一个 `cn.hutool:hutool-core:5.8.35`；470 stars 但 issue 生态比 AUSBC 小。

**第 3 名：上游 jiangdongguo/AndroidUSBCamera 3.2.7**
- 仅当"必须使用团队已锁定的坐标"时。
- 已知代价：16 KB 不合格（上架 Play 阻塞）、Android 14+ targetSdk 34 崩溃风险、JCenter 依赖（AGP 8 报错）、libuvc runtime scope 编译问题、热插拔崩溃。
- **若坚持用，必须做 4 件事**：`compileOnly libuvc`、targetSdk 压到 33 以下或自行打补丁 `USBMonitor.register()`、`android.enableJetifier`+手动补 jcenter 镜像、接受 16 KB 不合格（不上架 Play）。

**第 4 名：saki4510t/UVCCamera**：2018 年停更，需 NDK 自编 + 自己封装 Kotlin API。除"完全不想用第三方封装"外无理由选。

**第 5 名：Liuguihong/AndroidUVCCamera（JitPack 1.0.0 实际 Error）、poorld/UVCCamera（无发布）**：**不建议**，坐标/制品不可用或维护度极低。

### 6.3 CameraX / camera2 是否原生支持外接 UVC 摄像头

**结论：理论上支持，实际绝大多数手机不可用，不能作为主路径。**

- [官方] AOSP《External USB cameras》："The Android platform supports the use of plug-and-play USB cameras (that is, webcams) using the standard **Android Camera2 API and the camera HAL interface**. Webcams generally support USB video class (UVC) drivers…"
  来源：<https://source.android.com/docs/core/camera/external-usb-cameras>
  → 注意措辞：这是 **平台能力**（需要设备侧实现 external camera provider 并注册进 camera HAL），**不是 App 可以直接打开的通用能力**。
- [社区] StackOverflow《connecting USB UVC camera with CameraX》："I can see the camera using UsbManager but **for some reason, the CameraX doesn't list it in the connected camera list**。" <https://stackoverflow.com/questions/75169761/connecting-usb-uvc-camera-with-camerax>
- [社区] Core Electronics 论坛："**Camera2 API compatibility: Android devices are not required to support external webcams.** … it's not mandatory for manufacturers to enable support for external cameras." <https://forum.core-electronics.com.au/t/access-usb-webcam-in-android-app-on-pi4-android-11/13845>
- [官方/AOSP] Android 14 QPR1+ 的"把手机当 USB 摄像头（webcam）"是**反向**能力（手机输出为 UVC 设备），与外接摄像头无关：<https://source.android.com/docs/core/camera/webcam>

**工程判定**：CameraX/Camera2 的 `EXTERNAL` 路线依赖 OEM 打开 external provider，在 MIUI/ColorOS/HyperOS/原生 Pixel 上均不可预期 → **本项目必须自己走 UVC 协议栈（即 AUSBC/UVCAndroid 这类库）**。

---

## 7. 2026 年是否有更新、更活跃的同类库（问题 7）

**有 —— 而且是两条路：**

### 7.1 上游项目自身的活跃 fork：ernestp/AndroidUSBCamera 3.6.0（**最贴合本项目**）

- 仓库：<https://github.com/ernestp/AndroidUSBCamera>（默认分支 master）
- 最后提交：**2026-05-09T06:54:41-07:00**；最新 tag **3.6.0 = 2026-05-09**
- 版本节奏：3.4.1→3.4.5(2025) → **3.5.0(2025-12-03) → 3.5.1(2025-12-04) → 3.5.2(2026-03-22) → 3.5.3(2026-03-28) → 3.6.0(2026-05-09)**（[tags](https://github.com/ernestp/AndroidUSBCamera/tags)）
- README 原文（[来源](https://raw.githubusercontent.com/ernestp/AndroidUSBCamera/master/README.md)）：
  ```
  This is the **actively maintained, canonical fork** of the original AndroidUSBCamera project.
  … 
  ## 🚀 Project Status (Updated March 2026)
  - Recent highlights: Compatibility with **Android 16** and **16K page size**, fixed permission issues and reliability issues.
  - Original upstream jiangdongguo/AndroidUSBCamera is no longer actively maintained (last commit: Sep 2024; last release: Feb 2023).
  ```
- 构建基线：AGP **8.10.1**、Kotlin **2.2.10**、compileSdk/targetSdk **36**、minSdk **23**、NDK 28.1.13356709
- [实测] JitPack 制品：libausbc / libuvc / libnative **3/3 齐全**（libpush 已不需要，404 但 POM 也不引用）
- [实测] 16 KB：arm64-v8a + x86_64 全部 **16384 ✔**

### 7.2 独立库：shiyinghan/UVCAndroid 1.0.13（原 herohan）

- 仓库：<https://github.com/shiyinghan/UVCAndroid>（**默认分支 main**）
- Maven Central：`com.herohan:UVCAndroid`，latest/release = **1.0.13**，lastUpdated = **20260610014918**
  （[maven-metadata.xml](https://repo1.maven.org/maven2/com/herohan/UVCAndroid/maven-metadata.xml)）
- tag 节奏：1.0.13 = 2026-06-10、1.0.12 = 2026-05-13、1.0.11 = 2025-10-30、1.0.10 = 2025-09-08
- [实测] AAR 45 个条目、2.76 MB、**5 个 .so × 4 ABI 全部 16384 ✔**，自带 `res/xml/device_filter.xml`
- 依赖：androidx.core 1.13.1 / legacy-support-v4 1.0.0 / appcompat 1.6.1 / annotation 1.8.0 / **cn.hutool:hutool-core:5.8.35**
- API：`CameraHelper` + `ICameraHelper.StateCallback` + `addSurface(Object, boolean)` + `openCamera(UVCParam)` / `takePicture` / `startRecording`
- 中文文档：<https://blog.csdn.net/hanshiying007/article/details/124118486>

### 7.3 其它观察

- **saki4510t/UVCCamera**（3.2k stars）**已 8 年未更新**，仍是很多库的 native 上游，但不再适合直接集成。
- **Media3 / CameraX 没有 UVC 支持进展**：本轮检索未发现 CameraX 新增外接 UVC 摄像头 API 的官方说明；唯一相关的官方动作是"手机作为 USB webcam 输出"（Android 14 QPR1+），方向相反。
- 参考时间点：Android 17 已于 2026-06 发布（[知乎](https://zhuanlan.zhihu.com/p/2050523911419326680)）——ernestp 3.6.0 的 targetSdk 36 在 Android 17 时代可能又需要一次升级，需持续跟进。

---

## 8. 风险清单 + 对应缓解措施

| # | 风险 | 影响 | 证据 | 缓解措施 |
|---|---|---|---|---|
| R1 | **16 KB 页对齐不合格**（3.2.7 的 arm64 .so 全是 4096） | Google Play 拒审（targetSdk≥35）；Android 15+ 16KB 设备崩溃 | [实测] AAR 解析；[官方](https://developer.android.com/guide/practices/page-sizes)；[#735/#744/#745/#746](https://github.com/jiangdongguo/AndroidUSBCamera/issues/744) | **迁移到 ernestp 3.6.0**（实测 16384 ✔）；或 NDK r27+ 自编替换 jniLibs |
| R2 | JitPack 版本不可用（3.3.x 全 404） | 依赖解析失败，构建挂掉 | [实测] JitPack 制品状态 + 构建日志 "No build artifacts found" | 锁死 3.2.7 或迁 3.6.0；把 AAR 缓存到本地 `libs/` + `flatDir` 规避 JitPack 抖动 |
| R3 | Android 14+ targetSdk 34 动态广播崩溃 | 插拔设备即崩 | [实测] 3.2.7 源码无 `RECEIVER_NOT_EXPORTED`；[#651](https://github.com/jiangdongguo/AndroidUSBCamera/issues/651)/[#667](https://github.com/jiangdongguo/AndroidUSBCamera/issues/667)/[#722](https://github.com/jiangdongguo/AndroidUSBCamera/issues/722) | 迁 ernestp（已修）；或 fork `USBMonitor.register()` 打补丁；或 targetSdk 暂压 33 |
| R4 | `libuvc` 是 runtime scope → 编译期看不到 `USBMonitor.UsbControlBlock` | 实现 `IDeviceConnectCallBack` 时编译失败 | [#756](https://github.com/jiangdongguo/AndroidUSBCamera/issues/756)；[实测] POM | 显式加 `compileOnly libuvc:同版本` |
| R5 | JCenter 依赖消失 | AGP 8 构建失败（immersionbar / webpdecoder） | [#684](https://github.com/jiangdongguo/AndroidUSBCamera/issues/684)、[#711](https://github.com/jiangdongguo/AndroidUSBCamera/issues/711) | 迁 ernestp 3.6.0（已剔除这些依赖）；或用 `exclude group` 排除后用不到的库 |
| R6 | 首次授权后黑屏 / 热插拔崩溃 | 演示翻车、用户体验差 | [#552](https://github.com/jiangdongguo/AndroidUSBCamera/issues/552)、[#755](https://github.com/jiangdongguo/AndroidUSBCamera/issues/755) | `onConnectDev` 后再 open；插拔后 close→delay(300-500ms)→open；`ERROR` 时销毁重建 client |
| R7 | 分辨率协商失败（err=-51） | 打不开相机 | [#501](https://github.com/jiangdongguo/AndroidUSBCamera/issues/501)、[#522](https://github.com/jiangdongguo/AndroidUSBCamera/issues/522)、[#683](https://github.com/jiangdongguo/AndroidUSBCamera/issues/683) | **先 `getAllPreviewSizes()` 再选档**；准备 1920×1080→1280×720→640×480 的降级链 |
| R8 | MJPEG/YUYV 带宽不足（采集卡 1080p） | 卡顿/绿屏/丢帧 | [实测] 3.2.7 源码先 MJPEG 后 YUYV；[#751](https://github.com/jiangdongguo/AndroidUSBCamera/issues/751) | 1080p **强制 MJPEG**；双摄避免同格式 |
| R9 | 旋转 90° 图像拉伸 | 画面变形 | [#590](https://github.com/jiangdongguo/AndroidUSBCamera/issues/590) | 旋转放在容器层按真实宽高比缩放，不单靠 `setRotateType` |
| R10 | Android 14+ 后台采集无前台服务 | 后台被杀 / 抛异常 | [官方 service-types](https://developer.android.com/develop/background-work/services/fgs/service-types) | 需要后台时自建 `connectedDevice` 前台服务 + `FOREGROUND_SERVICE_CONNECTED_DEVICE` 权限 |
| R11 | 国产 ROM 权限弹窗/后台限制 | 首次授权失败、后台断流 | [#484](https://github.com/jiangdongguo/AndroidUSBCamera/issues/484)、[#530](https://github.com/jiangdongguo/AndroidUSBCamera/issues/530)、[#534](https://github.com/jiangdongguo/AndroidUSBCamera/issues/534)、[#648](https://github.com/jiangdongguo/AndroidUSBCamera/issues/648) | 引导用户设"默认打开本应用"；每次 attach 重新请求权限；电池优化白名单 |
| R12 | OTG 供电不足 | 间歇掉线 | 未证实（无专门 issue） | 用带供电的 OTG Hub；`onDetachDec` 做退避重连 |
| R13 | 上游彻底停更，未来无安全/兼容修复 | 技术债累积 | [commits](https://github.com/jiangdongguo/AndroidUSBCamera/commits/master)（2024-09 停）、[#729](https://github.com/jiangdongguo/AndroidUSBCamera/issues/729) | 立即评估迁 ernestp 3.6.0；并在工程里把 UVC 层抽象成接口，便于二次替换 |
| R14 | 单一维护者风险（ernestp 35 stars） | fork 也可能停更 | [ernestp](https://github.com/ernestp/AndroidUSBCamera) | fork 到内部私仓 + 记录重编流程（NDK 28）；同时保留 UVCAndroid 作为 Plan B |

---

## 9. 给团队的行动建议（TL;DR）

1. **不要用 3.2.7 作为长期方案**，它的 16 KB 与 Android 14 权限问题无法通过业务代码绕过。
2. **本周做一次 POC：把依赖换成 `com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0`**，同时加 `compileOnly libuvc:3.6.0`，按第 4.5 节 B 骨架跑通预览；把 import 里的 `com.serenegiant.usb` 改成 `com.jiangdg.usb`。
3. **验收口径**：`aapt2 dump badging` + Android Studio 的 "16 KB compatibility" 检查全绿；在 Android 14/15/16 真机上跑"插入→授权→预览→拔出→重插→再预览"。
4. **若 ernestp 出问题**，立刻切 `com.herohan:UVCAndroid:1.0.13`（Maven Central，全 ABI 16384）。
5. **把 UVC 抽象成 `UvcEngine` 接口**（`open/close/captureImage/startRecord`），避免第三次换库时重写业务。

---

### 附录：本报告的实测方法

- **AAR 解析**：Node 脚本读取 zip 中央目录 → 提取 `jni/**/*.so` → `zlib.inflateRawSync` 解压 → 解析 ELF program header（64 位：`e_phoff@0x20`、`e_phentsize@0x36`、`e_phnum@0x38`；PT_LOAD 的 `p_align@phdr+48`；32 位：`e_phoff@0x1c`、`p_align@phdr+28`），阈值 16384。
- **下载源**：JitPack（github 库）、Maven Central（repo1.maven.org）。
- **GitHub 数据**：直抓 HTML 内嵌 `react-app.embeddedData` JSON（API 403 时同样可用）。
- **官方文档**：因 `developer.android.com` 需走代理，使用 `HTTPS_PROXY=http://127.0.0.1:7897` + Node fetch 抓取。
- **未取得**：`source.android.com` 正文（跳 Google 登录），相关结论使用其搜索结果摘要 + 第三方佐证。
