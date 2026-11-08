package com.anwindmusic

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.anwindmusic.music.MediaSaver
import com.anwindmusic.music.MusicContent
import com.anwindmusic.music.PickBus
import com.anwindmusic.music.TvCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AnWindMusic —— AnWind 云音乐独立版入口 Activity：
 * - 承载 MusicContent（纯 Compose 手机布局）
 * - 系统文件管理器选图/选视频（DocumentsUI）/ 文件夹选择器（结果经 PickBus 回传播放器）
 * - v1.4：SAF 选择器拉起失败（电视普遍无 DocumentsUI）时返回 false，
 *   由 Compose 侧内置选择器兑底
 * - 运行时权限：音频读取（本地扫描）+ 通知（Android 13+）
 * - 默认全屏 edge-to-edge：内容延伸到状态栏/导航栏后面，占用刘海区域；
 *   交互控件经 insets 避让，不被系统栏/虚拟按键遮挡
 * - 歌词页真全屏：隐藏系统状态栏/导航栏（返回键或按钮恢复）
 */
class MainActivity : ComponentActivity() {

    /** 当前图片/视频选择用途（homeImage / lyricImage / coverImage / discImage / homeVideo / lyricVideo） */
    private var pendingPickKind: String? = null

    /** 沉浸式全屏状态（歌词页用；manifest 已锁 configChanges 避免旋转重建丢失） */
    private var fullscreen by mutableStateOf(false)

    /** 系统文件管理器选图（ACTION_OPEN_DOCUMENT）：
     *  带最近/图片/视频/相册等分类侧栏；结果复制到私有目录无授权过期问题。
     *  v1.4：返回是否拉起成功（电视无 DocumentsUI 时 false → Compose 内置选择器兑底） */
    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val kind = pendingPickKind
            pendingPickKind = null
            if (uri == null || kind == null) return@registerForActivityResult
            lifecycleScope.launch {
                // 复制到应用私有目录：path 形式存储，无 URI 授权过期问题
                val path = withContext(Dispatchers.IO) { MediaSaver.copyMediaToInternal(this@MainActivity, uri, kind) }
                if (path != null) {
                    PickBus.publish(path)
                } else {
                    Toast.makeText(this@MainActivity, "图片读取失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }

    /** v1.2 系统文件管理器选视频（ACTION_OPEN_DOCUMENT，仅 video 类型）：
     *  用途为主页/歌词页背景视频；结果同样复制到私有目录后经 PickBus 回传 */
    private val videoPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val kind = pendingPickKind
            pendingPickKind = null
            if (uri == null || kind == null) return@registerForActivityResult
            lifecycleScope.launch {
                val path = withContext(Dispatchers.IO) { MediaSaver.copyMediaToInternal(this@MainActivity, uri, kind) }
                if (path != null) {
                    PickBus.publish(path)
                } else {
                    Toast.makeText(this@MainActivity, "视频读取失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val path = treeUriToPath(uri)
            if (path != null) {
                PickBus.publish(path)
            } else {
                Toast.makeText(
                    this,
                    "该存储位置暂不支持自动换算为本地路径，请在设置页手动输入目录路径",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TV 兼容：进程内一次性检测设备是否为 Android TV（UiModeManager）。
        // TvCompat.tvFocus 等遥控适配逻辑全部由此开关驱动，手机/平板行为不变
        TvCompat.init(this)
        setupEdgeToEdge()
        requestEssentialPermissions()
        setContent {
            // 主色与播放器品牌红一致（#EC4141），未显式配色的 M3 控件跟随主题
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFFEC4141),
                    onPrimary = Color.White
                )
            ) {
                MusicContent(
                    onPickImage = { kind ->
                        // v1.4：返回 SAF 是否拉起成功；电视直接不尝试（无 DocumentsUI）
                        if (TvCompat.isTv) return@MusicContent false
                        pendingPickKind = kind
                        runCatching {
                            imagePicker.launch(arrayOf("image/*"))
                        }.isSuccess.also {
                            if (!it) pendingPickKind = null
                        }
                    },
                    onPickVideo = { kind ->
                        if (TvCompat.isTv) return@MusicContent false
                        pendingPickKind = kind
                        runCatching {
                            videoPicker.launch(arrayOf("video/*"))
                        }.isSuccess.also {
                            if (!it) pendingPickKind = null
                        }
                    },
                    onPickFolder = {
                        // v1.4：目录选择 SAF 免权限（无需「所有文件访问」）；
                        // 电视无文件选择器时返回 false，由 Compose 侧提示手动输入
                        pendingPickKind = "folder"
                        runCatching {
                            folderPicker.launch(null)
                        }.isSuccess.also {
                            if (!it) pendingPickKind = null
                        }
                    },
                    isFullscreen = fullscreen,
                    onToggleFullscreen = { toggleFullscreen() }
                )
            }
        }
    }

    // ==================== 默认全屏 edge-to-edge ====================

    /**
     * 默认全屏：内容绘制到状态栏/导航栏后面，占用刘海区域；
     * 交互控件由 Compose 侧 insets 避让，不会被虚拟按键遮挡。
     */
    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 刘海屏：内容延伸进刘海区域（shortEdges 全面适配，非刘海设备无影响）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // 导航栏完全透明：关闭系统对比度强制遮罩（否则手势条区域会有灰底）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // 浅色主题：状态栏/导航栏用深色图标
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    // ==================== 沉浸式全屏（歌词页） ====================

    private fun toggleFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            controller.show(WindowInsetsCompat.Type.systemBars())
            fullscreen = false
        } else {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            fullscreen = true
        }
    }

    // ==================== 运行时权限 ====================

    /** 音频读取（本地音乐扫描）+ 通知权限（Android 13+），合并一次申请 */
    private fun requestEssentialPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) {
            runCatching { requestPermissions(needed.toTypedArray(), 100) }
        }
    }

    // ==================== SAF 结果处理 ====================

    /**
     * SAF tree URI → 真实文件路径（仅手机主存储可换算）：
     * content://...../tree/primary:Music → /storage/emulated/0/Music
     * 其他存储（SD 卡/OTG）返回 null，由设置页手动输入路径兜底。
     */
    private fun treeUriToPath(uri: Uri): String? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
        if (!docId.startsWith("primary:")) return null
        val rel = docId.removePrefix("primary:")
        val base = Environment.getExternalStorageDirectory().absolutePath
        if (rel.isBlank()) base else "$base/$rel"
    }.getOrNull()
}
