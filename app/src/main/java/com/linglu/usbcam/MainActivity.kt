package com.linglu.usbcam

import android.annotation.SuppressLint
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
 * 单屏预览：USB 摄像头 / HDMI 采集卡的画面显示。
 *
 * 设计约束（AGENT.md 与 docs/spec/usbcam-v1.md）：
 * - 触控目标 >= 48dp；文案用 sp 随系统字号缩放
 * - 控件浮于画面之上、不抢焦点；唯一强调色为锈红
 * - 空 / 加载 / 错误三态齐备，错误文案点名问题与恢复动作
 *
 * 状态机说明：切换分辨率会让库先 CLOSED 再 OPENED。若 CLOSED 直接落到「空状态」，
 * 用户会看到画面闪烁一下空状态指引，因此用 [changingResolution] 区分这次关闭是否由
 * 主动切换引起：是则维持加载态，否则才认为设备真的断开。
 */
class MainActivity : CameraActivity() {

    private var binding: ActivityMainBinding? = null
    private var cameraView: AspectRatioTextureView? = null

    private var rotateType: RotateType = RotateType.ANGLE_0
    private var keepScreenOn: Boolean = true
    private var everOpened: Boolean = false
    private var changingResolution: Boolean = false
    private var currentSizeIndex: Int = 0
    private val previewSizes: MutableList<PreviewSize> = mutableListOf()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 系统 UsbManager：用于真实查询某设备是否已授权（不再靠猜测） */
    private val usbManager: UsbManager? by lazy {
        getSystemService(Context.USB_SERVICE) as? UsbManager
    }

