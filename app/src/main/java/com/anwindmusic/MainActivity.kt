package com.anwindmusic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
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
import com.anwindmusic.music.MusicContent
import com.anwindmusic.music.PickBus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AnWindMusic —— AnWind 云音乐独立版入口 Activity：
 * - 承载 MusicContent（纯 Compose 手机布局）
 * - 系统照片选择器（Photo Picker）/ 文件夹选择器（结果经 PickBus 回传播放器）
 * - 运行时权限：音频读取（本地扫描）+ 通知（Android 13+）
 * - 默认全屏 edge-to-edge：内容延伸到状态栏/导航栏后面，占用刘海区域；
 *   交互控件经 insets 避让，不被系统栏/虚拟按键遮挡
 * - 歌词页真全屏：隐藏系统状态栏/导航栏（返回键或按钮恢复）
 */
class MainActivity : ComponentActivity() {

    /** 当前图片选择用途（homeImage / lyricImage / coverImage / discImage） */
    private var pendingPickKind: String? = null

    /** 沉浸式全屏状态（歌词页用；manifest 已锁 configChanges 避免旋转重建丢失） */
    private var fullscreen by mutableStateOf(false)

    /** 系统照片选择器（Photo Picker）：带「照片/相册」分类网格，直观选图；
     *  无需存储权限；设备不支持时自动回退文档选择器 */
    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val kind = pendingPickKind
            pendingPickKind = null
            if (uri == null || kind == null) return@registerForActivityResult
            lifecycleScope.launch {
                // 复制到应用私有目录：path 形式存储，无 URI 授权过期问题
                val path = withContext(Dispatchers.IO) { copyUriToInternal(uri, kind) }
                if (path != null) {
                    PickBus.publish(path)
                } else {
                    Toast.makeText(this@MainActivity, "图片读取失败，请重试", Toast.LENGTH_SHORT).show()
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
                        pendingPickKind = kind
                        runCatching {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }.onFailure {
                            Toast.makeText(this, "无法打开图片选择器", Toast.LENGTH_SHORT).show()
                            pendingPickKind = null
                        }
                    },
                    onPickFolder = {
                        // 目录扫描走 File API：Android 11+ 需「所有文件访问」权限
                        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                            Toast.makeText(
                                this,
                                "扫描指定目录需要「所有文件访问」权限：请在下一页授权后重新点击选择",
                                Toast.LENGTH_LONG
                            ).show()
                            guideAllFilesAccess()
                        } else {
                            pendingPickKind = "folder"
                            runCatching {
                                folderPicker.launch(null)
                            }.onFailure {
                                Toast.makeText(this, "无法打开文件夹选择器", Toast.LENGTH_SHORT).show()
                                pendingPickKind = null
                            }
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

    /** 引导到「所有文件访问」授权页（Android 11+，指定目录扫描/公共音乐下载目录用） */
    private fun guideAllFilesAccess() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                .onFailure {
                    Toast.makeText(this, "请在系统设置中授予「所有文件访问」权限", Toast.LENGTH_LONG).show()
                }
        }
    }

    // ==================== SAF 结果处理 ====================

    /**
     * 把选中的图片复制到应用私有目录并返回绝对路径。
     * 相比直接存 content:// URI：无授权过期/重启失效问题，BgImage 直接按文件路径读取。
     */
    private fun copyUriToInternal(uri: Uri, kind: String): String? = runCatching {
        val ext = when (contentResolver.getType(uri)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val dir = File(filesDir, "picked_images").apply { mkdirs() }
        val out = File(dir, "${kind}_${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        out.absolutePath
    }.getOrNull()

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
