package com.anwindmusic.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.anwindmusic.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 播放控制入口：起播时由播放器组合调用，拉起前台服务实现后台持续播放。
 * 引擎无当前歌曲时静默忽略（避免空通知）。
 */
object PlaybackServiceController {
    fun start(context: Context) {
        if (MusicEngineHolder.peek()?.currentSong == null) return
        runCatching {
            val intent = Intent(context, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}

/**
 * 音乐播放前台服务（独立版新增）：
 * - mediaPlayback 前台服务：退后台/锁屏后播放不中断（进程保活）
 * - 通知栏控制：上一首 / 播放暂停 / 下一首 / 退出（MediaStyle 大字体样式）
 * - MediaSessionCompat：锁屏控制、耳机/蓝牙线控（媒体按键）
 * - 500ms 轮询引擎状态刷新通知（歌名/封面/播放态变化才重建通知，避免闪烁）
 * - 「退出」动作：停播并释放引擎（进度已由引擎会话记忆落盘）、关闭桌面歌词、移除通知
 *
 * 引擎为进程级单例（MusicEngineHolder），本服务只做「保活 + 控制 + 展示」；
 * Activity 重新打开时 UI 直读同一引擎，无缝恢复播放画面。
 */
class PlaybackService : Service() {

    companion object {
        private const val NOTIF_ID = 10086
        private const val CHANNEL_ID = "playback"
        private const val CHANNEL_NAME = "音乐播放"
        private const val POLL_MS = 500L
        private const val ACTION_TOGGLE = "com.anwindmusic.music.action.PLAYBACK_TOGGLE"
        private const val ACTION_NEXT = "com.anwindmusic.music.action.PLAYBACK_NEXT"
        private const val ACTION_PREV = "com.anwindmusic.music.action.PLAYBACK_PREV"
        private const val ACTION_EXIT = "com.anwindmusic.music.action.PLAYBACK_EXIT"
    }

    private lateinit var session: MediaSessionCompat
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 通知增量刷新依据：歌名 / 播放态 / 封面源变化才重建通知 */
    private var lastNotifKey: String = ""
    private var lastMetaKey: String? = null
    private var largeIcon: Bitmap? = null
    private var lastCoverSrc: String? = null

    private val tick = object : Runnable {
        override fun run() {
            updateSessionState()
            updateNotification()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun engine(): MusicEngine? = MusicEngineHolder.peek()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        setupSession()
        startForegroundWith(buildNotification())
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val e = engine()
        when (intent?.action) {
            ACTION_TOGGLE -> e?.toggle()
            ACTION_NEXT -> e?.next()
            ACTION_PREV -> e?.prev()
            ACTION_EXIT -> {
                exitNow()
                return START_NOT_STICKY
            }
        }
        // startForegroundService 必须立刻进入前台（onCreate 已调用，此处幂等兜底）
        startForegroundWith(buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        runCatching {
            session.isActive = false
            session.release()
        }
        super.onDestroy()
    }

    // ==================== MediaSession（锁屏/线控） ====================

    private fun setupSession() {
        session = MediaSessionCompat(this, "AnWindMusicPlayback").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = engine()?.let { if (!it.isPlaying) it.toggle() } ?: Unit
                override fun onPause() = engine()?.let { if (it.isPlaying) it.toggle() } ?: Unit
                override fun onSkipToNext() = engine()?.next() ?: Unit
                override fun onSkipToPrevious() = engine()?.prev() ?: Unit
                override fun onSeekTo(pos: Long) = engine()?.seekTo(pos) ?: Unit
                override fun onStop() = exitNow()
            })
            isActive = true
        }
    }

    /** 每次轮询同步播放态（锁屏进度条/按钮态、媒体按键路由） */
    private fun updateSessionState() {
        val e = engine() ?: return
        val state = when {
            e.isPreparing -> PlaybackStateCompat.STATE_CONNECTING
            e.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, e.rawPositionMs(), if (e.isPlaying) 1f else 0f)
                .build()
        )
        val song = e.currentSong
        // MediaSessionCompat 无 getMetadata：用本地 lastMetaKey 判断歌曲变化
        if (song != null && song.key != lastMetaKey) {
            lastMetaKey = song.key
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.name)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, e.durationMs)
                    .build()
            )
        }
    }

    // ==================== 通知 ====================

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): android.app.Notification {
        val e = engine()
        val song = e?.currentSong
        val playing = e?.isPlaying == true

        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun actionPi(action: String, requestCode: Int): PendingIntent =
            PendingIntent.getService(
                this, requestCode,
                Intent(this, PlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentIntent(contentPi)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "上一首", actionPi(ACTION_PREV, 11))
            .addAction(0, if (playing) "暂停" else "播放", actionPi(ACTION_TOGGLE, 12))
            .addAction(0, "下一首", actionPi(ACTION_NEXT, 13))
            .addAction(0, "退出", actionPi(ACTION_EXIT, 14))
            .apply {
                if (song != null) {
                    setContentTitle(song.name)
                    setContentText(
                        listOf(song.artist, song.album)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                            .ifBlank { "AnWind 云音乐" }
                    )
                    largeIcon?.let { setLargeIcon(it) }
                    setProgress(
                        100,
                        if (e.durationMs > 0) ((e.positionMs * 100) / e.durationMs).toInt() else 0,
                        e.isPreparing
                    )
                } else {
                    setContentTitle("AnWind 云音乐")
                    setContentText("播放已结束")
                }
            }
        // MediaStyle：锁屏/通知栏大字样式，紧凑区显示前三个动作
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
        )
        return builder.build()
    }

    /** 歌名/播放态/封面变化才重建通知（避免每 500ms 闪烁） */
    private fun updateNotification() {
        val e = engine() ?: return
        val song = e.currentSong
        val key = "${song?.key}|${e.isPlaying}|${e.isPreparing}|${largeIcon != null}"
        if (key == lastNotifKey) return
        lastNotifKey = key
        refreshLargeIcon(song)
        startForegroundWith(buildNotification())
    }

    private fun startForegroundWith(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /**
     * 异步加载封面作通知大图（加载完成后再刷新一次通知）：
     * v1.2：设置里自定义了封面图时优先使用（播放处统一：播放条/歌词页/通知/锁屏）；
     * 否则在线歌用网络封面；本地歌提取内嵌封面（LocalCover 磁盘缓存）；都无则无大图。
     */
    private fun refreshLargeIcon(song: SongInfo?) {
        val customCover = if (song == null) null else runCatching {
            engine()?.store?.loadMusicSettings()?.coverImage
        }.getOrNull()?.takeIf { it.isNotBlank() && File(it).isFile }
        val src = when {
            song == null -> null
            customCover != null -> "custom:$customCover"
            !song.isLocal && song.picUrl.isNotBlank() -> song.picUrl
            else -> "local:${song.key}"   // 本地歌：内嵌封面（含提取失败后的空图）
        }
        if (src == lastCoverSrc) return
        lastCoverSrc = src
        serviceScope.launch {
            largeIcon = if (song == null) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    when {
                        customCover != null ->
                            runCatching { loadBitmap(customCover, 512) }.getOrNull()
                        !song.isLocal && song.picUrl.isNotBlank() ->
                            runCatching { loadBitmap(song.picUrl, 512) }.getOrNull()
                        else -> {
                            val path = LocalCover.extractCached(applicationContext, song)
                            path?.let { runCatching { loadBitmap(it, 512) }.getOrNull() }
                        }
                    }
                }
            }
            lastNotifKey = ""
            updateNotification()
        }
    }

    // ==================== 退出 ====================

    /** 通知栏「退出」：停播释放引擎（会话进度已由引擎落盘）+ 关桌面歌词 + 移除通知 */
    private fun exitNow() {
        MusicEngineHolder.shutdown()
        DesktopLyricBus.songName = ""
        DesktopLyricBus.lines = emptyList()
        DesktopLyricBus.index = -1
        DesktopLyricBus.playing = false
        runCatching { stopService(Intent(this, LyricOverlayService::class.java)) }
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }
}
