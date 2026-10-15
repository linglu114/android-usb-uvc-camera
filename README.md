# USB 摄像头（android-usb-uvc-camera）

把手机通过 OTG 接上的 **UVC 摄像头** 或 **HDMI 采集卡**（如 MS2109 采集棒）的画面，实时显示在手机屏幕上。

## 功能（首版）

- 插上设备即弹 USB 授权，授权后自动出画面；单设备自动打开，多设备可选
- 分辨率切换、画面旋转、预览时保持屏幕常亮
- 空 / 加载 / 错误三态齐备，空状态自带排查指引

不做：录像、多路摄像头、音频、后台推流。

## 技术选型

| 项 | 选择 | 理由 |
| --- | --- | --- |
| UVC 引擎 | [ernestp/AndroidUSBCamera](https://github.com/ernestp/AndroidUSBCamera) `libausbc:3.6.0` | 原版 jiangdongguo 已停更（2024-09）；fork 版仍活跃且 **arm64 的 .so 已 16 KB 页对齐**、依赖树干净、已修 Android 12+ 权限崩溃 |
| UI | Android Views + Material Components | 库的 BottomSheet 样式依赖 MaterialComponents 主题父级 |
| 工具链 | Gradle 9.3.1 / AGP 8.10.1 / Kotlin 2.2.10 / JDK 21 | 与所选库自身工程完全对齐，避免版本调试 |

## 构建

```powershell
$env:JAVA_HOME='D:\Program Files\Zulu\zulu-21'
$env:GRADLE_USER_HOME='D:\Data\SDK\gradle-home'
gradlew.bat :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 验收

自动项（无需设备）：

```powershell
tools\check-16k.ps1 -Apk app\build\outputs\apk\debug\app-debug.apk
```

真机项（需连接手机并插入摄像头/采集卡）：安装、出画面、拔插重连、分辨率与旋转切换、暗色与 1.3× 字号截图。

## 常见问题

- **插上没反应**：部分机型需在设置中手动开启 OTG；确认线材支持数据传输而非仅充电。
- **画面反复掉线**：手机 OTG 口供电通常只有数百 mA，采集棒容易超限。请改用带外部供电的 USB Hub 或 Y 型 OTG 线；软件侧可降低分辨率减少带宽。
- **USB 授权弹窗被拒**：重新插拔设备，在系统弹窗中点「允许」；部分国产 ROM 需在权限管理里放行。
- **画面分辨率不对**：用底部控制条切换分辨率；采集卡通常对 MJPEG 支持最好。

## 许可与来源

- 应用代码：本仓库
- USB/UVC 引擎：[AndroidUSBCamera](https://github.com/ernestp/AndroidUSBCamera)（Apache-2.0）。其中 libjpeg-turbo / libusb / libuvc 子目录许可与 Apache-2.0 不同；本项目仅自用、不再分发其二进制。
