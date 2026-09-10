package com.anwindmusic.music

import android.app.Activity
import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import java.io.File
import kotlinx.coroutines.launch

/**
 * 云音乐独立版（v1.0.0，移植自 AnWind 桌面云音乐 v2.21.5）：
 * - 纯手机布局：顶部标题栏 + 内容页 + 紧凑播放条 + 底部导航栏（对照网易云音乐手机版）
 * - 功能与桌面版一致：搜索播放、我喜欢、最近播放、本地音乐、歌曲/歌词下载、
 *   3D 歌词秀（Lyrics3DPage）、桌面歌词悬浮窗（LyricOverlayService）、设置中心
 * - 在线音乐与歌词数据源：酷我搜索/播放链接 + 酷我/网易云/QQ/LRCLIB 四级词源回退
 * - 背景图片/扫描目录选择改走系统文件选择器（MainActivity 经 [PickBus] 回传）
 * - 后台持续播放：起播即拉起前台服务 PlaybackService（通知栏播放控制 + 锁屏线控）
 */

// ==================== 文件选择事件总线 ====================

/**
 * 图片/文件夹选择结果总线（独立版新增，替代桌面版 FilePickBus）：
 * MainActivity 的系统 SAF 选择器返回结果后 publish，
 * 播放器组合内 listen 接收并按 pendingPick 用途分发。
 */
object PickBus {

    @Volatile
    private var listener: ((String) -> Unit)? = null

    /** 注册监听（同一时刻仅一个播放器组合监听），返回注销函数 */
    fun listen(l: (String) -> Unit): () -> Unit {
        listener = l
        return { if (listener === l) listener = null }
    }

    /** MainActivity 在选择器回调中发布结果（主线程） */
    fun publish(path: String) {
        listener?.invoke(path)
    }
}

/** 页面枚举 */
private enum class Page(val label: String) {
    SEARCH("搜索"),
    FAVORITES("我喜欢"),
    RECENT("最近"),
    LOCAL("本地"),
    DOWNLOADS("下载"),
    SETTINGS("设置")
}

/** 下载任务（字段为 Compose State，进度条自动刷新） */
class DownloadItem(val song: SongInfo) {
    var progress by mutableStateOf(0f)
    var done by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var file by mutableStateOf<File?>(null)
}

