package com.anwindmusic.music

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 内置图片/视频选择器（v1.4 新增，电视实测修复）：
 *
 * 问题：电视/盒子普遍没有系统文件管理器（DocumentsUI），设置页点
 * 「选择背景图片/视频」时 ACTION_OPEN_DOCUMENT 直接失败，什么都选不了。
 *
 * 方案：应用内全屏选择器直接查 MediaStore 图片/视频库，网格缩略图展示，
 * 遥控器四向移动 + OK 选中（复用 tvFocus 焦点高亮体系）；结果复制到
 * 应用私有目录后经 PickBus 回传，与原 SAF 流程完全一致。
 * 手机上系统选择器优先，SAF 拉起失败时也自动回落到这里。
 *
 * 权限：READ_MEDIA_IMAGES / READ_MEDIA_VIDEO（Android 13+）或
 * READ_EXTERNAL_STORAGE（Android 12 及以下），打开时按需申请一次。
 */
@Composable
fun InAppMediaPicker(
    /** 选择用途（homeImage / lyricImage / coverImage / discImage / homeVideo / lyricVideo） */
    kind: String,
    onClose: () -> Unit,
    onPicked: (Uri) -> Unit
) {
    val context = LocalContext.current
    val video = kind.endsWith("Video")
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        if (video) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    // null = 加载中；空表 = 无媒体
    var mediaItems by remember { mutableStateOf<List<MediaSaver.MediaItem>?>(null) }
    LaunchedEffect(hasPermission, video) {
        mediaItems = if (hasPermission) {
            withContext(Dispatchers.IO) { MediaSaver.queryMediaItems(context, video) }
        } else null
    }

    // 缩略图缓存（滚动不重复解码）
    val thumbCache = remember { LruCache<String, Bitmap>(96) }

    // v1.4.2 TV 焦点彻底修复（上一版 onPreviewKeyEvent 安全网根本不会触发：
    // Compose 按键派发走焦点树，焦点不在选择器子树内时，选择器的 onPreviewKeyEvent
    // 永远不调用 → 遥控方向/OK 全派发给背后主界面的播放键）。
    // 正解：根 Box 自身 focusable + 抢焦重试，焦点进子树后 onPreviewKeyEvent 才会
    // 生效，再由 focusManager.moveFocus 在子树内寻焦。
    val rootFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // isFocused=true 表示根 Box 自身持焦（非子孙）；用于区分“根持焦跳到关闭键” vs “子孙持焦几何寻焦”
    var rootSelfFocused by remember { mutableStateOf(false) }
    if (TvCompat.isTv) {
        LaunchedEffect(Unit) {
            // 重试抢焦点：focusable 节点可能在首帧还未就绪，最多重试 10 次
            repeat(10) {
                runCatching { rootFocus.requestFocus() }
                delay(50)
            }
        }
    }

    BackHandler { onClose() }

    // 全屏深色覆盖层（盖在主界面之上，状态栏同色）
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (TvCompat.isTv) Modifier
                    .focusRequester(rootFocus)
                    .focusable()
                    .onFocusChanged { state -> rootSelfFocused = state.isFocused }
                    .onPreviewKeyEvent { e ->
                        // 焦点已在选择器子树内（根或子孙），本拦截器一定触发
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> {
                                if (rootSelfFocused) {
                                    // 根自身持焦 → moveFocus 从全屏元素不可靠，直接跳关闭键
                                    runCatching { closeFocus.requestFocus() }
                                } else {
                                    focusManager.moveFocus(FocusDirection.Down)
                                }
                                true
                            }
                            Key.DirectionUp -> {
                                if (!rootSelfFocused) focusManager.moveFocus(FocusDirection.Up)
                                true
                            }
                            Key.DirectionLeft -> {
                                if (!rootSelfFocused) focusManager.moveFocus(FocusDirection.Left)
                                true
                            }
                            Key.DirectionRight -> {
                                if (!rootSelfFocused) focusManager.moveFocus(FocusDirection.Right)
                                true
                            }
                            Key.DirectionCenter, Key.Enter -> {
                                // 根自身持焦 → 消费（无动作）；子孙持焦 → 放行让 clickable 处理
                                !rootSelfFocused
                            }
                            else -> false
                        }
                    }
                else Modifier
            )
            .background(Color(0xF7141418))
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 12.dp)) {
            // ---- 顶栏：标题 + 关闭 ----
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (video) Icons.Filled.Videocam else Icons.Filled.Image,
                    contentDescription = null,
                    tint = Mc.red,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (video) "选择背景视频" else "选择背景图片",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .focusRequester(closeFocus)
                        .tvFocus(RoundedCornerShape(10.dp), scaleUp = true)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            when {
                // ---- 未授权：引导按钮（遥控可聚焦）----
                !hasPermission -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "需要访问${if (video) "视频" else "图片"}权限才能选择背景",
                        color = Color(0xFFC9C9CF),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Mc.red)
                            .tvFocus(RoundedCornerShape(18.dp), accent = Color.White)
                            .clickable { runCatching { permLauncher.launch(permission) } }
                            .padding(horizontal = 28.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "授予权限",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "拒绝过一次的系统需到系统设置里手动开启",
                        color = Color(0xFF6C6C7A),
                        fontSize = 11.sp
                    )
                }
                // ---- 加载中 ----
                mediaItems == null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Mc.red, strokeWidth = 3.dp)
                }
                // ---- 无媒体 ----
                mediaItems!!.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "设备上没有找到${if (video) "视频" else "图片"}",
                        color = Color(0xFFC9C9CF),
                        fontSize = 14.sp
                    )
                }
                // ---- 网格 ----
                else -> LazyVerticalGrid(
                    // v1.4.1：TV 网格缩小到 140dp，提升信息密度（HD 风格，非放大版手机）
                    columns = GridCells.Adaptive(minSize = if (TvCompat.isTv) 140.dp else 110.dp),
                    modifier = Modifier.fillMaxSize().padding(bottom = 12.dp)
                ) {
                    items(mediaItems!!, key = { it.id }) { item ->
                        MediaCell(
                            item = item,
                            video = video,
                            thumbCache = thumbCache,
                            onClick = { onPicked(item.uri) }
                        )
                    }
                }
            }
        }
    }
}

/** 网格单元：缩略图 + 名称（视频带时长角标）；遥控聚焦红框放大 */
@Composable
private fun MediaCell(
    item: MediaSaver.MediaItem,
    video: Boolean,
    thumbCache: LruCache<String, Bitmap>,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var bmp by remember(item.id) { mutableStateOf<Bitmap?>(thumbCache.get(item.id.toString())) }
    LaunchedEffect(item.id) {
        if (bmp == null) {
            bmp = withContext(Dispatchers.IO) { MediaSaver.loadMediaThumbnail(context, item) }
            bmp?.let { thumbCache.put(item.id.toString(), it) }
        }
    }
    Column(
        Modifier
            .padding(5.dp)
            .clip(RoundedCornerShape(10.dp))
            .tvFocus(RoundedCornerShape(10.dp), scaleUp = true)
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF232329)))
            }
            if (video && item.durationMs > 0) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = fmtTime(item.durationMs),
                        color = Color.White,
                        fontSize = 9.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.name,
            color = Color(0xFFC9C9CF),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
