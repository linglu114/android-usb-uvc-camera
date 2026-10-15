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
10. **旋转后只刷新渲染尺寸，绝不可重注册相机**：`ICamera.setRenderSize` 内部是 `mSizeChangedFuture?.set(...)`（`MultiCameraClient.kt:592`），该一次性 Future 在相机打开阶段已消费，二次设置无效 → GL viewport 停在旧尺寸。而 `unRegisterMultiCamera()+registerMultiCamera()` 会重建 `MultiCameraClient`/`USBMonitor` 并重走 attach 流程，且 `unRegisterMultiCamera` **不复位 `mRequestPermission`**（`CameraActivity.kt:163-171`），会引入新的死锁路径。（**订正**：此处曾写「USBMonitor 会先撤销已有权限再申请 → 必然弹授权框」，与代码不符——`resetDevicePermission`（`USBMonitor.java:712-750`）只清本库缓存 + 关 ctrlBlock，**不触碰系统级授权**；当时弹窗是因为重装 APK 后系统权限本就不存在。）正解：反射调用公开的 `RenderManager.setRenderSize(w,h)`，它只投递 `MSG_GL_CHANGED_SIZE` 更新 `mScreenRender/mCameraRender` 尺寸（`RenderManager.kt:130-139`），完全不碰 USB。实测旋转期间权限日志 0 条、横屏内容框 1917×1056。
11. **挖孔（displayCutout）避让只在横屏生效**：竖屏时挖孔位于顶部状态栏区域，不做横向避让。
12. **横屏是沉浸态**：隐藏应用控件**与**系统状态栏（`WindowInsetsControllerCompat.hide(systemBars)`）；点击屏幕唤出控件并显示系统栏，5 秒后再次隐藏。竖屏始终显示系统栏。
13. **分辨率只允许用户显式选择**（BottomSheet 列表），不做任何自动切换或轮换。
14. **代码动态创建的 Material 控件必须与主题父级匹配**：本主题受库限制固定在 `Theme.MaterialComponents.*`，故不要用 M3 专有控件（如 `MaterialSwitch`）。实测：`MaterialSwitch` 在该主题下其 `SwitchCompat` 的 `textOn/textOff` 为 null，`onMeasure → makeLayout` 会对它们调 `length()` 而 **NPE 闪退**（`NullPointerException at SwitchCompat.makeLayout:993`）。改用 `SwitchMaterial`，并同时 `showText = false` + `textOn/textOff = ""` 作双保险。
15. **HUD 遮罩与其上文字必须同源跟随主题**：遮罩色与文字色要么都用语义色（跟随 DayNight），要么都固定；**千万不能一边固定一边跟随**。曾经的 bug：遮罩固定深色 `#E60F0F11`，而文字用 `@color/hud_on_surface`，浅色系统下解析成 `#232325`，深字压深底导致实测亮度只剩 38（不可读）。现方案：`hud_bar_scrim` 走语义层（浅色 `#E6F7F6F3` / 深色 `#E60F0F11`），文字用 `hud_on_surface`。
16. **预览舞台（画面 letterbox 区）固定暗色，不跟随主题**：视频周边永远是暗的，浅色主题下变白既刺眼又不专业。`camera_container` 用固定的 `@color/hud_stage`；空/错误态时由代码置为透明，让空状态复用跟随主题的根背景（否则会出现深底深字）。
17. **HUD 底衬禁用渐变**：渐变在亮画面上会形成突兀过渡带（用户明确否掉）。用纯色 drawable；且**不要给 HUD 文字加 shadowRadius 较大的阴影**（4dp 在 420dpi 下会糊掉笔画）。
18. **系统栏图标明暗必须按各自背景分别设置**：主题里的 `windowLightStatusBar` 是静态值，无法表达"两个系统栏压在不同背景上"——状态栏压在**跟随主题的 HUD 遮罩**上（浅色主题遮罩变白 → 图标必须转深色），导航栏压在**固定暗色的预览舞台**边缘（图标恒定浅色）。故在 `applySystemBarAppearance()` 里设置 `isAppearanceLightStatusBars = !isDark` / `isAppearanceLightNavigationBars = false`，并在 `onCreate` 与 `onConfigurationChanged` 调用。历史 bug：主题写死 `windowLightStatusBar=false`，浅色主题下白图标压白遮罩看不见。
19. **帧率只能取库的实测值，拿不到 UVC 声明的刷新率**：原生层 `UVCCamera.addSize()` 只把 `"1920x1080"` 按 `x` 切开取宽高（`UVCCamera.java:395-397`），JSON 里不含帧率，故 `Size.fps` 恒为 0.0、Kotlin 的 `PreviewSize` 根本没有该字段。唯一可得的是库的**实测相机帧率**：`RenderManager.emitFrameRate()` 挂在 `onFrameAvailable`（相机 SurfaceTexture 每来一帧调一次），每秒经 `EventBus/BusKey.KEY_FRAME_RATE` 广播一次。应用直接 `EventBus.with<Int>(BusKey.KEY_FRAME_RATE).observe(this)` 即可，零额外开销。实测正确性：1080p→31Hz、720p→61Hz，随分辨率变化。
20. **断开检测必须由应用自己实现**：库的 `CameraActivity.onDetachDec`（注意拼写是 Dec）只做内部清理——`mCameraMap.remove`、`setUsbControlBlock(null)`、`mCurrentCamera = null`——**既不调用 `closeCamera()`，也不回调 `onCameraState(CLOSED)`**，所以拔掉采集卡后界面会一直停在「已连接」。应用需自行注册 `UsbManager.ACTION_USB_DEVICE_DETACHED` 广播，收到后延迟 ~400ms 等库清理完再判断设备列表是否为空，为空则落空状态。
21. **界面显示的分辨率必须是「正在使用的预览尺寸」**：`currentSizeIndex` 不能取列表首项（面积最大项），否则会把 1280×720 的实际预览显示成 1920×1080（帧率也就与分辨率对不上）。`loadPreviewSizes()` 里用 `getCameraRequest().previewWidth/Height` 反查下标。
21. **BottomSheet 是独立窗口，Snackbar 会被它遮挡**：需要在面板内提示用户时，先 `dismissSheet()` 再 `snack(...)`。

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
| 拔卡后界面复位 | `showState()` 统一负责：非预览态清空顶栏元信息（`clearMeta()`）并把 `cameraView` 置 `INVISIBLE`；`refreshMeta()` 亦以当前状态为准 | 真机验收 F7e（`evidence/shot-63/64`） |
| 底表弹出方式 | `showSheet()` 统一封装：内容套 `NestedScrollView`；`behavior.skipCollapsed = true`，`show()` 后置 `STATE_EXPANDED` | 横屏 1080 高时默认 `behavior_peekHeight=auto` 只露出 192px（仅「设置」标题），必须手动上滑才看得到内容（`evidence/shot-71` vs `shot-73`） |

