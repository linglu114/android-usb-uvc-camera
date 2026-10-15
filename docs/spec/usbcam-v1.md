# Spec — usbcam v1（Android USB 摄像头 / 采集卡预览）

状态：已批准（2026-10-15）。本文件是 `code-review` 的 Spec 轴基准，与 `AGENT.md`（Standards 轴）配合使用。

## 1. 目标

手机经 OTG 接外接 UVC 摄像头或 HDMI 采集卡，画面实时显示在手机屏幕。首版只做预览与其必要控制。

## 2. 范围

**做**：单路预览；USB 授权流程；热插拔；分辨率切换；画面旋转；预览保持屏幕常亮；空/加载/错误三态。

**不做**：录像、拍照、多路摄像头、音频、后台常驻、推流、Splash、Dynamic Color、CameraX/Camera2 EXTERNAL 路线。

## 3. 功能需求（可验收）

| ID | 需求 | 验收方式 |
| --- | --- | --- |
| F1 | App 启动即进入预览屏，无启动页 | 真机截图 |
| F2 | 无设备时显示空状态：标题 + 三条排查指引（OTG 支持 / 插紧设备 / 供电不足用带供电 Hub） | 真机截图 |
| F3 | 插入设备后系统弹出 USB 授权；允许后自动出画面 | 真机操作 |
| F4 | 单设备自动打开；多设备可切换 | 真机操作（多设备按可用性） |
| F5 | 拔下设备回到空状态；重新插入能再次出画面 | 真机拔插 |
| F6 | 可切换分辨率，切换后状态条显示新值 | 真机操作 |
| F7 | 可切换 0/90/180/270 旋转 | 真机操作 |
| F8 | 预览时屏幕保持常亮，可在控制条关闭 | 真机观察 |
| F9 | 打开失败时状态条与 Snackbar 给出可读原因与恢复动作 | 代码审查 + 真机（若有失败场景） |
| F10 | 画面等比显示，不拉伸形变 | 真机截图 + 代码 |

## 4. 非功能需求

| ID | 需求 | 验收方式 |
| --- | --- | --- |
| N1 | `gradle :app:assembleDebug` 在命令行一次成功 | 退出码 0 + APK 存在 |
| N2 | 依赖树不含 immersionbar / webpdecoder / glide / mmkv / libpush | `:app:dependencies` 输出检查 |
| N3 | APK 内 arm64-v8a 全部 .so 的 LOAD 对齐 ≥ 2**14 | `tools/check-16k.ps1` |
| N4 | `zipalign -c -P 16 4` 通过 | 同脚本 |
| N5 | 所有触控目标 ≥ 48dp，间距 ≥ 8dp | 布局审查 |
| N6 | 字号一律 sp；1.3× 系统字号下控制条不裁剪 | 真机切换字号后截图 |
| N7 | 深/浅两套主题均可读 | 真机切换 uimode 后截图 |
| N8 | 内容避开状态栏/手势条（edge-to-edge + insets） | 真机截图 |
| N9 | 单一强调色；无纯黑背景、无渐变文字、无 emoji 图标 | 资源审查 |

## 5. 构建与运行约束

- 工具链：Gradle 9.3.1 / AGP 8.10.1 / Kotlin 2.2.10 / JDK 21。
- SDK：minSdk 23、compileSdk 36、targetSdk 34。
- ABI：仅 `arm64-v8a`。
- 包名 `com.linglu.usbcam`；应用名「USB 摄像头」；界面中文。
- 主题父级固定为 `Theme.MaterialComponents.DayNight.NoActionBar`（理由见 AGENT.md 硬约束 1）。

## 6. 验收命令

```powershell
$env:JAVA_HOME='D:\Program Files\Zulu\zulu-21'
$env:GRADLE_USER_HOME='D:\Data\SDK\gradle-home'
gradle.bat :app:assembleDebug
gradle.bat :app:dependencies --configuration debugRuntimeClasspath
tools\check-16k.ps1 -Apk app\build\outputs\apk\debug\app-debug.apk
```

真机部分（需连接设备）：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell cmd uimode night yes      # 暗色截图
adb shell settings put system font_scale 1.3
adb exec-out screencap -p > evidence\shot-dark-1.3x.png
adb shell settings put system font_scale 1.0
adb shell cmd uimode night no
```

## 7. 已知限制（不作为缺陷）

- 镜像（水平翻转）未实现：AUSBC 无公开 API，控制条上对应按钮已隐藏而非留无效控件。
- 采集卡 UAC 音频不工作（上游已知，首版不做音频）。
- 高分辨率下带宽不足可能掉帧，属供电/带宽的硬件边界，软件侧只提供降分辨率手段。
