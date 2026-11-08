package com.anwindmusic.music

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * v1.4 媒体存取工具：彻底移除「所有文件访问」（MANAGE_EXTERNAL_STORAGE）后的合规替代。
 *
 * 背景：该权限是国内手机/电视安全检测判定「存在违规功能，禁止安装」的主因之一。
 * Android 10+ 的一切共享存储读写均改走 MediaStore（标准、免权限、其他应用可见）：
 * - 下载音乐 → MediaStore.Audio（公共音乐库 Music/AnWindMusic）
 * - 下载歌词 → MediaStore.Downloads（Download/AnWindMusic）
 * - 指定目录扫描 → MediaStore 路径过滤查询（见 MusicPages.scanDirsViaMediaStore）
 * - 内置图片/视频选择器 → MediaStore.Images / Video 查询（电视无 DocumentsUI 的兜底）
 * Android 9 及以下保持公共目录直接 File 写入（WRITE_EXTERNAL_STORAGE）。
 */
object MediaSaver {

    // ==================== 写入：下载音乐 ====================

    /**
     * 保存下载的音频到公共音乐库：
     * - Android 10+：MediaStore.Audio 插入（Music/AnWindMusic，免权限，
     *   IS_PENDING 事务写入，系统媒体库立即索引，其他播放器可见）；
     * - Android 9-：直接复制到公共 Music/AnWindMusic（WRITE_EXTERNAL_STORAGE）。
     * @param source 已下载完成的临时文件（调用方负责删除）
     * @return 实际落盘文件（路径供本地曲库合并去重），失败返回 null
     */
    fun saveAudioFile(context: Context, displayName: String, mimeType: String, source: File): File? {
        if (Build.VERSION.SDK_INT >= 29) {
            return insertToMediaStore(
                context, source, displayName, mimeType,
                collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                relativePath = Environment.DIRECTORY_MUSIC + "/AnWindMusic"
            )
        }
        return copyToPublicDir(context, source, displayName, subDir = "AnWindMusic")
    }

    // ==================== 写入：下载歌词 ====================

    /**
     * 保存 .lrc 歌词文件：
     * - Android 10+：MediaStore.Downloads（Download/AnWindMusic，任意类型文件均可入Downloads 集合，免权限）；
     * - Android 9-：与音乐同目录（公共 Music/AnWindMusic）直接写入。
     */
    fun saveLyricFile(context: Context, displayName: String, text: String): File? {
        if (Build.VERSION.SDK_INT >= 29) {
            val tmp = File(context.cacheDir, "lrc_${System.currentTimeMillis()}.tmp")
            return try {
                tmp.writeText(text)
                insertToMediaStore(
                    context, tmp, displayName, "application/octet-stream",
                    collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    relativePath = Environment.DIRECTORY_DOWNLOADS + "/AnWindMusic"
                )
            } finally {
                runCatching { tmp.delete() }
            }
        }
        return runCatching {
            val dir = musicDownloadDir(context)
            if (!dir.exists() && !dir.mkdirs()) return@runCatching null
            val f = File(dir, displayName)
            f.writeText(text)
            f
        }.getOrNull()
    }

    /** MediaStore 事务写入：insert（IS_PENDING=1）→ 拷流 → 发布（IS_PENDING=0）→ 返回真实路径 */
    private fun insertToMediaStore(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
        collection: Uri,
        relativePath: String
    ): File? = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return@runCatching null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("openOutputStream failed")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            // 发布后读 DATA 列拿真实绝对路径（供本地曲库合并/进度显示）
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return@runCatching File(c.getString(0))
            }
            null
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }.getOrNull()

    /** Android 9-：直接写公共目录 */
    private fun copyToPublicDir(
        context: Context,
        source: File,
        displayName: String,
        subDir: String
    ): File? = runCatching {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            subDir
        )
        if (!dir.exists() && !dir.mkdirs()) return@runCatching null
        val dest = File(dir, displayName)
        source.copyTo(dest, overwrite = true)
        dest
    }.getOrNull()

    // ==================== 内置选择器：媒体查询 ====================

    /** 内置选择器条目（图片或视频） */
    data class MediaItem(
        val id: Long,
        val uri: Uri,
        val name: String,
        /** 视频：时长 ms；图片：0 */
        val durationMs: Long
    )

    /**
     * 查询设备图片/视频（内置选择器数据源，按添加时间倒序，最多 [limit] 条）。
     * 需 READ_MEDIA_IMAGES/READ_MEDIA_VIDEO（33+）或 READ_EXTERNAL_STORAGE（≤32）。
     */
    fun queryMediaItems(context: Context, video: Boolean, limit: Int = 600): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        val collection = if (video) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED
        )
        runCatching {
            context.contentResolver.query(
                collection,
                if (video) projection + MediaStore.Video.Media.DURATION else projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val durCol = if (video) c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1
                while (c.moveToNext() && list.size < limit) {
                    val id = c.getLong(idCol)
                    list.add(
                        MediaItem(
                            id = id,
                            uri = Uri.withAppendedPath(
                                if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id.toString()
                            ),
                            name = c.getString(nameCol) ?: "",
                            durationMs = if (video) c.getLong(durCol) else 0L
                        )
                    )
                }
            }
        }
        return list
    }

    /** 内置选择器缩略图：29+ loadThumbnail（图片/视频通用）；旧系统用 Thumbnails 表 */
    fun loadMediaThumbnail(context: Context, item: MediaItem, size: Int = 256): Bitmap? {
        return if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                context.contentResolver.loadThumbnail(
                    item.uri, android.util.Size(size, size), null
                )
            }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                val opts = BitmapFactory.Options()
                if (item.durationMs > 0) {
                    MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver, item.id,
                        MediaStore.Video.Thumbnails.MINI_KIND, opts
                    )
                } else {
                    MediaStore.Images.Thumbnails.getThumbnail(
                        context.contentResolver, item.id,
                        MediaStore.Images.Thumbnails.MINI_KIND, opts
                    )
                }
            }.getOrNull()
        }
    }

    // ==================== SAF/内置选择器结果统一处理 ====================

    /**
     * 把选中的 content:// 图片/视频复制到应用私有目录并返回绝对路径：
     * 相比直接存 URI 无授权过期/重启失效问题，BgImage/BgVideo 按路径读取。
     * （原 MainActivity.copyUriToInternal 逻辑收编，供 SAF 与内置选择器共用）
     */
    fun copyMediaToInternal(context: Context, uri: Uri, kind: String): String? = runCatching {
        val ext = when (context.contentResolver.getType(uri)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            "video/quicktime" -> "mov"
            "video/3gpp" -> "3gp"
            else -> if (kind.endsWith("Video")) "mp4" else "jpg"
        }
        val dir = File(context.filesDir, "picked_images").apply { mkdirs() }
        val out = File(dir, "${kind}_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        out.absolutePath
    }.getOrNull()
}