@Composable
fun MusicContent(
    /** 系统图片选择器（kind：homeImage / lyricImage / coverImage / discImage） */
    onPickImage: (String) -> Unit,
    /** 系统文件夹选择器（本地扫描目录） */
    onPickFolder: () -> Unit,
    /** 是否处于沉浸式全屏（歌词页隐藏系统状态栏/导航栏） */
    isFullscreen: Boolean,
    /** 切换沉浸式全屏 */
    onToggleFullscreen: () -> Unit
) {
    val context = LocalContext.current
    // 应用级单例引擎：Activity 销毁/后台化不影响播放，前台服务接管生命周期
    val engine = remember(context) { MusicEngineHolder.obtain(context) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    val uiScope = rememberCoroutineScope()

    // ===== 页面状态 =====
    var page by remember { mutableStateOf(Page.SEARCH) }
    var showLyrics by remember { mutableStateOf(false) }

    // ===== 搜索状态（页面间切换保留） =====
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SongInfo>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    // v2.24：搜索音源（0=酷我 / 1=简音），选择持久化到设置
    var searchSource by remember { mutableStateOf(musicSettings.searchSource) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchPage by remember { mutableStateOf(0) }

    // ===== 歌单状态 =====
    var favorites by remember { mutableStateOf(engine.store.loadFavorites()) }
    var recent by remember { mutableStateOf(engine.store.loadRecent()) }
    val favKeys = remember(favorites) { favorites.map { it.key }.toSet() }

    fun refreshLibrary() {
        favorites = engine.store.loadFavorites()
        recent = engine.store.loadRecent()
    }

    // ===== 播放器设置中心 =====
    var musicSettings by remember { mutableStateOf(engine.store.loadMusicSettings()) }
    fun updateSettings(s: MusicSettings) {
        musicSettings = s
        engine.store.saveMusicSettings(s)
    }

    // ===== 桌面歌词锁定开关（状态存 desklyric.json，与位置同文件） =====
    var lyricOverlayState by remember { mutableStateOf(engine.store.loadLyricOverlayState()) }
    fun updateLyricLock(locked: Boolean) {
        lyricOverlayState = lyricOverlayState.copy(locked = locked)
        engine.store.saveLyricOverlayState(lyricOverlayState)
        // 服务已运行则经 onStartCommand 重读锁定状态并重建窗口；未运行则拉起
        if (musicSettings.desktopLyricOn) startDesktopLyricService(context)
    }

    // ===== 本地音乐 =====
    var localSongs by remember { mutableStateOf<List<SongInfo>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    fun scanLocal() {
        scanning = true
        uiScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val found = queryLocalSongs(context, musicSettings.scanMode, musicSettings.scanDirs)
            localSongs = found
            scanning = false
        }
    }

    // 设置变更后自动重扫本地曲库（仅指定目录模式）
    LaunchedEffect(musicSettings.scanMode, musicSettings.scanDirs.size) {
        if (musicSettings.scanMode == MusicSettings.SCAN_DIRS) scanLocal()
    }

    // ===== 系统文件选择器（替代桌面版文件资源管理器） =====
    // pendingPick 记录本次选择用途（图片归属/目录），PickBus 回传时按用途分发
    var pendingPick by remember { mutableStateOf<String?>(null) }

    fun openPicker(kind: String, mode: String) {
        pendingPick = kind
        if (mode == "image") onPickImage(kind) else onPickFolder()
    }

    // ===== 动态清晰度（v1.2）：按自定义背景等效亮度切换全局明暗配色 =====
    // 图片背景：降采样取样平均亮度并折算「图片压暗」滑条；纯色/渐变：直接取色亮度。
    // 亮背景 → 深色文字+白玻璃+白柔光；暗背景 → 白色文字+黑玻璃+黑投影。
    val homeCustomBg = musicSettings.homeBgMode != MusicSettings.HOME_BG_DEFAULT
    var bgLuminance by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(
        musicSettings.homeBgMode,
        musicSettings.homeBgImage,
        musicSettings.homeBgColor,
        musicSettings.homeBgGradient,
        musicSettings.homeImageDim
    ) {
        bgLuminance = when (musicSettings.homeBgMode) {
            MusicSettings.BG_IMAGE -> {
                val path = musicSettings.homeBgImage
                if (path.isNullOrEmpty()) null
                else loadBackgroundBitmap(context, path, 64)?.averageLuminance()
                    ?.let { (it * (1f - musicSettings.homeImageDim)).coerceIn(0f, 1f) }
            }
            MusicSettings.BG_SOLID -> Color(musicSettings.homeBgColor).luminance()
            MusicSettings.BG_GRADIENT -> {
                val pair = HomeBgGradients.getOrElse(musicSettings.homeBgGradient) { HomeBgGradients[0] }
                (pair[0].luminance() + pair[1].luminance()) / 2f
            }
            else -> null
        }
    }
    val scheme = remember(homeCustomBg, bgLuminance) { adaptiveScheme(homeCustomBg, bgLuminance) }
    // 系统栏图标：自定义深色背景或歌词页 → 浅色图标；默认/浅色背景 → 深色图标
    val lightSystemBars = !(scheme.isCustom && !scheme.isLight)

    // ===== 系统栏图标颜色适配 =====
    // 主界面默认/浅色背景 → 深色图标；自定义深色背景或歌词页（0xFF0B0B10）→ 浅色图标。
    // 真全屏模式下系统栏已隐藏，此设置仅在退出全屏/半屏歌词时生效。
    DisposableEffect(showLyrics, isFullscreen, lightSystemBars) {
        val window = (context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            val light = !showLyrics && lightSystemBars
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        }
        onDispose {
            // 离开组合时恢复浅色主题默认（深色图标）
            (context as? Activity)?.window?.let { w ->
                val c = WindowCompat.getInsetsController(w, w.decorView)
                c.isAppearanceLightStatusBars = true
                c.isAppearanceLightNavigationBars = true
            }
        }
    }

    // ===== 屏幕方向（设置页「屏幕方向」即时生效；manifest 已锁 configChanges 不重建） =====
    DisposableEffect(musicSettings.orientation) {
        (context as? Activity)?.requestedOrientation = when (musicSettings.orientation) {
            MusicSettings.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            MusicSettings.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            MusicSettings.ORIENTATION_SENSOR -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED   // 跟随系统
        }
        onDispose { }
    }

    DisposableEffect(Unit) {
        val unlisten = PickBus.listen { path ->
            when (pendingPick) {
                "homeImage" -> updateSettings(
                    musicSettings.copy(homeBgImage = path, homeBgMode = MusicSettings.BG_IMAGE)
                )
                "lyricImage" -> updateSettings(
                    musicSettings.copy(lyricBgImage = path, lyricBgMode = MusicSettings.BG_IMAGE)
                )
                "coverImage" -> updateSettings(
                    musicSettings.copy(coverImage = path)
                )
                "discImage" -> updateSettings(
                    musicSettings.copy(discImage = path)
                )
                "folder" -> {
                    if (!musicSettings.scanDirs.contains(path)) {
                        updateSettings(musicSettings.copy(scanDirs = musicSettings.scanDirs + path))
                    }
                }
            }
            pendingPick = null
        }
        onDispose { unlisten() }
    }

    // ===== 下载 =====
    val downloads = remember { mutableStateListOf<DownloadItem>() }

    fun startDownload(song: SongInfo) {
        if (song.isLocal) {
            toast("本地歌曲无需下载")
            return
        }
        if (downloads.any { it.song.key == song.key && !it.failed }) {
            toast("该歌曲已在下载列表")
            return
        }
        // 清理同曲的失败任务（重试场景）
        downloads.removeAll { it.song.key == song.key && it.failed }
        val item = DownloadItem(song)
        downloads.add(0, item)
        uiScope.launch {
            // 1) 解析直链（v2.24：简音 Meting 音源走代理直链解析，其余走酷我解析）
            val urlRes = song.metingServer
                ?.let { MetingMusicApi.resolvePlayUrl(it, song.id) }
                ?: KuwoMusicApi.getPlayUrl(song.id)
            if (urlRes.isFailure) {
                item.failed = true
                item.error = urlRes.exceptionOrNull()?.message ?: "获取链接失败"
                return@launch
            }
            // 2) 下载音频
            val dir = musicDownloadDir(context)
            val file = File(dir, sanitizeFileName("${song.artist} - ${song.name}") + ".mp3")
            val dl = KuwoMusicApi.downloadFile(urlRes.getOrThrow(), file) { done, total ->
                if (total > 0) item.progress = (done.toFloat() / total).coerceIn(0f, 1f)
            }
            if (dl.isFailure) {
                item.failed = true
                item.error = dl.exceptionOrNull()?.message ?: "下载失败"
                runCatching { file.delete() }
                return@launch
            }
            item.done = true
            item.progress = 1f
            item.file = file
            // 3) 同步下载歌词到同目录
            val lyric = fetchLyrics(engine.store, song, engine = musicSettings.lyricEngine)
            if (lyric != null) {
                runCatching {
                    File(dir, file.nameWithoutExtension + ".lrc").writeText(LrcParser.toLrcText(lyric))
                }
            }
            toast("下载完成：${file.name}")
            refreshLibrary()
        }
    }

    // 歌词手动下载/自动下载后刷新当前曲歌词显示（tick 作为 LaunchedEffect key）
    var lyricRefreshTick by remember { mutableStateOf(0) }

    fun downloadLyricFile(song: SongInfo?) {
        if (song == null) {
            toast("当前没有播放中的歌曲")
            return
        }
        uiScope.launch {
            val lyric = fetchLyrics(engine.store, song, force = true, engine = musicSettings.lyricEngine)
            if (lyric == null) {
                toast("未找到该歌曲的歌词")
                return@launch
            }
            val dir = musicDownloadDir(context)
            val file = File(dir, sanitizeFileName("${song.artist} - ${song.name}") + ".lrc")
            runCatching { file.writeText(LrcParser.toLrcText(lyric)) }
            // 若下载的是正在播放的歌曲，刷新其歌词显示
            if (song.key == engine.currentSong?.key) lyricRefreshTick++
            toast("歌词已保存：${file.absolutePath}")
        }
    }

    // ===== 歌词获取（收编到引擎） =====
    // 切歌/装回会话时引擎自动后台拉取（缓存优先 + 深度下载兜底），
    // UI 直读 engine.lyricDoc / engine.lyricLoading；后台播放时歌词照常推进
    LaunchedEffect(lyricRefreshTick) {
        if (lyricRefreshTick > 0) engine.refreshLyrics(force = true)
    }

    // ===== 桌面歌词偏好推送 =====
    // 引擎负责把播放进度/歌词行推进到 DesktopLyricBus；此处负责
    // 「偏好变化 → 推送镜像 + 拉起/关闭悬浮窗服务」
    LaunchedEffect(
        musicSettings.desktopLyricOn,
        musicSettings.desktopLyricFullscreen,
        musicSettings.desktopLyricColor,
        musicSettings.desktopLyricBgAlpha,
        musicSettings.desktopLyricSize,
        musicSettings.desktopLyricKtv,
        musicSettings.desktopLyricLines
    ) {
        DesktopLyricBus.applySettings(musicSettings)
        if (musicSettings.desktopLyricOn) {
            startDesktopLyricService(context)
        } else {
            stopDesktopLyricService(context)
        }
    }

    // ===== 后台持续播放 =====
    // 起播即拉起前台服务（通知栏播放控制 + 锁屏线控 + 保活）；
    // 服务为进程级，Activity 退后台/销毁不影响；通知栏「退出」可停播
    LaunchedEffect(engine.isPlaying) {
        if (engine.isPlaying) PlaybackServiceController.start(context)
    }

    // ===== 播放错误提示 =====
    LaunchedEffect(engine.playError) {
        engine.playError?.let {
            toast(it)
            engine.clearError()
        }
    }

    // 返回键：歌词页优先退出；沉浸式全屏先退出全屏
    BackHandler(enabled = showLyrics) { showLyrics = false }
    BackHandler(enabled = showLyrics && isFullscreen) { onToggleFullscreen() }

    // ===== 布局：顶部栏 + 内容页（歌词页覆盖） + 播放条 + 底部导航 =====
    val homeBgModifier = when (musicSettings.homeBgMode) {
        MusicSettings.BG_SOLID -> Modifier.background(Color(musicSettings.homeBgColor))
        MusicSettings.BG_GRADIENT -> {
            val pair = HomeBgGradients.getOrElse(musicSettings.homeBgGradient) { HomeBgGradients[0] }
            Modifier.background(Brush.verticalGradient(pair))
        }
        MusicSettings.BG_IMAGE -> Modifier.background(Color.Transparent)
        else -> Modifier.background(Mc.bg)
    }
    // 向全内容区提供自定义背景激活标记与动态清晰度配色方案
    // （表面控件自动半透明融入背景；文字按背景明暗自适应颜色+光晕）
    CompositionLocalProvider(
        LocalHomeCustomBg provides homeCustomBg,
        LocalAdaptiveScheme provides scheme
    ) {
        // imePadding：edge-to-edge 下键盘不挤压窗口（API 30+），由 insets 把整体抬到键盘上方；
        // API 24-29 的 adjustResize 窗口缩放路径下 ime insets 为 0，此修饰符自动无操作
        Box(Modifier.fillMaxSize().imePadding()) {
            // 自定义图片背景 + 压暗层（仅图片模式）
            if (musicSettings.homeBgMode == MusicSettings.BG_IMAGE) {
                BgImage(musicSettings.homeBgImage, Modifier.matchParentSize())
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = musicSettings.homeImageDim))
                )
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .then(homeBgModifier)
            ) {
                if (!showLyrics) {
                    MusicTopBar(
                        onSettings = { page = Page.SETTINGS }
                    )
                }
                Box(Modifier.weight(1f)) {
                    if (!showLyrics) {
                        when (page) {
                            Page.SEARCH -> SearchPage(
                                query = query,
                                onQueryChange = { query = it },
                                searchSource = searchSource,
                                onSourceChange = { src ->
                                    searchSource = src
                                    updateSettings(musicSettings.copy(searchSource = src))
                                },
                                onSearch = { kw ->
                                    searching = true
                                    searchError = null
                                    uiScope.launch {
                                        if (searchSource == MusicSettings.SEARCH_SOURCE_METING) {
                                            // v2.24：简音音源（Meting 聚合，netease 空结果自动回落 kugou）
                                            val r = MetingMusicApi.search(kw)
                                            if (r.isSuccess) {
                                                searchResults = r.getOrThrow().map { SongInfo.fromMetingSong(it) }
                                                searchPage = 0
                                                if (searchResults.isEmpty()) searchError = "未找到相关歌曲"
                                            } else {
                                                searchResults = emptyList()
                                                searchError = r.exceptionOrNull()?.message ?: "搜索失败"
                                            }
                                        } else {
                                            val r = KuwoMusicApi.search(kw, 0, 20)
                                            if (r.isSuccess) {
                                                searchResults = r.getOrThrow().map { SongInfo.fromKuwoSong(it) }
                                                searchPage = 0
                                                if (searchResults.isEmpty()) searchError = "未找到相关歌曲"
                                            } else {
                                                searchResults = emptyList()
                                                searchError = r.exceptionOrNull()?.message ?: "搜索失败"
                                            }
                                        }
                                        searching = false
                                    }
                                },
                                results = searchResults,
                                searching = searching,
                                error = searchError,
                                canLoadMore = searchSource == MusicSettings.SEARCH_SOURCE_KUWO,
                                onLoadMore = {
                                    // v2.24：仅酷我源支持分页；简音源一次返回全部结果
                                    if (searchSource == MusicSettings.SEARCH_SOURCE_KUWO &&
                                        !searching && searchResults.isNotEmpty()
                                    ) {
                                        val nextPage = searchPage + 1
                                        searching = true
                                        uiScope.launch {
                                            val r = KuwoMusicApi.search(query, nextPage, 20)
                                            if (r.isSuccess) {
                                                searchResults = searchResults + r.getOrThrow().map { SongInfo.fromKuwoSong(it) }
                                                searchPage = nextPage
                                            }
                                            searching = false
                                        }
                                    }
                                },
                                favKeys = favKeys,
                                currentKey = engine.currentSong?.key,
                                isPlaying = engine.isPlaying,
                                onPlay = { song -> engine.play(song, searchResults) },
                                onToggleFav = { song ->
                                    engine.store.toggleFavorite(song)
                                    refreshLibrary()
                                },
                                onDownload = { song -> startDownload(song) },
                                onDownloadLyric = { song -> downloadLyricFile(song) },
                                downloadOf = { key -> downloads.firstOrNull { it.song.key == key } }
                            )
                            Page.FAVORITES -> LibraryPage(
                                title = "我喜欢",
                                songs = favorites,
                                favKeys = favKeys,
                                currentKey = engine.currentSong?.key,
                                isPlaying = engine.isPlaying,
                                onPlay = { song -> engine.play(song, favorites) },
                                onPlayAll = { engine.playAll(favorites) },
                                onToggleFav = { song ->
                                    engine.store.toggleFavorite(song)
                                    refreshLibrary()
                                },
                                onDownload = { song -> startDownload(song) },
                                downloadOf = { key -> downloads.firstOrNull { it.song.key == key } }
                            )
                            Page.RECENT -> LibraryPage(
                                title = "最近播放",
                                songs = recent,
                                favKeys = favKeys,
                                currentKey = engine.currentSong?.key,
                                isPlaying = engine.isPlaying,
                                onPlay = { song -> engine.play(song, recent) },
                                onPlayAll = { engine.playAll(recent) },
                                onToggleFav = { song ->
                                    engine.store.toggleFavorite(song)
                                    refreshLibrary()
                                },
                                onDownload = { song -> startDownload(song) },
                                downloadOf = { key -> downloads.firstOrNull { it.song.key == key } }
                            )
                            Page.LOCAL -> LocalPage(
                                songs = localSongs,
                                scanning = scanning,
                                onScan = { scanLocal() },
                                favKeys = favKeys,
                                currentKey = engine.currentSong?.key,
                                isPlaying = engine.isPlaying,
                                onPlay = { song -> engine.play(song, localSongs) },
                                onPlayAll = { engine.playAll(localSongs) },
                                onToggleFav = { song ->
                                    engine.store.toggleFavorite(song)
                                    refreshLibrary()
                                }
                            )
                            Page.DOWNLOADS -> DownloadsPage(
                                items = downloads,
                                saveDir = musicDownloadDir(context).absolutePath,
                                onRetry = { item -> startDownload(item.song) }
                            )
                            Page.SETTINGS -> SettingsPage(
                                settings = musicSettings,
                                onChange = { updateSettings(it) },
                                onPickLyricImage = { openPicker("lyricImage", "image") },
                                onPickHomeImage = { openPicker("homeImage", "image") },
                                onPickCoverImage = { openPicker("coverImage", "image") },
                                onPickDiscImage = { openPicker("discImage", "image") },
                                onPickFolder = { openPicker("folder", "dir") },
                                onRescan = { scanLocal() },
                                lyricLocked = lyricOverlayState.locked,
                                onLyricLockChange = { updateLyricLock(it) }
                            )
                        }
                    }

                    // ===== 3D 歌词秀覆盖层（铺满内容区；播放条/顶栏/底栏此时移除） =====
                    // 注意：AnimatedVisibility 的 ColumnScope 扩展不能透过外层 Column
                    // 的隐式接收器调用（与原版相同），必须用显式 Column 提供最近接收者
                    Column(Modifier.fillMaxSize()) {
                        AnimatedVisibility(
                            visible = showLyrics,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.fillMaxSize()
                        ) {
                        Lyrics3DPage(
                            song = engine.currentSong,
                            positionMs = engine.positionMs,
                            durationMs = engine.durationMs,
                            isPlaying = engine.isPlaying,
                            isPreparing = engine.isPreparing,
                            lyric = engine.lyricDoc,
                            lyricLoading = engine.lyricLoading,
                            playMode = engine.playMode,
                            settingsProvider = { musicSettings },
                            positionProvider = { engine.rawPositionMs() },
                            onSeek = { engine.seekTo(it) },
                            onToggle = { engine.toggle() },
                            onNext = { engine.next() },
                            onPrev = { engine.prev() },
                            onCycleMode = { engine.cycleMode() },
                            onDownloadLyric = { downloadLyricFile(engine.currentSong) },
                            onClose = { showLyrics = false },
                            // v2.22：歌词秀双界面（3D 墙 / 黑胶唱片机）点击封面切换，经设置持久化
                            onUpdateSettings = { updateSettings(it) },
                            isTrueFullscreen = isFullscreen,
                            onToggleFullscreen = onToggleFullscreen
                        )
                        }
                    }
                }

                if (!showLyrics) {
                    PlayerBarMobile(
                        engine = engine,
                        isFav = engine.currentSong?.let { favKeys.contains(it.key) } ?: false,
                        // v1.2：设置里自定义了封面图时，播放处优先显示该封面（与歌词页/通知一致）
                        coverOverride = musicSettings.coverImage,
                        onToggleFav = {
                            engine.currentSong?.let { song ->
                                engine.store.toggleFavorite(song)
                                refreshLibrary()
                            }
                        },
                        lyricsOpen = showLyrics,
                        onToggleLyrics = { showLyrics = !showLyrics }
                    )
                    MusicBottomNav(
                        page = page,
                        onPageChange = { page = it }
                    )
                }
            }
        }
    }
}

