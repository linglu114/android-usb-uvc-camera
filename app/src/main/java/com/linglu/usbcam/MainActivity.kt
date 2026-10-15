package com.linglu.usbcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraActivity
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.camera.bean.PreviewSize
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import com.linglu.usbcam.databinding.ActivityMainBinding

/**
 * 单屏预览：USB 摄像头 / HDMI 采集卡。
 *
 * 交互约定（用户已确认）：
 * - 画面朝向交给系统自动旋转，应用内不提供旋转/镜像控件
 * - 分辨率只在用户显式选择时切换，不做任何自动切换或轮换
 * - 横屏进入即隐藏全部控件（沉浸），点击屏幕切换，恢复显示后自动再隐藏
 * - 刘海/挖孔：画面保持沉浸延伸，关键控件用 displayCutout 安全边衬区避开
 *   （依据 developer.android.com/develop/ui/views/layout/display-cutout 最佳实践）
 */
class MainActivity : CameraActivity() {

    private var binding: ActivityMainBinding? = null
    private var cameraView: AspectRatioTextureView? = null

    private var keepScreenOn: Boolean = true
    private var everOpened: Boolean = false
    private var changingResolution: Boolean = false
    private var currentSizeIndex: Int = 0
    private val previewSizes: MutableList<PreviewSize> = mutableListOf()

    private var isLandscape: Boolean = false
    private var uiVisible: Boolean = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { if (isLandscape) setUiVisible(false) }

    /** 系统 UsbManager：用于真实查询设备是否已授权（不靠猜测） */
    private val usbManager: UsbManager? by lazy {
        getSystemService(Context.USB_SERVICE) as? UsbManager
    }

