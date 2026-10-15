# Android 15/16 · 16 KB 页大小 与 UVC(OTG) 预览方案调研报告

> 对象：jiangdongguo/AndroidUSBCamera（AUSBC）3.2.7（JitPack）在 16 KB page size 设备/Play 政策下的可用性，以及可落地的替代路线。
> 取证日期：2026-10-14（Asia/Shanghai）。所有 ELF 结论均为本机实测（NDK r28.2 llvm-readelf），命令与产物路径见附录 A。
> 官方主依据：[Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes?hl=en)（页面标注 Last updated 2026-09-16）。

---

## 关键结论（≤8 行）

1. **设备侧（Android 15/16 能否跑起来）**：Android 15 起 AOSP 支持 16 KB 页设备；Android 16 新增 **16 KB 兼容模式（backcompat）**，系统检测到「整包 4 KB 对齐」时**自动**进入兼容模式运行并弹提示框 —— 即 AUSBC 3.2.7 在 Android 16 上可能是「能跑但被降级」，而非必崩（[page-sizes](https://developer.android.com/guide/practices/page-sizes?hl=en)、[Android 16 行为变更](https://developer.android.com/about/versions/16/behavior-changes-all?hl=en)）。
2. **但我们的库并不安全**：按官方 RELRO 判定公式 `(VirtAddr+MemSiz) % 0x4000` 实测，AUSBC 3.2.7 的 arm64-v8a 中 **libUVCCamera.so 余数=0x1000、libusb100.so=0x3000、libjpeg-turbo1500.so=0x2000、libUACAudio.so=0x2000（仅 libuvc.so=0）**，正是官方描述的「RELRO 未对齐 → 运行时 segmentation fault」情形。
3. **Play 强制已延期**：官方页面现口径为 **2027-02-01** 起「不支持 16 KB 的更新将无法发布」；Android Studio 弹窗里的 `November 1st, 2025` 是旧口径（2025-05 博客原文）（[page-sizes](https://developer.android.com/guide/practices/page-sizes?hl=en)、[Play Console 技术质量要求](https://support.google.com/googleplay/android-developer/answer/17492799?hl=en)、[2025-05 博客](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html)）。
4. **NDK**：**r28 及以上默认 16 KB 对齐**（属实）；**r27 及以下**必须加 `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`（ndk-build 写 `LOCAL_LDFLAGS`/`APP_LDFLAGS`，CMake 写 `target_link_options`）。
5. **原地重对齐：不能**。官方只给「重新编译 + 重新导入」路径；实测 NDK r28 的 `llvm-objcopy` **没有** `--align-segment` 参数。**Gradle 侧 `useLegacyPackaging` / `zipalign -P 16` 只改 ZIP 对齐，改不了 ELF 段对齐 —— 不能规避**。
6. **AUSBC 上游仍未修**：issue [#735](https://github.com/jiangdongguo/AndroidUSBCamera/issues/735)（2026-03-24 关闭）、[#744](https://github.com/jiangdongguo/AndroidUSBCamera/issues/744)/[#745](https://github.com/jiangdongguo/AndroidUSBCamera/issues/745)/[#746](https://github.com/jiangdongguo/AndroidUSBCamera/issues/746)（**仍 Open**）；修复 PR [#753](https://github.com/jiangdongguo/AndroidUSBCamera/pull/753) 自 2026-03-14 起无评论、**未合并**（master 最后合并 2024-09-02）。
7. **最可行路线（本机已实测）**：`com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0`（JitPack）—— 实测 arm64-v8a / x86_64 **全部 LOAD Align = 0x4000**；同类还有 Maven Central `com.herohan:UVCAndroid:1.0.13`（实测全部 ABI = 0x4000），但 API 不同、需重写集成。
8. **备选**：nolte fork `com.github.nolte.AndroidUSBCamera:libuvc:3.2.7-16kb.2`（NDK r28 + `APP_LDFLAGS += -Wl,-z,max-page-size=16384`），API 面一致但包名已从 `com.serenegiant.usb.*` 迁至 `com.jiangdg.*`。

---

## 1. 官方要求核实

### 1.1 「设备启用 16 KB 页」与「Play 强制」是两条独立的事

| 维度 | 设备侧（能否运行） | Play 侧（能否上架/更新） |
|---|---|---|
| 引入版本 | Android 15（AOSP）开始支持 16 KB 页设备 | 面向 **targetSdk ≥ 35（Android 15）** 的应用 |
| 触发条件 | 设备内核配置为 16 KB 页（OEM 出厂决定），或用户开开发者选项重启 | 向 Play 提交新应用/更新 |
| 4 KB .so 后果 | Android 15 上可能崩溃；**Android 16 起自动进兼容模式**（弹框提示） | 截止日后更新被拒 |
| 当前截止 | — | **2027-02-01** |

原文摘录（[page-sizes](https://developer.android.com/guide/practices/page-sizes?hl=en)）：

> "Historically, Android has only supported 4 KB memory page sizes... Beginning with Android 15, AOSP supports devices that are configured to use a page size of 16 KB (16 KB devices)... **Without recompiling, apps won't work on 16 KB devices in future Android releases.**"

> "**Google Play compatibility requirement** — ...all apps targeting Android 15 (API level 35) and higher must support 16 KB memory page sizes on 64-bit devices on Google Play. **Starting February 1, 2027**, if your app updates don't support 16 KB memory page sizes, you won't be able to release these updates."

Android 16 兼容模式原文（[behavior-changes-all](https://developer.android.com/about/versions/16/behavior-changes-all?hl=en)）：

> "**16 KB page size compatibility mode** — Android 15 introduced support for 16 KB memory pages... **Android 16 adds a compatibility mode**, allowing some apps built for 4 KB memory pages to run on a device configured for 16 KB memory pages. When your app is running on a device with Android 16 or higher, if Android detects that your app has 4 KB aligned memory pages, **it automatically uses compatibility mode and display a notification dialog to the user**. Setting the `android:pageSizeCompat` property in the AndroidManifest.xml... will prevent the display of the dialog... For best performance, reliability, and stability, your app should still be 16 KB aligned."

兼容模式的两个前提（[page-sizes](https://developer.android.com/guide/practices/page-sizes?hl=en)）：

> "The package manager runs an app in 16 KB backcompat mode when the following conditions are met: If the app has ELF files (with an .so extension) with a **LOAD segment alignment of 4 KB**. If the zipped APK has **uncompressed ELF files that are 4 KB ZIP aligned**."

⚠️ **关键推论**：若为了「稳妥」把 `useLegacyPackaging = true`（压缩 .so）与 16 KB ZIP 对齐混用，可能**同时不满足**兼容模式的两个条件而直接崩。兼容模式只认「4 KB ELF + 4 KB ZIP 对齐」。

### 1.2 哪些设备默认/可切换 16 KB 页

官方列出的「开发者选项可切换 Boot with 16KB page size」设备（[page-sizes](https://developer.android.com/guide/practices/page-sizes?hl=en)）：

- Pixel 8 / 8 Pro（Android 15 QPR1+）
- Pixel 8a（Android 15 QPR1+）
- Pixel 9 / 9 Pro / 9 Pro XL（Android 15 QPR2+）
- Pixel 9a（Android 16+）

注意：这是**开发者选项可启用**名单，不等于「出厂即 16 KB 内核」；文档说明使用前需在 Settings > System > Software updates 打齐更新。验证命令（官方）：

```bash
adb shell getconf PAGE_SIZE    # 期望 16384
adb shell setprop bionic.linker.16kb.app_compat.enabled true   # 强制全局兼容模式开
adb shell setprop pm.16kb.app_compat.disabled false
```

（Android 17 起还可设 `bionic.linker.16kb.app_compat.enabled fatal`，让不兼容二进制立即 abort。）

### 1.3 Play 时间线为何有两个日期

- 2025-05-08 官方博客："**Starting November 1st, 2025**, all new apps and updates... must support 16 KB page sizes."（[blog](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html)）——这正是 Android Studio / Play Console 告警文本那句的来源（见 [#735](https://github.com/jiangdongguo/AndroidUSBCamera/issues/735) 正文）。
- 官方 page-sizes 页面（Last updated 2026-09-16）与 [Play Console 技术质量要求](https://support.google.com/googleplay/android-developer/answer/17492799?hl=en) 现口径已是 **February 1, 2027**；Play 页面另单列 Wear OS（2026-09-15）与 TV（2026-08-01）更早强制点。
- **结论**：手机/平板按 **2027-02-01** 排期；不要用旧弹窗文本做依据。

### 1.4 官方修复三件套

1. **Update the packaging of your shared libraries**：AGP **≥ 8.5.1** + 不压缩 .so（`extractNativeLibs=false`）；
2. **Compile your app using 16 KB ELF alignment**：NDK r28+ 默认，或 r27- 加 flag；**预编译库必须同样重编并重新导入**；
3. **Fix code**：移除 `PAGE_SIZE` 硬编码（r27+ 在 16 KB 模式下 `PAGE_SIZE` 已 undef）、改用 `getpagesize()`/`sysconf(_SC_PAGESIZE)`；自查 `mmap` 页对齐参数。

⚠️ AGP 8.3–8.5 的坑（[page-sizes](https://developer.android.com/guide/practices/page-sizes?hl=en)）：

> "In AGP version 8.3 to 8.5, apps are 16 KB aligned by default. **However, bundletool does not zipalign APKs by default. So, the app may appear to work, but when built from a bundle in Play, it won't install.**"
## 2. 缓解措施核实（逐条「能/不能」）

### 2.1 NDK r28+ 默认 16 KB 对齐 —— ✅ 属实

原文（[page-sizes](https://developer.android.com/guide/practices/page-sizes?hl=en)）：

> "**Android NDK r28 and higher** — NDK version r28 and higher compile 16 KB-aligned by default."
> "**Android NDK r27 and lower** — ...use the following linker flags: `-Wl,-z,max-page-size=16384` `-Wl,-z,common-page-size=16384`"

- ndk-build：`LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`（或写在 `Application.mk` 的 `APP_LDFLAGS`）
- CMake：`target_link_options(<target> PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")`（CMake < 3.13 退化为 `set_target_properties(... LINK_FLAGS ...)`）

附带条件：即便用 r28，**预编译库不会自动变对齐** —— "If your app uses any prebuilt shared libraries, you must also recompile them in the same way and reimport the 16 KB-aligned libraries into your app."

### 2.2 对已有 4 KB .so「原地重对齐」—— ❌ 不能（有实测依据）

- **官方立场**：只提供「重新编译」，无「重对齐工具」章节；验证工具仅 `check_elf_alignment.sh`、`llvm-objdump -p ... | grep LOAD`、`zipalign -v -c -P 16 4` 三种（均为**检查**用途），[page-sizes](https://developer.android.com/guide/practices/page-sizes?hl=en)。
- **本机实测**：NDK r28.2 的 `llvm-objcopy --help` 中与对齐相关的只有 `--set-section-alignment`；执行重对齐直接失败：

```
llvm-objcopy.exe: error: unknown argument '--align-segment=0x4000'
```

- **patchelf**：上游能力是改 interpreter/rpath/soname/段属性，**没有**「把既有 LOAD 段 p_align 提到 16 KB 并重排文件布局」的功能；即便强行改 `p_align` 字段，文件内偏移与重定位也不会随之自洽，属不可靠改法（未找到任何出自主流项目/官方的成功案例）。
- **结论**：**不存在可用的官方或社区「原地重对齐」路径，唯一正解是重新编译**。少数项目以「替换为已对齐的预编译 .so」达到同样效果（nolte 的 [PR #19](https://github.com/nolte/kamerplanter-android/pull/19) 即此思路），但那本质是**换库**，不是重对齐。

### 2.3 Gradle 侧配置能否规避 —— ❌ 改不了 ELF；只能改「安装/上架」层面

| 手段 | 作用层面 | 能否解决 16 KB 崩溃 | 说明 |
|---|---|---|---|
| `useLegacyPackaging = true` / `extractNativeLibs=true` | APK 内 .so **是否压缩** | ❌ | 只改压缩方式；且会破坏兼容模式的「4 KB ZIP 对齐」前提，官方明确警告增大占用、更易安装失败 |
| `zipalign -P 16` / AGP ≥ 8.5.1 | **ZIP 内** 16 KB 对齐 | ❌ | 影响安装校验（ZIP 对齐），不是 ELF 段对齐；两者必须同时满足 |
| `android:pageSizeCompat`（Android 16 SDK） | 关闭兼容模式**弹框** | ❌（只消提示） | 不改变「仍是 4 KB」的事实；官方仍建议做到 16 KB |
| 只升 AGP/NDK 但保留预编译 .so | 打包工具链 | ❌ | 预编译库必须重编（见 2.1） |

官方 ZIP 对齐检查：

```bash
zipalign -c -P 16 -v 4 app.apk
bundletool dump config --bundle=my.aab | grep alignment   # PAGE_ALIGNMENT_16K / PAGE_ALIGNMENT_4K
```

---

## 3. AUSBC 适配现状（issues / PR / fork）

### 3.1 上游 issue 清单

| Issue | 标题 | 开 issue | 状态 | 结论 |
|---|---|---|---|---|
| [#735](https://github.com/jiangdongguo/AndroidUSBCamera/issues/735) | Support 16 KB page sizes - Google Play compatibility requirement | 2025-07-18 (AudisoDev) | **Closed**（2026-03-24，因 #753 被认定 completed） | 最早、最完整的报错清单；评论中出现「换库到其他 UVC 项目」的绕行建议 |
| [#744](https://github.com/jiangdongguo/AndroidUSBCamera/issues/744) | Support 16 KB Size | 2025-09-24 (chiragjain1991) | **Open** | 同一问题复报（含 x86_64 与第三方 libmmkv/libglide-webp） |
| [#745](https://github.com/jiangdongguo/AndroidUSBCamera/issues/745) | 16 kb issue in google play console | 2025-09-24 (ogha) | **Open** | 同上 |
| [#746](https://github.com/jiangdongguo/AndroidUSBCamera/issues/746) | 16KB Page Size Support update required... | 2025-09-24 (shyam6764) | **Open** | 同上 |

issue 正文贴出的 Android Studio 报错原文（[#735](https://github.com/jiangdongguo/AndroidUSBCamera/issues/735)）：

> "APK app-debug.apk is not compatible with 16 KB devices. Some libraries have LOAD segments not aligned at 16 KB boundaries:
> lib/arm64-v8a/**libUACAudio.so** lib/arm64-v8a/**libUVCCamera.so** lib/arm64-v8a/**libjpeg-turbo1500.so** lib/arm64-v8a/**libnativelib.so** lib/arm64-v8a/**libusb100.so** lib/arm64-v8a/**libuvc.so**
> Starting November 1st, 2025..."

（上游清单里多一个 **libUACAudio.so** —— 你的 AAR 没有它，但它同样未对齐，见第 4 节路径清单。）

### 3.2 修复 PR / 分支

**PR [#753](https://github.com/jiangdongguo/AndroidUSBCamera/pull/753)**（siddhesh-bhadale，2026-03-14 提交，**至今未合并、无 review**），描述要点：

- Gradle 6.7.1 → 8.13；**NDK 27 / SDK 36 / Kotlin 2.0.21**；
- **删除 jniLibs 下的预编译 .so**，改为 `externalNativeBuild` 从源码编译；
- 开启 `ANDROID_SUPPORT_16K_PAGES=true`；
- 重新生成 `libuvc-4.0.0.aar` / `libnative-4.0.0.aar` / `libausbc-4.0.0.aar`；
- 因 Gradle/NDK 升级冲突，**demo app 模块被临时禁用**；
- 实测设备：Realme Narzo N55 / Narzo 80x / Samsung S24 Ultra / Galaxy A15。

该 PR 声称 Fixes #744 #745 #746 #735；[#735](https://github.com/jiangdongguo/AndroidUSBCamera/issues/735) 已被其关闭，但**代码未进 master**（master 最新提交仍为 2024-09-02 #668）。

**社区 fork 修复（有可用成品）**：

- **nolte/AndroidUSBCamera**，tag `3.2.7-16kb.1` / `3.2.7-16kb.2`（均 **2026-08-14**），tag 说明："libuvc 3.2.7 lineage with 16 KB-aligned native libraries"。JitPack 坐标 `com.github.nolte.AndroidUSBCamera:libuvc:3.2.7-16kb.2`。其 `libuvc/src/main/jni/Application.mk` 实测内容：

```make
APP_PLATFORM := android-21
APP_ABI := arm64-v8a armeabi-v7a x86_64
APP_OPTIM := release
APP_LDFLAGS += -Wl,-z,max-page-size=16384
```

  其 `libnative/src/main/cpp/CMakeLists.txt` 用 `set_target_properties(nativelib PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384")`（注释说明因 JitPack 上 CMake < 3.13，不用 `target_link_options`）。
- 消费方 **nolte/kamerplanter-android** 的 [PR #19](https://github.com/nolte/kamerplanter-android/pull/19)（2026-08-14 合并）给出两个**极有价值的工程陷阱**：
  1. "**libuvc/build.gradle sets no jniLibs.srcDirs override, so AGP packages src/main/jniLibs while the ndkBuild task writes to src/main/libs. The committed prebuilt binaries were what actually shipped; the source build ran past them.**" → **只加 flag 不删预编译 .so，等于没改**。
  2. "**libUACAudio.so is removed in the fork. Its sources are absent upstream, so it cannot be rebuilt.**" → 该库**无源码可重编**，只能删除或换库。

---
## 4. 源码自行构建 AUSBC 的可行性

### 4.1 仓库真实结构（本机 `git clone --depth 1` 实测，commit `426ed0a5da2c826197761cc5a89ba3ae9c52bd7c`，2024-09-02）

```
AndroidUSBCamera/
├── app/              演示 App
├── libausbc/         Kotlin 门面层（CameraFragment / CameraActivity / ...）
├── libnative/        CMake 项目 → libnativelib.so（lame + yuv + mp3 代理）
└── libuvc/           ★ 关键：Java/Kotlin USB 层 + ndk-build 原生层
    ├── build.gradle
    ├── aar/libuvc-3.2.9.aar                                        ← 历史遗留 AAR（未清理）
    ├── src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/*.so    ← ★ 预编译 4 KB 库（随仓库提交）
    └── src/main/jni/
        ├── Android.mk / Application.mk
        ├── UVCCamera/Android.mk            → libUVCCamera.so
        ├── libjpeg-turbo-1.5.0/Android.mk  → libjpeg-turbo1500.so
        ├── libusb/android/jni/Android.mk   → libusb100.so
        ├── libuvc/android/jni/Android.mk   → libuvc.so
        └── (libUACAudio 源码不存在，仅 jniLibs 内有 .so)
```

`settings.gradle` 实测仅含 `:app :libausbc :libuvc :libnative`；**仓库根没有 `libpush` 模块**（该模块属于其他历史版本/分支）。

### 4.2 构建系统：两套并存

- **libuvc = ndk-build（Android.mk）**：`libuvc/build.gradle` 用 `task ndkBuild(type: Exec)` 直接调 `$NDK_HOME/ndk-build -C src/main`（取 `NDK_HOME` 或 `local.properties` 的 `ndk.dir`）。**这不是 AGP 的 `externalNativeBuild { ndkBuild }`**，而是自定义 Exec 任务。
- **libnative = CMake**：`externalNativeBuild.cmake.path = src/main/cpp/CMakeLists.txt`。

### 4.3 是否显式设置 `-Wl,-z,max-page-size=16384`？—— **没有**

整仓 grep（`ANDROID_SUPPORT_16K|max-page-size|common-page-size|useLegacyPackaging|extractNativeLibs`）：**0 命中**。对照实测：

| 文件 | 原版（jiangdongguo master） | nolte tag 3.2.7-16kb.2 |
|---|---|---|
| `libuvc/src/main/jni/Application.mk` | `APP_PLATFORM := android-14`；`APP_ABI := arm64-v8a armeabi-v7a x86 x86_64`；**无 LDFLAGS** | `APP_PLATFORM := android-21`；`APP_LDFLAGS += -Wl,-z,max-page-size=16384` |
| `libnative/src/main/cpp/CMakeLists.txt` | 无对齐 flag | `set_target_properties(nativelib PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384")` |
| 根 `build.gradle` | AGP **4.2.2**、Kotlin 1.5.20、`versionNameString = '3.3.3'`（注意：tag/README 发的是 3.2.7） | 同上游（fork 只在 tag 里改 native） |

### 4.4 改造点清单（具体文件路径）与工作量

| # | 文件 | 动作 | 必需 |
|---|---|---|---|
| 1 | `libuvc/src/main/jni/Application.mk` | 加 `APP_LDFLAGS += -Wl,-z,max-page-size=16384`（一处覆盖 ndk-build 全部 .so）；或分别在各 `Android.mk` 加 `LOCAL_LDFLAGS` | ★ |
| 2 | `libuvc/src/main/jni/{UVCCamera,libusb/android/jni,libuvc/android/jni,libjpeg-turbo-1.5.0}/Android.mk` | 若不用 #1，则逐个加 `LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384` | ★（与 #1 二选一） |
| 3 | `libnative/src/main/cpp/CMakeLists.txt` | 加 `set_target_properties(nativelib PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384")` | ★ |
| 4 | `libuvc/src/main/jniLibs/**/*.so`（4 ABI × 5 个） | **删除**预编译库，否则 AGP 打的是它们而不是源码产物（nolte 实测教训） | ★★ 关键 |
| 5 | 同上目录 `libUACAudio.so` | **无源码可重编** → 删除；若业务依赖 UAC 音频，需换方案或自行移植源码 | ★（若用音频） |
| 6 | 根 `build.gradle` | AGP 4.2.2 → **≥ 8.5.1**（配合 ZIP 对齐与 bundletool）；同步 Gradle wrapper。**连锁触发**：`jcenter()` 已停服、Kotlin 1.5.20 偏旧、`libausbc` 的 `compileSdkVersion 31`/`targetSdkVersion 31` 需提升 | ★★ 工作量主要在此 |
| 7 | `libuvc/build.gradle` | 建议把自定义 `ndkBuild` Exec 换成标准 `externalNativeBuild { ndkBuild { path 'src/main/jni/Android.mk' } }` + `defaultConfig.ndk { abiFilters ... }`；或显式设置 `sourceSets.main.jniLibs.srcDirs` 指向 `src/main/libs` | ☆ 推荐 |
| 8 | `libnative/build.gradle` | `externalNativeBuild.cmake.arguments "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"`（可选，与 flag 等效） | ☆ 可选 |
| 9 | AndroidManifest.xml | 若临时靠兼容模式兜底，可加 `android:pageSizeCompat="enabled"`（需 compileSdk 36）消除弹框；**非治本** | ☆ 兜底 |

**工作量评估**（本机 NDK r28.2 已存在，见附录 A）：

- **最小改动**（#1 + #3 + #4，保持 AGP 4.2.2）：**0.5 人日**可编出 16 KB 对齐的 .so。风险：AGP 4.2.2 + 旧 Gradle 在现代 Android Studio/JDK 上可能直接跑不起来。
- **推荐改动**（含 #5~#7，AGP ≥ 8.5.1）：**1~3 人日**（不含业务联调）。不确定性主要在 jcenter 依赖替换、Kotlin/Gradle 版本摩擦、以及 #753 提到的「demo app 需单独修」。
- **实测参考**：PR #753 作者走的正是「删预编译 + 全源码重编 + NDK27」这条路。

---

## 5. 备选方案评估

> 「是否 16 KB 对齐」：**★ 实测** = 本机下载其官方产物用 llvm-readelf 验证；**○ 源码已配** = 仅源码含 flag、未验证发布产物；**未验证** = 无证据。

| 方案 | 真实坐标 / 仓库 | 最近提交·发布 | ABI | 16 KB 对齐 | 集成难度 | 备注 |
|---|---|---|---|---|---|---|
| **ernestp/AndroidUSBCamera** | JitPack `com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0`（模块 libausbc/libuvc/libnative，groupId=com.github.ernestp） | master 最新提交 **2026-05-09**（`09df386`，PR #18 UseMediaStore）；tag 到 **3.6.0** | armeabi-v7a, arm64-v8a, x86, x86_64（4 ABI，免本地 NDK） | **★ 实测 arm64-v8a 与 x86_64 全部 LOAD=0x4000**；libjpeg-turbo 升级为 `libjpeg-turbo313.so` | **最低**：与 AUSBC 同 API（`com.jiangdg.*`），换坐标即可 | 根 `build.gradle`：`ndkVersion='28.1.13356709'`、minSdk 23、targetSdk 36、`versionNameString='3.6.0'`；libnative 同时设 `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`；**仓库中仍存 2 个历史 AAR**（`libuvc/aar/libuvc-3.2.9.aar`、`libnative/aar/libnative-3.2.9.aar`），虽无引用，建议下游用 APK Analyzer 复核实际打包内容 |
| **nolte/AndroidUSBCamera** | JitPack `com.github.nolte.AndroidUSBCamera:libuvc:3.2.7-16kb.2` | tag **2026-08-14**（`3.2.7-16kb.1`/`.2`）；master 仍是上游 2024-09-02 | `arm64-v8a armeabi-v7a x86_64`（**无 x86**） | ○ 源码已配（`APP_LDFLAGS += -Wl,-z,max-page-size=16384`；libnative 走 CMake `LINK_FLAGS`）；消费方 [PR #19](https://github.com/nolte/kamerplanter-android/pull/19) 称已在 Pixel 7a 消除 PageSizeMismatchDialog | 中：**包名从 `com.serenegiant.usb.*` 迁到 `com.jiangdg.usb.*`**，import 需全量替换（作者称 API 不变）；`libUACAudio.so` 已移除 | 附加价值：作者加了 `scripts/check_page_alignment.py` 作 CI 门禁 |
| **herohan:UVCAndroid**（Maven Central） | Maven Central `com.herohan:UVCAndroid:1.0.13` | GitHub `herohan/UVCAndroid` **404**；同名项目现由 [shiyinghan/UVCAndroid](https://github.com/shiyinghan/UVCAndroid) 维护（最新提交 **2026-06-24**，`fcdae5f`） | arm64-v8a, armeabi-v7a, x86, x86_64 | **★ 实测 1.0.13 全部 ABI 全部 LOAD=0x4000**（`libjpeg-turbo212.so`/`libusb1.0.so`/`libuvc.so`/`libUVCCamera.so`/`libyuv.so`） | 中高：**API 与 AUSBC 不同**，需按 [README](https://github.com/shiyinghan/UVCAndroid/blob/main/README.md) 重写集成代码 | 坐标提示：Maven Central artifactId 为 `UVCAndroid`；`jitpack.io/com/github/shiyinghan/UVCAndroid/...` **实测 404**，不要臆造 JitPack 坐标 |
| **shiyinghan/UVCAndroid**（源码） | [github.com/shiyinghan/UVCAndroid](https://github.com/shiyinghan/UVCAndroid) | 最新提交 **2026-06-24**（`fcdae5f`，PR #148） | 源码层面 4 ABI | ○ 源码已配：`libusb/android/jni/libusb.mk`、`UVCCamera/Android.mk`、`libjpeg-turbo/Android.mk` 均含 `LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384` / `common-page-size=16384`；AGP **8.5.1** | 同上（API 不同）；走源码构建则不再「免 NDK」 | README 指向 Maven Central 的 `com.herohan:UVCAndroid` 作为依赖 |
| **saki4510t/UVCCamera** | [github.com/saki4510t/UVCCamera](https://github.com/saki4510t/UVCCamera) | **最后提交 2018-10-02**（"update dependancy and AS 3.1.x"），139 commits；500 open issues / 21 PR | 由 `Application.mk` 决定 | **未验证（几乎必然否）**：无任何 16 KB 配置，停留在 `android-14` 时代 | 高 | **已停滞 8 年**，仅作 AUSBC/libuvc 血统参考 |
| **duvitech-llc/AndroidUSBCamera** | [github.com/duvitech-llc/AndroidUSBCamera](https://github.com/duvitech-llc/AndroidUSBCamera) | 页面显示 **1 Commit**、1 star、无最近提交日期 | — | **未验证** | 高 | 极早期快照，**不可作为 16 KB 适配来源** |
| **suethan/AndroidUSBCamera** | [github.com/suethan/AndroidUSBCamera](https://github.com/suethan/AndroidUSBCamera) | 44 stars / 25 commits；页面无近期日期 | — | **未验证** | 高 | 老分支（含 `libuvccamera` 模块），未见 16 KB 改动 |
| **其他 fork** | [network/members](https://github.com/jiangdongguo/AndroidUSBCamera/network/members) | — | — | **未验证** | 中 | 建议按 4.4「删预编译 + 加 flag」自行处理，比逐个 fork 试错更省 |
| **CameraX / Camera2 EXTERNAL（走系统 Camera HAL）** | [AOSP：外接 USB 摄像头](https://source.android.com/docs/core/camera/external-usb-cameras)（镜像 [source.android.google.cn](https://source.android.google.cn/docs/core/camera/external-usb-cameras)） | 平台能力 | 不涉及 | 不涉及 | — | AOSP 原文明确：「**此功能不能替代 Android 手机上典型的内部相机 HAL**，也不是为了协助执行涉及高分辨率和高速流式传输、AR 以及手动 ISP/传感器/摄像头控制且对性能要求很高的复杂任务」，且需设备侧 `external camera provider` 被启用；**绝大多数手机未启用，不可依赖** |
| **「V4L2 直通 / 2024-2026 新 Kotlin 方案」** | — | — | — | — | — | **未发现**可替代的成熟新方案：公开检索（GitHub 仓库搜索、多引擎）未命中「16 KB 对齐 + 活跃维护」的新 UVC 库；主流仍是 saki 血统（AUSBC / UVCAndroid / herohan）。**不做无证据推荐。** |

---
## 6. 结论：可靠性排名（Android 15/16 上「OTG UVC 预览可用」）

| 排名 | 路线 | 改造工作量 | 风险 | 适用判断 |
|---|---|---|---|---|
| **1** | **换依赖到 `com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0`**（同 API，免 NDK） | **≈0**（改一行坐标） | 低。唯一需自证：实际 APK 用 `zipalign -c -P 16 -v 4` + APK Analyzer 复核（其 AAR 的 arm64/x86_64 已实测 0x4000）；注意其 3.2.9 历史 AAR 残留 | **首选**。3.6.0 直接给出 4 ABI 且对齐已实测 |
| **2** | **换依赖到 `com.github.nolte.AndroidUSBCamera:libuvc:3.2.7-16kb.2`**（或同思路自建 fork） | 低~中（`com.serenegiant.usb.*` import 改 `com.jiangdg.*`） | 低~中。发布产物对齐未由我直接实测（源码 flag 与消费方 CI 证据充分）；无 x86；`libUACAudio` 已移除，若需 UAC 音频须单独处理 | **次选 / Plan B** |
| **3** | **自行构建 AUSBC 源码**（加 flag + 删预编译 .so） | **1~3 人日**（最小 0.5 人日） | 中。AGP 4.2.2→8.5.1 连锁升级、jcenter 停服、demo 模块需另修；但完全自主可控 | 需要「绝对自控 + 可审计」时选它；可直接借 [PR #753](https://github.com/jiangdongguo/AndroidUSBCamera/pull/753) 的做法 |
| **4** | 靠兼容模式「先跑起来」（Android 16 `pageSizeCompat`，Android 15 手动 `setprop`） | ≈0 | **高**：仅 Android 16+ 自动；Android 15 设备很可能直接 segfault（我方 libUVCCamera RELRO 余数=0x1000）；且 Play 2027-02-01 前仍必须修 | **只可当临时验证手段，不可作为交付方案** |
| **5** | 整体换 `com.herohan:UVCAndroid:1.0.13`（Maven Central） | 中高（API 不同，需重写集成层） | 中。产物对齐已实测，但上游 GitHub 已 404、维护主体不明 | 当 1/2 都不可用时的备选 |
| **6** | saki4510t/UVCCamera 或 duvitech-llc / suethan fork | 高 | **高**：停更 8 年 / 快照级 fork，无 16 KB 适配 | **不推荐** |
| **7** | CameraX/Camera2 EXTERNAL | — | **极高（设备侧不可控）** | **排除** |

---

## 风险与缓解措施表

| # | 风险 | 触发条件 | 影响 | 缓解措施 |
|---|---|---|---|---|
| 1 | 16 KB 设备上 native 段错误 | Android 15 且未进兼容模式；或 RELRO 未对齐 | 预览崩溃/黑屏 | 换 16 KB 对齐产物（路线 1/2）或自编（路线 3）；用 `adb shell getconf PAGE_SIZE` 确认测试机状态 |
| 2 | Play 更新被拒 | 2027-02-01 后提交且含 4 KB .so（targetSdk≥35） | 无法发版 | 截止前完成对齐；先用 `zipalign -c -P 16 4` + APK Analyzer 自查 |
| 3 | **「只改 flag 不删预编译 .so」导致白改** | `libuvc/src/main/jniLibs/**` 仍在（原版实测 4 ABI × 5 个 .so） | 打包的仍是不对齐旧库 | 删除 `jniLibs` 下预编译库，或确认 AGP 实际打包源（nolte 实测：`ndkBuild` 写 `src/main/libs`，AGP 打 `src/main/jniLibs`） |
| 4 | 兼容模式「假绿」 | 依赖 Android 16 自动兼容 | 弹框骚扰；Android 17 起可控变 `fatal` | 把兼容模式当临时手段；`android:pageSizeCompat` 只用于消提示 |
| 5 | `useLegacyPackaging=true` 反而破坏兼容模式前提 | 压缩 .so 导致 ZIP 非 4 KB 对齐 | 既不满足 16 KB 又不满足兼容模式条件 | 不用压缩去「规避」；正解是 AGP ≥ 8.5.1 + 不压缩 + 16 KB ELF |
| 6 | 传输/音频能力缺失 | `libUACAudio.so` 无源码（上游）、nolte fork 已删除 | UAC 音频（设备麦克风）不可用 | 明确业务是否需要 UAC；需要则改用含源码的方案（UVCAndroid 系）或自行移植 |
| 7 | AGP 升级连锁失败 | AGP 4.2.2 → 8.5.1、`jcenter()` 停服、Kotlin 1.5.20 | 构建阻塞 | 参照 [PR #753](https://github.com/jiangdongguo/AndroidUSBCamera/pull/753) 的完整升级组合（Gradle 8.13 / SDK 36 / Kotlin 2.0.21 / NDK 27），接受 demo 模块需单独修 |
| 8 | fork 供应链风险 | ernestp / nolte 均为个人 fork | 后续停更、被 Play 判为问题 SDK | 把 16 KB flag + 删预编译库的改动**沉淀到自己仓库**（fork → vendor），不长期依赖个人 JitPack |
| 9 | 设备侧 `external camera provider` 不可用 | 走 CameraX/Camera2 EXTERNAL | 路由不通 | 不使用该路线（见第 5 节 AOSP 原文） |
| 10 | ⚠️ 本报告未覆盖：预览性能/供电/热 | OTG UVC 实际跑流 | 掉帧、掉线、无法枚举 | 需单独做**真机 16 KB 设备实测**（Pixel 8/9 开发者选项 Boot with 16KB page size）；本报告只解决「能否加载与上架」 |

---

## 附录 A：本机实测命令与产物（可复现）

```powershell
# 工具链（本机已存在，无需新装）
$bin = 'D:/Data/SDK/Android/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin'

# 1) 原版 AUSBC 3.2.7（JitPack）—— 4 KB 对齐
#    https://jitpack.io/com/github/jiangdongguo/AndroidUSBCamera/libuvc/3.2.7/libuvc-3.2.7.aar
& "$bin/llvm-readelf.exe" -l .../libuvc/jni/arm64-v8a/libUVCCamera.so | Select-String LOAD
#    → LOAD ... 0x1000 / 0x1000 / 0x1000

# 2) ernestp 3.6.0（JitPack）—— 16 KB 对齐
#    https://jitpack.io/com/github/ernestp/AndroidUSBCamera/libuvc/3.6.0/libuvc-3.6.0.aar
#    https://jitpack.io/com/github/ernestp/AndroidUSBCamera/libnative/3.6.0/libnative-3.6.0.aar
& "$bin/llvm-readelf.exe" -l .../arm64-v8a/libUVCCamera.so | Select-String LOAD
#    → LOAD ... 0x4000 / 0x4000 / 0x4000

# 3) herohan（Maven Central）—— 16 KB 对齐
#    https://repo1.maven.org/maven2/com/herohan/UVCAndroid/1.0.13/UVCAndroid-1.0.13.aar
#    → 全部 ABI 全部 LOAD = 0x4000

# 4) RELRO 判定（官方公式 (VirtAddr+MemSiz) % 0x4000 == 0）
& "$bin/llvm-readelf.exe" -Wl <lib>.so | Select-String GNU_RELRO
#    AUSBC 3.2.7 arm64:
#      libUVCCamera      0x283C8 + 0xC38  = 0x29000 → rem 0x1000（未对齐）
#      libusb100         0x1A6A8 + 0x958  = 0x1B000 → rem 0x3000（未对齐）
#      libjpeg-turbo1500 0x512E0 + 0xD20  = 0x52000 → rem 0x2000（未对齐）
#      libUACAudio       0x5C10  + 0x3F0  = 0x6000  → rem 0x2000（未对齐）
#      libuvc            0x178B0 + 0x750  = 0x18000 → rem 0（对齐）

# 5) 重对齐工具不存在（实测）
& "$bin/llvm-objcopy.exe" --align-segment=0x4000 in.so out.so
#    → error: unknown argument '--align-segment=0x4000'
```

证据落盘目录（本次会话产出）：

- `D:/Data/Work/Project/AUSBC/evidence/repos/` —— AUSBC(426ed0a)、ernestp(09df386)、shiyinghan(fcdae5f) 浅克隆
- `D:/Data/Work/Project/AUSBC/evidence/jitpack/` —— 原版 3.2.7、ernestp 3.6.0 的 AAR 及解包结果
- `D:/Data/Work/Project/AUSBC/evidence/herohan/` —— herohan 1.0.13 AAR 及解包结果
- `D:/Data/Work/Project/AUSBC/evidence/pages/` —— 官方文档与各 issue/PR 页面正文快照（txt）

## 附录 B：来源清单

**官方**
- Support 16 KB page sizes — https://developer.android.com/guide/practices/page-sizes?hl=en （镜像 https://developer.android.google.cn/guide/practices/page-sizes?hl=zh-cn ）
- Android 15 行为变更（Support for 16 KB page sizes）— https://developer.android.com/about/versions/15/behavior-changes-all?hl=en
- Android 16 行为变更（16 KB page size compatibility mode）— https://developer.android.com/about/versions/16/behavior-changes-all?hl=en
- Play Console 技术质量要求 — https://support.google.com/googleplay/android-developer/answer/17492799?hl=en
- AOSP：外接 USB 摄像头 — https://source.android.com/docs/core/camera/external-usb-cameras
- Android Developers Blog（2025-05-08，November 1st 2025 旧口径）— https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html
- Transition to using 16 KB page sizes... Android Studio（2025-07-10）— https://android-developers.googleblog.com/search/label/16%20KB%20page%20size

**AUSBC issues / PR**
- #735 — https://github.com/jiangdongguo/AndroidUSBCamera/issues/735
- #744 — https://github.com/jiangdongguo/AndroidUSBCamera/issues/744
- #745 — https://github.com/jiangdongguo/AndroidUSBCamera/issues/745
- #746 — https://github.com/jiangdongguo/AndroidUSBCamera/issues/746
- PR #753 — https://github.com/jiangdongguo/AndroidUSBCamera/pull/753
- 上游仓库 — https://github.com/jiangdongguo/AndroidUSBCamera
- forks 网络 — https://github.com/jiangdongguo/AndroidUSBCamera/network/members

**候选方案**
- ernestp fork — https://github.com/ernestp/AndroidUSBCamera （JitPack https://jitpack.io/#ernestp/AndroidUSBCamera ）
- nolte fork — https://github.com/nolte/AndroidUSBCamera ；tag https://github.com/nolte/AndroidUSBCamera/tags ；消费方 PR https://github.com/nolte/kamerplanter-android/pull/19
- shiyinghan/UVCAndroid — https://github.com/shiyinghan/UVCAndroid
- herohan:UVCAndroid（Maven Central）— https://central.sonatype.com/artifact/com.herohan/UVCAndroid
- saki4510t/UVCCamera — https://github.com/saki4510t/UVCCamera ；提交历史 https://github.com/saki4510t/UVCCamera/commits/master
- duvitech-llc/AndroidUSBCamera — https://github.com/duvitech-llc/AndroidUSBCamera
- suethan/AndroidUSBCamera — https://github.com/suethan/AndroidUSBCamera

---

### 交付建议（一句话）

**把依赖从 `com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7` 换成 `com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0`（同 API、免 NDK、4 ABI、已实测 16 KB 对齐），随后在自己 APK 上用 `zipalign -c -P 16 -v 4` + APK Analyzer 复验；若需完全自控，则按 nolte/PR #753 的路径自建：加 `max-page-size=16384` + **删除 jniLibs 预编译 .so** + AGP ≥ 8.5.1。**
