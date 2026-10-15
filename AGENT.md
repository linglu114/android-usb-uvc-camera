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
9. **横竖屏用系统自动旋转，且 `screenOrientation` 必须是 `unspecified`**（不要用 `fullSensor`）。实测 `fullSensor` 会在启动期发生方向抖动，TextureView 重建 Surface 而渲染器仍绑旧 Surface，整片预览透明（像素为背景色而非黑帧）；且它忽略用户的旋转锁定，语义也不符。
10. **旋转后只刷新渲染尺寸，绝不可重注册相机**：`ICamera.setRenderSize` 内部是 `mSizeChangedFuture?.set(...)`（`MultiCameraClient.kt:592`），该一次性 Future 在相机打开阶段已消费，二次设置无效 → GL viewport 停在旧尺寸。而 `unRegisterMultiCamera()+registerMultiCamera()` 会走 `onAttachDev → requestPermission`，`USBMonitor` 在 Android 9+ 会**先撤销已有权限再申请** → 必然弹授权框（实测）。正解：反射调用公开的 `RenderManager.setRenderSize(w,h)`，它只投递 `MSG_GL_CHANGED_SIZE` 更新 `mScreenRender/mCameraRender` 尺寸（`RenderManager.kt:130-139`），完全不碰 USB。实测旋转期间权限日志 0 条、横屏内容框 1917×1056。
11. **挖孔（displayCutout）避让只在横屏生效**：竖屏时挖孔位于顶部状态栏区域，不做横向避让。
12. **横屏是沉浸态**：隐藏应用控件**与**系统状态栏（`WindowInsetsControllerCompat.hide(systemBars)`）；点击屏幕唤出控件并显示系统栏，5 秒后再次隐藏。竖屏始终显示系统栏。
13. **分辨率只允许用户显式选择**（BottomSheet 列表），不做任何自动切换或轮换。
14. **代码动态创建的 Material 控件必须与主题父级匹配**：本主题受库限制固定在 `Theme.MaterialComponents.*`，故不要用 M3 专有控件（如 `MaterialSwitch`）。实测：`MaterialSwitch` 在该主题下其 `SwitchCompat` 的 `textOn/textOff` 为 null，`onMeasure → makeLayout` 会对它们调 `length()` 而 **NPE 闪退**（`NullPointerException at SwitchCompat.makeLayout:993`）。改用 `SwitchMaterial`，并同时 `showText = false` + `textOn/textOff = ""` 作双保险。
15. **HUD 遮罩与其上文字必须同源跟随主题**：遮罩色与文字色要么都用语义色（跟随 DayNight），要么都固定；**千万不能一边固定一边跟随**。曾经的 bug：遮罩固定深色 `#E60F0F11`，而文字用 `@color/hud_on_surface`，浅色系统下解析成 `#232325`，深字压深底导致实测亮度只剩 38（不可读）。现方案：`hud_bar_scrim` 走语义层（浅色 `#E6F7F6F3` / 深色 `#E60F0F11`），文字用 `hud_on_surface`。
16. **预览舞台（画面 letterbox 区）固定暗色，不跟随主题**：视频周边永远是暗的，浅色主题下变白既刺眼又不专业。`camera_container` 用固定的 `@color/hud_stage`；空/错误态时由代码置为透明，让空状态复用跟随主题的根背景（否则会出现深底深字）。
17. **HUD 底衬禁用渐变**：渐变在亮画面上会形成突兀过渡带（用户明确否掉）。用纯色 drawable；且**不要给 HUD 文字加 shadowRadius 较大的阴影**（4dp 在 420dpi 下会糊掉笔画）。
18. **BottomSheet 是独立窗口，Snackbar 会被它遮挡**：需要在面板内提示用户时，先 `dismissSheet()` 再 `snack(...)`。

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
| 旋转方式 | 系统自动旋转（`unspecified`），应用内无旋转/镜像控件 | 用户决策：竖屏下 180° 无意义 |
| 横屏沉浸 | 隐藏应用控件 + 系统状态栏，点击唤出（5s 自动隐藏） | 用户决策 |
| 分辨率 | 仅用户显式选择，不自动切换 | 用户决策 |
| 挖孔避让 | 仅横屏生效 | 用户决策 + 官方最佳实践 |
| 旋转刷新渲染 | 反射调 `RenderManager.setRenderSize`，不重注册相机 | 库一次性 Future 限制 + 避免 USB 弹窗 |

## 8. 后续可用 skill

代码与 Spec 就绪后，`code-review` skill 可对 `HEAD` 起点做双轴审查：Standards 对照本文件，Spec 对照 `docs/spec/usbcam-v1.md`。
