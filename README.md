# USB 摄像头

把手机通过 OTG 接上的 **UVC 摄像头** 或 **HDMI 采集卡**（例如 MS2109 采集棒）的画面，实时显示在手机屏幕上。

单 Activity，无后台服务、无网络权限、无统计埋点。

## 功能

- 插上设备自动请求 USB 授权，授权通过后直接出画面
- 顶栏显示设备名、分辨率与实测帧率（如 `1280×720@61Hz`），以及连接状态
- 分辨率列表按设备实际能力生成，只在设置面板里由你显式选择
- 跟随系统自动旋转；横屏自动进入沉浸（隐藏控件与系统状态栏，点一下屏幕唤出）
- 横屏避让挖孔，竖屏保留系统状态栏
- 预览时可保持屏幕常亮，可在设置面板里关掉
- 拔掉设备自动释放相机并回到空状态，重新插上自动重连，不需要重启应用
- 无设备 / 正在打开 / 出错三种状态都有明确提示，空状态带排查指引

## 环境要求

- Android 6.0（API 23）及以上，arm64 设备
- 手机支持 OTG，且线材支持数据传输（不能是纯充电线）

## 下载

到 [Releases](https://github.com/linglu114/android-usb-uvc-camera/releases) 下载最新 APK 直接安装（arm64 设备）。

## 构建

需要 JDK 21 与 Android SDK（`compileSdk 36`、`build-tools`、`ndk 28.2.13676358`）。

```bash
./gradlew :app:assembleDebug     # 调试包
./gradlew :app:assembleRelease   # 需要本地 keystore.properties，否则产物未签名
```

产物在 `app/build/outputs/apk/` 下。

## 常见问题

- **插上没反应**：部分机型要在系统设置里手动打开 OTG；确认线材支持数据传输。
- **画面反复掉线**：手机 OTG 口供电通常只有几百 mA，采集棒很容易超限。请改用带外部供电的 USB Hub 或 Y 型 OTG 线；也可以降低分辨率减少带宽。
- **授权后一直不出画面**：Android 14 及以上系统里，底层库用不可变 `PendingIntent` 申请授权，系统回填的设备信息会丢失，导致授权通过但连接流程不启动。本应用会在授权达成后自行补一次连接请求；若仍不出画面，重新插拔一次设备即可。
- **USB 授权弹窗被拒**：重新插拔设备，在系统弹窗里点「允许」；部分国产 ROM 还需要在权限管理里放行。
- **分辨率不对**：进设置面板的「选择分辨率」里切换；采集卡一般对 MJPEG 支持最好。

## 致谢与许可

- UVC 引擎：[ernestp/AndroidUSBCamera](https://github.com/ernestp/AndroidUSBCamera)（Apache-2.0）
- 本项目以 Apache-2.0 许可开源。
