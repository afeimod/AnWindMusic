package com.anwindmusic.music

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置图片/视频选择器（v1.4.3 重写：独立窗口 + 文件夹浏览 + 大图预览）
 *
 * v1.4.3 彻底解决 TV 遥控穿透：选择器放在 [Dialog] 独立窗口里 —— Android 窗口
 * 管理器把输入焦点给到 Dialog 窗口，D-pad 事件物理隔离，不可能穿透到背后的音乐 App。
 * 不再依赖 Compose 焦点树的抢焦/拦截（v1.4.1/v1.4.2 的 FocusRequester + onPreviewKeyEvent
 * 方案在部分电视固件上失效，根因是 Compose 按键派发走焦点树、overlay 不自动获焦）。
 *
 * 同时升级为文件管理器风格：
 * - **文件夹芯片**：按 MediaStore BUCKET_DISPLAY_NAME 分组（DCIM / Pictures / Movies /
 *   Download …），「全部」+ 各文件夹带数量，遥控/点击切换筛选
 * - **TV 横屏分栏**：左侧缩略图网格（130dp 单元），右侧聚焦项大图预览（512dp 高清缩略图
 *   + 文件名 + 时长 + 所在文件夹 + 操作提示）
 * - **手机/竖屏**：单网格（110dp 单元），与原行为一致
 * - 权限：READ_MEDIA_IMAGES / READ_MEDIA_VIDEO（33+）或 READ_EXTERNAL_STORAGE（≤32）
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

    // 缩略图缓存（滚动不重复解码；大图预览用 _lg 后缀 key 避免覆盖小图）
    val thumbCache = remember { LruCache<String, Bitmap>(96) }
    // 文件夹筛选（null = 全部）
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    // 当前聚焦的条目（TV 大图预览同步显示）
    var focusedItem by remember { mutableStateOf<MediaSaver.MediaItem?>(null) }

    // 文件夹列表（从媒体条目按 BUCKET_DISPLAY_NAME 聚合，按数量降序）
    val folders = remember(mediaItems) {
        mediaItems?.mapNotNull { it.bucketName.ifBlank { null } }
            ?.groupingBy { it }?.eachCount()
            ?.toList()?.sortedByDescending { it.second }
            ?.map { it.first }
            ?: emptyList()
    }
    val filtered = if (selectedFolder == null) mediaItems
                   else mediaItems?.filter { it.bucketName == selectedFolder }

    // v1.4.3 核心：Dialog 独立窗口 —— 焦点物理隔离，D-pad 不可能穿透到背后主界面
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        // 全屏深色覆盖层
        Box(
            Modifier
                .fillMaxSize()
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
                        modifier = Modifier.tvFocus(RoundedCornerShape(10.dp), scaleUp = true)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // ---- 文件夹筛选条（文件管理器风格）----
                if (folders.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FolderChip(
                                label = "全部 (${mediaItems?.size ?: 0})",
                                selected = selectedFolder == null,
                                onClick = { selectedFolder = null }
                            )
                        }
                        items(folders) { folder ->
                            val count = mediaItems?.count { it.bucketName == folder } ?: 0
                            FolderChip(
                                label = "$folder ($count)",
                                selected = selectedFolder == folder,
                                onClick = { selectedFolder = folder }
                            )
                        }
                    }
                }

                // ---- 内容区 ----
                when {
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
                    mediaItems == null -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Mc.red, strokeWidth = 3.dp)
                    }
                    filtered.isNullOrEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (selectedFolder != null)
                                       "$selectedFolder 中没有${if (video) "视频" else "图片"}"
                                   else "设备上没有找到${if (video) "视频" else "图片"}",
                            color = Color(0xFFC9C9CF),
                            fontSize = 14.sp
                        )
                    }
                    else -> {
                        // TV 横屏：左网格 + 右大图预览；手机/竖屏：仅网格
                        if (TvCompat.isTv) {
                            Row(Modifier.fillMaxSize().padding(bottom = 12.dp)) {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 130.dp),
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                ) {
                                    gridItems(filtered, key = { it.id }) { item ->
                                        MediaCell(
                                            item = item,
                                            video = video,
                                            thumbCache = thumbCache,
                                            isFocused = focusedItem?.id == item.id,
                                            onFocusChange = { f -> if (f) focusedItem = item },
                                            onClick = { onPicked(item.uri) }
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                // 大图预览面板（聚焦项；未聚焦时显示首项）
                                LargePreview(
                                    item = focusedItem ?: filtered.first(),
                                    video = video,
                                    thumbCache = thumbCache,
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                )
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 110.dp),
                                modifier = Modifier.fillMaxSize().padding(bottom = 12.dp)
                            ) {
                                gridItems(filtered, key = { it.id }) { item ->
                                    MediaCell(
                                        item = item,
                                        video = video,
                                        thumbCache = thumbCache,
                                        isFocused = false,
                                        onFocusChange = {},
                                        onClick = { onPicked(item.uri) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 文件夹芯片（文件管理器风格筛选标签：文件夹图标 + 名称 + 数量） */
@Composable
private fun FolderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Mc.red else Color(0xFF2A2A33))
            .tvFocus(RoundedCornerShape(16.dp), accent = Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = if (selected) Color.White else Color(0xFF9A9AA5),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFFC9C9CF),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/** 网格单元：缩略图 + 名称（视频带时长角标）；聚焦时上报父级用于大图预览同步 */
@Composable
private fun MediaCell(
    item: MediaSaver.MediaItem,
    video: Boolean,
    thumbCache: LruCache<String, Bitmap>,
    isFocused: Boolean,
    onFocusChange: (Boolean) -> Unit,
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
            .onFocusChanged { state -> onFocusChange(state.isFocused) }
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
            // 聚焦时叠加选中提示（TV 上更醒目，提示按 OK 选中）
            if (isFocused) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Mc.red)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "OK 选中",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
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

/**
 * TV 大图预览面板（右侧分栏）：聚焦项的高清缩略图 + 文件名 + 时长 + 所在文件夹 +
 * 操作提示。缩略图用 512dp（与网格 256dp 缓存 key 加 _lg 后缀隔离，互不覆盖）。
 */
@Composable
private fun LargePreview(
    item: MediaSaver.MediaItem,
    video: Boolean,
    thumbCache: LruCache<String, Bitmap>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var largeBmp by remember(item.id) {
        mutableStateOf<Bitmap?>(thumbCache.get("${item.id}_lg"))
    }
    LaunchedEffect(item.id) {
        largeBmp = withContext(Dispatchers.IO) {
            MediaSaver.loadMediaThumbnail(context, item, size = 512)
        }
        largeBmp?.let { thumbCache.put("${item.id}_lg", it) }
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1A1A22))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF232329)),
            contentAlignment = Alignment.Center
        ) {
            val b = largeBmp
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator(color = Mc.red, strokeWidth = 3.dp)
            }
            if (video) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0x99000000))
                        .padding(vertical = 6.dp, horizontal = 10.dp)
                ) {
                    Text(
                        text = if (item.durationMs > 0) "视频 · ${fmtTime(item.durationMs)}" else "视频",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = item.name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (item.bucketName.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "📁 ${item.bucketName}",
                color = Color(0xFF9A9AA5),
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "按 OK 键选中 · 按返回键关闭",
            color = Color(0xFF6C6C7A),
            fontSize = 10.sp
        )
    }
}