// ==================== 顶部标题栏 ====================

@Composable
private fun MusicTopBar(onSettings: () -> Unit) {
    // v1.2：自定义背景时顶栏降透至 35%（白玻璃）/ 45%（黑玻璃），文字随明暗自适应
    val sch = LocalAdaptiveScheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (sch.isCustom) sch.glass else Mc.sidebarBg)
            // 状态栏/刘海区域由本栏背景延伸填充（edge-to-edge），内容避让到状态栏下方
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Logo
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Mc.red),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "AnWind 云音乐",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = sch.textPrimary,
            style = TextStyle(shadow = sch.shadow),
            maxLines = 1
        )
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Filled.Settings,
            contentDescription = "设置",
            tint = if (sch.isCustom) sch.textPrimary else Mc.textSecondary,
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onSettings)
                .padding(2.dp)
        )
    }
}

// ==================== 底部导航栏 ====================

@Composable
private fun MusicBottomNav(page: Page, onPageChange: (Page) -> Unit) {
    // v1.2：自定义背景时底栏降透且颜色随明暗自适应（白玻璃/黑玻璃）
    val sch = LocalAdaptiveScheme.current
    HorizontalDivider(
        thickness = 0.5.dp,
        color = if (sch.isCustom) sch.divider else Mc.divider
    )
    NavigationBar(
        containerColor = if (sch.isCustom) sch.glass else Color.White,
        tonalElevation = 0.dp
    ) {
        val items = listOf(
            Page.SEARCH to Icons.Filled.Search,
            Page.FAVORITES to Icons.Filled.Favorite,
            Page.RECENT to Icons.Filled.History,
            Page.LOCAL to Icons.Filled.LibraryMusic,
            Page.DOWNLOADS to Icons.Filled.Download
        )
        for ((p, icon) in items) {
            NavigationBarItem(
                selected = page == p,
                onClick = { onPageChange(p) },
                icon = { Icon(icon, contentDescription = p.label, modifier = Modifier.size(22.dp)) },
                label = { Text(p.label, fontSize = 10.sp, style = TextStyle(shadow = sch.shadow)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Mc.red,
                    selectedTextColor = Mc.red,
                    // 自定义背景下未选中图标/文字实心自适应色，保证可读
                    unselectedIconColor = sch.textSecondary,
                    unselectedTextColor = sch.textSecondary,
                    indicatorColor = Color(0x1AEC4141)
                )
            )
        }
    }
}

// ==================== 底部播放条（手机紧凑版） ====================

@Composable
private fun PlayerBarMobile(
    engine: MusicEngine,
    isFav: Boolean,
    /** v1.2：设置里自定义的封面图（非空时播放处直接显示它，不再回落歌曲封面/默认封面） */
    coverOverride: String?,
    onToggleFav: () -> Unit,
    lyricsOpen: Boolean,
    onToggleLyrics: () -> Unit
) {
    val song = engine.currentSong
    // v1.2：自定义背景时播放条降透至 35%（白玻璃）/ 45%（黑玻璃），文字自适应+光晕
    val sch = LocalAdaptiveScheme.current
    var userSeeking by remember { mutableStateOf(false) }
    var seekPos by remember { mutableStateOf(0f) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (sch.isCustom) sch.glass else Color.White)
            .padding(top = 4.dp)
    ) {
        // 进度条（细线，红色）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = fmtTime(if (userSeeking) seekPos.toLong() else engine.positionMs),
                fontSize = 10.sp,
                color = if (sch.isCustom) sch.textTertiary else Mc.textTertiary,
                style = TextStyle(shadow = sch.shadow)
            )
            Slider(
                value = if (userSeeking) seekPos else {
                    engine.positionMs.coerceAtMost(engine.durationMs).toFloat()
                },
                valueRange = 0f..engine.durationMs.coerceAtLeast(1L).toFloat(),
                onValueChange = {
                    userSeeking = true
                    seekPos = it
                },
                onValueChangeFinished = {
                    engine.seekTo(seekPos.toLong())
                    userSeeking = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = Mc.red,
                    activeTrackColor = Mc.red,
                    inactiveTrackColor = if (sch.isCustom) sch.track else Color(0xFFE5E5E8)
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(16.dp)
                    .padding(horizontal = 6.dp)
            )
            Text(
                text = fmtTime(engine.durationMs),
                fontSize = 10.sp,
                color = if (sch.isCustom) sch.textTertiary else Mc.textTertiary,
                style = TextStyle(shadow = sch.shadow)
            )
        }

        // 第二行：封面+歌名 | 词 | 模式 | 收藏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .clickable(onClick = onToggleLyrics)
            ) {
                if (!coverOverride.isNullOrEmpty()) {
                    // v1.2：设置里自定义了封面图 → 播放处直接显示它（与歌词页封面/通知大图一致）
                    BgImage(coverOverride, Modifier.size(40.dp))
                } else {
                    AsyncCover(song = song, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = song?.name ?: "未在播放",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (sch.isCustom) sch.textPrimary else Mc.textPrimary,
                    style = TextStyle(shadow = sch.shadow),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song?.let { "${it.artist} · ${it.album.ifBlank { "未知专辑" }}" } ?: "云音乐，发现好音乐",
                    fontSize = 10.sp,
                    color = if (sch.isCustom) sch.textTertiary else Mc.textTertiary,
                    style = TextStyle(shadow = sch.shadow),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))

            // 词 按钮（打开/关闭 3D 歌词秀）
            Box(
                Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (lyricsOpen) Mc.red else Color.Transparent)
                    .clickable(onClick = onToggleLyrics)
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "词",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (lyricsOpen) Color.White
                            else if (sch.isCustom) sch.textSecondary else Mc.textSecondary,
                    style = TextStyle(shadow = if (lyricsOpen) null else sch.shadow)
                )
            }
            Spacer(Modifier.width(12.dp))

            // 播放模式
            Icon(
                imageVector = when (engine.playMode) {
                    MusicStore.MODE_LOOP_ONE -> Icons.Filled.RepeatOne
                    MusicStore.MODE_SHUFFLE -> Icons.Filled.Shuffle
                    else -> Icons.Filled.Repeat
                },
                contentDescription = "播放模式",
                tint = if (sch.isCustom) sch.textSecondary else Mc.textSecondary,
                modifier = Modifier
                    .size(17.dp)
                    .clickable { engine.cycleMode() }
            )
            Spacer(Modifier.width(14.dp))

            // 收藏
            Icon(
                imageVector = if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = "喜欢",
                tint = if (isFav) Mc.red
                       else if (sch.isCustom) sch.textSecondary else Mc.textSecondary,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onToggleFav)
            )
        }

        // 第三行：上一首 / 播放 / 下一首（居中）+ 音量
        Row(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "上一首",
                        tint = if (sch.isCustom) sch.textPrimary else Mc.textPrimary,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { engine.prev() }
                    )
                    Spacer(Modifier.width(14.dp))
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Mc.red)
                            .clickable { engine.toggle() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (engine.isPreparing) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Icon(
                                imageVector = if (engine.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (engine.isPlaying) "暂停" else "播放",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "下一首",
                        tint = if (sch.isCustom) sch.textPrimary else Mc.textPrimary,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { engine.next() }
                    )
                }
            }

            // 音量
            Icon(
                Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "音量",
                tint = if (sch.isCustom) sch.textSecondary else Mc.textSecondary,
                modifier = Modifier.size(16.dp)
            )
            Slider(
                value = engine.volume,
                valueRange = 0f..1f,
                onValueChange = { engine.volume = it },
                colors = SliderDefaults.colors(
                    thumbColor = if (sch.isCustom) sch.textSecondary else Mc.textSecondary,
                    activeTrackColor = if (sch.isCustom) sch.textSecondary else Mc.textSecondary,
                    inactiveTrackColor = if (sch.isCustom) sch.track else Color(0xFFE5E5E8)
                ),
                modifier = Modifier
                    .width(84.dp)
                    .height(16.dp)
            )
        }
    }
}