### 已知坑（真机实测）

| 现象 | 根因 | 处置 |
| --- | --- | --- |
| 拔卡后顶栏仍显示设备名/分辨率，且中间留一块纯黑矩形 | 库断开时只回调 `CLOSED`、不再广播帧率，没人擦这两行；`camera_container` 置透明后，库注入的 `AspectRatioTextureView` 仍不透明（无帧=纯黑，实测 1080×607 居中，y 896..1502） | 见上表「拔卡后界面复位」；隐藏用 `INVISIBLE` 而非 `GONE`，保留 Surface 以便重插直接出图 |
| 推流中 `adb shell am force-stop` 后重启，画面全黑、fps 恒为 0（顶栏只有分辨率、无 `@Hz`） | 打断等时流把 MS2109 卡死，重开设备时 `libuvc/device: uvc_open: internal_devh->info->ctrl_if.bEndpointAddress is null`，`get_frame: allocate new frame` 只出现一两次 | 再干净重启一次即可恢复（实测 60/61 fps）；软件侧 `usb/unbind`、`authorized=0` 都**不能**模拟拔插（设备始终留在总线，内核不产生 detach uevent），验证拔插必须物理插拔 |
| 帧率显示为 0 / 缺失 | 帧率上报挂在 `RenderManager.onFrameAvailable`（`RenderManager.kt:359`），无帧可报 | 属正常：出帧才有 `@Hz`，无帧时只显示分辨率 |
| 设置底表左右上角圆角外被填成一块不透明灰（实测 `#3B3B3F`） | 圆角只落在内容容器上：Material 的 `Widget.MaterialComponents.BottomSheet` 给 `design_bottom_sheet` 的底是 `backgroundTint=?attr/colorSurface` + `shapeAppearance=?attr/shapeAppearanceLargeComponent`，后者在 MaterialComponents 下 `cornerSize=0dp`，于是这张**矩形**底盖住了 16dp 圆角之外的角 | `UsbCam.BottomSheet.Modal` 换成 `Widget.MaterialComponents.BottomSheet.Modal` 并显式指定 `backgroundTint=@color/hud_surface_variant` 与自建 `ShapeAppearance.UsbCam.BottomSheet`（上 16dp / 下 0dp）；验收：`evidence/shot-70-sheet-corners.png` 角外取到画面色（左 `#101010`、右 `#1E263E`），不再是单一灰 |
| 排查「这块颜色是谁画的」 | 静态看 XML/样式容易猜错 | 用「给候选视图分别上色」的探针法（`window` / `touch_outside` / `design_bottom_sheet` / 内容容器各给一个纯色）再截图取样。实测：`touch_outside` 铺满状态栏以下整屏，`window` 背景只在状态栏那条露出，圆角外的填充来自 `design_bottom_sheet` 自身 |

## 8. 后续可用 skill

代码与 Spec 就绪后，`code-review` skill 可对 `HEAD` 起点做双轴审查：Standards 对照本文件，Spec 对照 `docs/spec/usbcam-v1.md`。