    /**
     * CAMERA 运行时权限：保留它不是为了用内置相机，
     * 而是因为部分 ROM（本机 ColorOS 15 实测）在缺少 CAMERA 时
     * 会让 UsbManager.requestPermission 直接被拒且不弹授权框。
     * 依据：StackOverflow 61542540 + 本机复现。
     */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            snack(getString(R.string.snack_camera_granted))
            requestUsbPermission()
        } else {
            snack(getString(R.string.snack_camera_denied))
        }
    }

    override fun getRootView(layoutInflater: LayoutInflater): View {
        val b = ActivityMainBinding.inflate(layoutInflater)
        binding = b
        return b.root
    }

    override fun getCameraView(): IAspectRatio {
        val v = AspectRatioTextureView(this)
        cameraView = v
        return v
    }

    override fun getCameraViewContainer(): ViewGroup? = binding?.cameraContainer

    override fun getGravity(): Int = Gravity.CENTER

    override fun getCameraRequest(): CameraRequest = CameraRequest.Builder()
        .setPreviewWidth(1280)
        .setPreviewHeight(720)
        .setRenderMode(CameraRequest.RenderMode.OPENGL)
        .setDefaultRotateType(RotateType.ANGLE_0)
        .setAudioSource(CameraRequest.AudioSource.NONE)
        .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
        .setAspectRatioShow(true)
        .setCaptureRawImage(false)
        .setRawPreviewData(false)
        .create()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyInsets()
        applyKeepScreenOn()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        // 方向变化后强制重新测量：Activity 声明了 configChanges 不会重建，
        // 而 AspectRatioTextureView 只在被重新测量时才按新屏幕计算尺寸，
        // 否则会停在旋转前的 1080x607（实测横屏画面未放大）。
        binding?.cameraContainer?.requestLayout()
        cameraView?.post {
            val v = cameraView ?: return@post
            if (v.width <= 0 || v.height <= 0) return@post
            refreshRenderSize(v.width, v.height)
        }
        cameraView?.requestLayout()
        applyOrientationUi()
    }

    override fun onResume() {
        super.onResume()
        applyOrientationUi()
    }

    /**
     * 内容遵守系统栏与刘海安全区。
     * 横屏时挖孔位于屏幕短边（左侧或右侧），给控件加左右内边距避免被遮挡；
     * 预览画面本身保持延伸（沉浸式），不额外裁切。
     */
    /**
     * 只把新的表面尺寸告知渲染器，不触碰 USB 授权链路。
     *
     * 为什么不用公开 API：
     * - ICamera.setRenderSize 内部是 mSizeChangedFuture?.set(...)（MultiCameraClient.kt:592），
     *   该 SettableFuture 在相机打开阶段已被消费，二次 set 无效，GL viewport 会停在旧尺寸。
     * - 重注册相机（unRegisterMultiCamera + registerMultiCamera）会走 onAttachDev →
     *   requestPermission，而 USBMonitor 在 Android 9+ 会先撤销已有权限再申请，必然弹授权框。
     * - RenderManager.setRenderSize 是公开方法，只投递 MSG_GL_CHANGED_SIZE，更新
     *   mScreenRender/mCameraRender 的尺寸与 buffer（RenderManager.kt:130-139），不涉 USB。
     */
    private fun refreshRenderSize(width: Int, height: Int): Boolean {
        val camera = getCurrentCamera() ?: return false
        var cls: Class<*>? = camera.javaClass
        while (cls != null && cls != Any::class.java) {
            try {
                val field = cls.getDeclaredField("mRenderManager")
                field.isAccessible = true
                val renderManager = field.get(camera) ?: return false
                renderManager.javaClass.getMethod(
                    "setRenderSize",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ).invoke(renderManager, width, height)
                return true
            } catch (e: NoSuchFieldException) {
                cls = cls.superclass
            } catch (e: Exception) {
                return false
            }
        }
        return false
    }

    private fun applyInsets() {
        val b = binding ?: return
        val basePad = resources.getDimensionPixelSize(R.dimen.screen_padding)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 挖孔避让仅在横屏生效：竖屏时挖孔位于顶部状态栏区域，无需横向避让
            val cutL = if (isLandscape) insets.getInsets(WindowInsetsCompat.Type.displayCutout()).left else 0
            val cutR = if (isLandscape) insets.getInsets(WindowInsetsCompat.Type.displayCutout()).right else 0
            b.topBar.updatePadding(
                top = bars.top,
                left = cutL,
                right = basePad + cutR
            )
            b.controlBar.updatePadding(
                bottom = bars.bottom,
                left = cutL + 8,
                right = basePad + cutR
            )
            b.statusRow.updatePadding(left = basePad + cutL, right = basePad + cutR)
            insets
        }
        ViewCompat.requestApplyInsets(b.root)
    }

    /**
     * 系统栏显隐。横屏沉浸时隐藏状态栏与导航栏，并允许边缘滑动临时唤出；
     * 竖屏或用户唤出控件时恢复显示。
     */
    private fun setSystemBarsVisible(visible: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun applyKeepScreenOn() {
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        binding?.btnKeepOn?.alpha = if (keepScreenOn) 1f else 0.45f
    }

    override fun initData() {
        val b = binding ?: return
        b.btnExit.setOnClickListener { finish() }
        b.btnKeepOn.setOnClickListener {
            keepScreenOn = !keepScreenOn
            applyKeepScreenOn()
        }
        b.btnResolution.setOnClickListener { showResolutionSheet() }
        b.btnDevice.setOnClickListener { showSummary() }
        b.btnMore.setOnClickListener { showSummary() }

        // 空状态的出口：设备已插入但未授权时，用户可主动触发系统授权
        b.btnRequestPermission.setOnClickListener { requestUsbPermission() }
        b.btnRescan.setOnClickListener { refreshEmptyState() }

        // 横屏沉浸：点击屏幕切换控件显隐（按钮会消费自身点击，不会误触）
        b.root.setOnClickListener { toggleUi() }

        refreshEmptyState()
        showState(UiState.Loading)
        applyOrientationUi()
        mainHandler.postDelayed({ ensureCameraPermission() }, STARTUP_PERMISSION_DELAY_MS)
    }

    // ---------------- 授权 ----------------

    private fun ensureCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            requestUsbPermission()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** 已插入的 USB 设备（UVC 设备在无权限时也能枚举到） */
    private fun pendingDevices(): List<UsbDevice> = try {
        getDeviceList()?.toList().orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    private fun requestUsbPermission() {
        val devices = pendingDevices()
        if (devices.isEmpty()) {
            snack(getString(R.string.snack_no_device))
            return
        }
        val denied = devices.filter { usbManager?.hasPermission(it) != true }
        if (denied.isEmpty()) {
            snack(getString(R.string.snack_permission_already))
            return
        }
        try {
            denied.forEach { requestPermission(it) }
            snack(getString(R.string.snack_permission_requested))
        } catch (e: Exception) {
            snack(e.localizedMessage ?: e.javaClass.simpleName)
        }
        mainHandler.postDelayed({ onPermissionChecked() }, PERMISSION_RECHECK_DELAY_MS)
    }

    /**
     * 授权复查：用系统 UsbManager 真实查询，而不是猜测。
     * 只有确实仍有设备未授权时才落空状态；已授权则保持加载态交给 OPENED。
     */
    private fun onPermissionChecked() {
        if (binding == null) return
        if (everOpened || isCameraOpened()) return
        val devices = pendingDevices()
        if (devices.isEmpty()) {
            refreshEmptyState()
            showState(UiState.Empty)
            return
        }
        val denied = devices.filter { usbManager?.hasPermission(it) != true }
        if (denied.isNotEmpty()) {
            refreshEmptyState()
            showState(UiState.Empty)
        }
    }

    // ---------------- 横屏沉浸 ----------------

    private fun applyOrientationUi() {
        if (isLandscape) {
            setUiVisible(false)
        } else {
            mainHandler.removeCallbacks(autoHideRunnable)
            setUiVisible(true)
        }
    }

    private fun toggleUi() {
        if (!isLandscape) return
        setUiVisible(!uiVisible)
        if (uiVisible) scheduleAutoHide()
    }

    private fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        val b = binding ?: return
        val state = if (visible) View.VISIBLE else View.GONE
        // 系统状态栏/导航栏：仅横屏沉浸时隐藏；竖屏始终保留
        setSystemBarsVisible(visible || !isLandscape)
        // 方向变化时重新分派 insets，使「挖孔避让仅横屏生效」及时切换
        ViewCompat.requestApplyInsets(b.root)
        b.topBar.visibility = state
        b.statusRow.visibility = state
        b.controlBar.visibility = state
    }

    private fun scheduleAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, UI_AUTO_HIDE_MS)
    }

    // ---------------- 分辨率（显式选择，无自动切换） ----------------

    private var sheet: BottomSheetDialog? = null

    private fun showResolutionSheet() {
        if (previewSizes.isEmpty()) loadPreviewSizes()
        if (previewSizes.isEmpty()) {
            snack(getString(R.string.snack_resolution_unavailable))
            return
        }
        val pad = resources.getDimensionPixelSize(R.dimen.sheet_padding)
        val touch = resources.getDimensionPixelSize(R.dimen.touch_target)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundResource(R.drawable.bg_bottom_sheet)
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.sheet_title_resolution)
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.hud_on_surface))
            setPadding(0, 0, 0, 8)
        })
        container.addView(TextView(this).apply {
            text = getString(R.string.sheet_resolution_hint)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.hud_on_surface_variant))
            setPadding(0, 0, 0, 12)
        })

        previewSizes.forEachIndexed { index, size ->
            container.addView(TextView(this).apply {
                text = size.width.toString() + " × " + size.height.toString()
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                minHeight = touch
                setPadding(pad, 0, pad, 0)
                isSelected = index == currentSizeIndex
                setTextColor(ContextCompat.getColor(context, R.color.hud_on_surface))
                setBackgroundResource(R.drawable.bg_sheet_option)
                setOnClickListener {
                    applyResolution(index)
                    dismissSheet()
                }
            })
        }

        sheet = BottomSheetDialog(this).apply {
            setContentView(container)
            show()
        }
    }

    private fun dismissSheet() {
        sheet?.dismiss()
        sheet = null
    }

    private fun applyResolution(index: Int) {
        val camera = getCurrentCamera() ?: return
        val size = previewSizes.getOrNull(index) ?: return
        changingResolution = true
        try {
            camera.updateResolution(size.width, size.height)
            currentSizeIndex = index
            showState(UiState.Connected)
            snack(getString(R.string.snack_resolution_switched_fmt, size.width, size.height))
            refreshMeta()
        } catch (e: Exception) {
            changingResolution = false
            showState(UiState.Error, e.localizedMessage ?: e.javaClass.simpleName)
            return
        }
        mainHandler.postDelayed({
            changingResolution = false
            if (binding != null && !isCameraOpened()) {
                refreshEmptyState()
                showState(UiState.Empty)
            }
        }, RESOLUTION_SETTLE_DELAY_MS)
    }

    /** 分辨率列表：优先 16:9，取不到则用全量；按面积降序 */
    private fun loadPreviewSizes() {
        val sizes = try {
            getAllPreviewSizes(16.0 / 9.0)?.takeIf { it.isNotEmpty() }
                ?: getAllPreviewSizes(null)
        } catch (e: Exception) {
            null
        }
        previewSizes.clear()
        if (sizes != null) {
            previewSizes.addAll(sizes.sortedByDescending { it.width * it.height })
            if (currentSizeIndex >= previewSizes.size) currentSizeIndex = 0
        }
    }

    // ---------------- 状态展示 ----------------

    override fun onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
        val b = binding ?: return
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                everOpened = true
                changingResolution = false
                showState(UiState.Connected)
                loadPreviewSizes()
                refreshMeta()
            }
            ICameraStateCallBack.State.CLOSED -> {
                if (changingResolution) {
                    showState(UiState.Loading)
                    return
                }
                if (everOpened) {
                    refreshEmptyState()
                    showState(UiState.Empty)
                } else {
                    showState(UiState.Loading)
                }
            }
            ICameraStateCallBack.State.ERROR -> {
                changingResolution = false
                showState(UiState.Error, msg)
                Snackbar.make(
                    b.root,
                    getString(R.string.snack_open_failed_fmt, msg ?: "unknown"),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 空状态文案由「设备存在性 + 实际授权状态」共同决定，
     * 避免把「已授权、正在打开」误报成未授权。
     */
    @SuppressLint("SetTextI18n")
    private fun refreshEmptyState() {
        val b = binding ?: return
        val devices = pendingDevices()
        val denied = devices.filter { usbManager?.hasPermission(it) != true }
        when {
            devices.isEmpty() -> {
                b.tvEmptyTitle.setText(R.string.empty_title)
                b.tvEmptySteps.setText(R.string.empty_steps_default)
                b.btnRequestPermission.visibility = View.GONE
            }
            denied.isNotEmpty() -> {
                val names = denied.joinToString(", ") { deviceLabel(it) }
                b.tvEmptyTitle.setText(R.string.empty_title_detected)
                b.tvEmptySteps.text = getString(R.string.empty_detected_fmt, names)
                b.btnRequestPermission.visibility = View.VISIBLE
            }
            else -> {
                b.tvEmptyTitle.setText(R.string.empty_title_opening)
                b.tvEmptySteps.setText(R.string.empty_steps_opening)
                b.btnRequestPermission.visibility = View.GONE
            }
        }
    }

    @SuppressLint("NewApi")
    private fun refreshMeta() {
        val b = binding ?: return
        val parts = mutableListOf<String>()
        val names = getDeviceList()?.joinToString(", ") { dev -> deviceLabel(dev) }
        if (!names.isNullOrBlank()) parts.add(names)
        previewSizes.getOrNull(currentSizeIndex)?.let {
            parts.add(it.width.toString() + "×" + it.height.toString())
        }
        b.tvMeta.text = if (parts.isEmpty()) getString(R.string.meta_unknown_device) else parts.joinToString(" · ")
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun deviceLabel(dev: UsbDevice): String =
        dev.productName?.takeIf { it.isNotBlank() } ?: dev.deviceName

    private fun showSummary() {
        val b = binding ?: return
        val lines = mutableListOf<String>()
        lines.add(getString(R.string.sheet_title_device) + ": " + (getDeviceList()?.size ?: 0))
        if (previewSizes.isEmpty()) {
            lines.add(getString(R.string.sheet_no_device))
        } else {
            lines.add(getString(R.string.sheet_title_resolution))
            previewSizes.take(6).forEach { lines.add("  " + it.width + "×" + it.height) }
        }
        Snackbar.make(b.root, lines.joinToString("\n"), Snackbar.LENGTH_LONG).show()
    }

    private fun snack(text: String) {
        val b = binding ?: return
        Snackbar.make(b.root, text, Snackbar.LENGTH_SHORT).show()
    }

    private enum class UiState { Empty, Loading, Connected, Error }

    private fun showState(state: UiState, detail: String? = null) {
        val b = binding ?: return
        b.emptyState.visibility = if (state == UiState.Empty) View.VISIBLE else View.GONE
        b.loadingState.visibility = if (state == UiState.Loading) View.VISIBLE else View.GONE

        val dot = b.statusDot
        val tint = when (state) {
            UiState.Connected -> R.color.hud_primary
            UiState.Error -> R.color.hud_error
            else -> R.color.hud_on_surface_variant
        }
        dot.background.setTint(getColor(tint))

        b.tvStatus.text = when (state) {
            UiState.Connected -> getString(R.string.state_connected)
            UiState.Loading -> getString(R.string.loading_opening)
            UiState.Error -> getString(R.string.state_error_fmt, detail ?: "unknown")
            UiState.Empty -> getString(R.string.state_disconnected)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        dismissSheet()
        binding = null
        cameraView = null
        super.onDestroy()
    }

    private companion object {
        private const val PERMISSION_RECHECK_DELAY_MS = 2500L
        private const val STARTUP_PERMISSION_DELAY_MS = 2500L
        private const val RESOLUTION_SETTLE_DELAY_MS = 2500L
        private const val UI_AUTO_HIDE_MS = 5000L
    }
}