    /**
     * CAMERA 运行时权限。
     * 保留它不是为了用内置相机，而是因为部分 ROM（本机 ColorOS 15 实测）在应用未持有
     * CAMERA 权限时会让 UsbManager.requestPermission 直接被拒且不弹授权对话框。
     * 依据：StackOverflow 61542540 高票答案的实测结论。
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

    /**
     * 请求 1280x720 + MJPEG（采集卡对该格式支持最好）。
     * 设备不支持时 AUSBC 内部会自动回退，这里不再叠加第二层回退逻辑。
     */
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
        applyInsets()
        applyKeepScreenOn()
    }

    /** 先拿 CAMERA 权限，否则部分 ROM 不会给出 USB 授权对话框 */
    private fun ensureCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            requestUsbPermission()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** 内容避开状态栏与手势条（edge-to-edge + window insets） */
    private fun applyInsets() {
        val b = binding ?: return
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.topBar.updatePadding(top = bars.top)
            b.controlBar.updatePadding(bottom = bars.bottom)
            insets
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
        // 首版不做镜像（AUSBC 无水平翻转公开 API），隐藏按钮而不是留一个无效控件
        b.btnMirror.visibility = View.GONE
        b.btnKeepOn.setOnClickListener {
            keepScreenOn = !keepScreenOn
            applyKeepScreenOn()
        }
        b.btnRotate.setOnClickListener {
            rotateType = nextRotate(rotateType)
            applyRotate(rotateType)
        }
        b.btnResolution.setOnClickListener { cycleResolution() }
        b.btnMore.setOnClickListener { showSummary() }

        // 空状态的出口：设备已插入但未授权时，用户可主动触发系统授权对话框
        b.btnRequestPermission.setOnClickListener { requestUsbPermission() }
        b.btnRescan.setOnClickListener {
            refreshEmptyState()
            snack(getString(R.string.action_rescan) + "完成")
        }

        // 启动即进入加载态（等待授权/打开），随后由 onCameraState 驱动
        refreshEmptyState()
        showState(UiState.Loading)

        // 稍候再发起授权：USBMonitor 在注册后约 1s 才完成首次设备枚举
        mainHandler.postDelayed({ ensureCameraPermission() }, STARTUP_PERMISSION_DELAY_MS)
    }

    /** 已插入但未授权的 USB 设备（UVC 设备在无权限时也能枚举到） */
    private fun pendingDevices(): List<UsbDevice> = try {
        getDeviceList()?.toList().orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * 让用户主动触发一次系统 USB 授权。
     * 某些 ROM（实测 ColorOS 15 / Android 15）在应用启动时被动的授权请求不会拉起弹窗，
     * 这里提供显式入口；若确实没有弹窗，说明请求被 ROM 侧策略丢弃，需要走系统设置授权。
     */
    private fun requestUsbPermission() {
        val devices = pendingDevices()
        if (devices.isEmpty()) {
            snack(getString(R.string.snack_no_device))
            return
        }
        val denied = devices.filter { usbManager?.hasPermission(it) != true }
        if (denied.isEmpty()) {
            // 权限已授予：系统不会再弹对话框，库会在设备 attach 时自行打开相机
            snack(getString(R.string.snack_permission_already))
            return
        }
        try {
            denied.forEach { requestPermission(it) }
            snack(getString(R.string.snack_permission_requested))
        } catch (e: Exception) {
            snack(e.localizedMessage ?: e.javaClass.simpleName)
        }
        // 部分 ROM（实测 ColorOS 15 / Android 15）的授权请求可能既不弹窗也不回调，
        // 这里复查一次系统权限状态，保证界面不会永远停在加载态。
        mainHandler.postDelayed({ onPermissionChecked() }, PERMISSION_RECHECK_DELAY_MS)
    }

    /**
     * 授权复查：用系统 UsbManager 真实查询权限。
     * - 仍有设备未授权 → 落空状态并给出授权入口
     * - 已全部授权 → 保持加载态，交给 onCameraState(OPENED) 驱动
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


    /**
     * 空状态文案由「设备存在性 + 实际授权状态」共同决定。
     * 用 UsbManager.hasPermission 做真实判定，避免把「已授权、正在打开」误报成未授权。
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
                // 设备已授权，只是相机尚未打开：这不是「未授权」，不要误导用户
                b.tvEmptyTitle.setText(R.string.empty_title_opening)
                b.tvEmptySteps.setText(R.string.empty_steps_opening)
                b.btnRequestPermission.visibility = View.GONE
            }
        }
    }

    private fun nextRotate(current: RotateType): RotateType = when (current) {
        RotateType.ANGLE_0 -> RotateType.ANGLE_90
        RotateType.ANGLE_90 -> RotateType.ANGLE_180
        RotateType.ANGLE_180 -> RotateType.ANGLE_270
        else -> RotateType.ANGLE_0
    }

    /** 旋转只对 OPENGL 渲染生效；失败时给出原因而不是静默 */
    private fun applyRotate(type: RotateType) {
        val camera = getCurrentCamera()
        if (camera == null || !isCameraOpened()) {
            snack(getString(R.string.state_disconnected))
            return
        }
        try {
            camera.setRotateType(type)
        } catch (e: Exception) {
            snack(e.localizedMessage ?: e.javaClass.simpleName)
        }
    }


    /** 在有可选列表时按列表轮换；列表为空时不做无依据的切换 */
    private fun cycleResolution() {
        if (previewSizes.isEmpty()) {
            loadPreviewSizes()
        }
        if (previewSizes.isEmpty()) {
            snack(getString(R.string.sheet_no_device))
            return
        }
        val camera = getCurrentCamera() ?: return
        currentSizeIndex = (currentSizeIndex + 1) % previewSizes.size
        val size = previewSizes[currentSizeIndex]
        // AUSBC 的 updateResolution 通常只调 setPreviewSize，不一定回调 CLOSED/OPENED。
        // 因此这里不改状态显示（保持「已连接」），只用标志抑制可能的 CLOSED 空状态闪烁，
        // 并用定时器兜底：若相机真的没起来，再落回空状态。
        changingResolution = true
        try {
            camera.updateResolution(size.width, size.height)
            snack(getString(R.string.snack_resolution_switched_fmt, size.width, size.height))
            showState(UiState.Connected)
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
                // 主动切换分辨率引起的中断：保持加载态，等随后的 OPENED
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

    /** 分辨率列表：优先 16:9，取不到则用全量列表；按面积降序便于从高到低轮换 */
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
        val count = getDeviceList()?.size ?: 0
        lines.add(getString(R.string.sheet_title_device) + ": " + count)
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

    private companion object {
        private const val PERMISSION_RECHECK_DELAY_MS = 2500L
        private const val STARTUP_PERMISSION_DELAY_MS = 2500L
        private const val RESOLUTION_SETTLE_DELAY_MS = 2500L
    }

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
        binding = null
        cameraView = null
        super.onDestroy()
    }
}
