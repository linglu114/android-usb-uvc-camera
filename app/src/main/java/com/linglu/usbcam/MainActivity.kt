package com.linglu.usbcam

import android.annotation.SuppressLint
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.RequiresApi
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
        // 未打开任何设备前，加载态有真实用途：等待 USB 授权 / 相机打开
        showState(UiState.Loading)
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
        changingResolution = true
        showState(UiState.Loading)
        try {
            camera.updateResolution(size.width, size.height)
            snack(getString(R.string.snack_resolution_switched_fmt, size.width, size.height))
            refreshMeta()
        } catch (e: Exception) {
            changingResolution = false
            showState(UiState.Error, e.localizedMessage ?: e.javaClass.simpleName)
        }
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
                showState(if (everOpened) UiState.Empty else UiState.Loading)
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
