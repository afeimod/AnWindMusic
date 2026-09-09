# AnWindMusic —— AnWind 云音乐独立版

从 [AnWind](https://github.com/afeimod/AnWind) 项目中的「云音乐」应用整体移植而成的**独立安卓音乐播放器**，界面与功能对照网易云音乐 PC 版，包名 `com.anwindmusic`。

## 功能特性

### 在线音乐
- **搜索播放**：酷我音乐曲库关键词搜索（歌名/歌手/专辑），分页加载，热门关键词快捷入口
- **多源播放直链解析**：多个解析 API 依次回退，失败自动切换
- **歌曲下载**：MP3 下载（带进度条/失败重试/下载管理页），歌词同步下载为 .lrc
- **歌词下载**：任意歌曲可手动下载 .lrc（无需先播放）

### 歌词系统
- **四级词源回退**：酷我 → 网易云 → QQ 音乐 → LRCLIB，冷门歌/外文歌命中率高
- **3D 歌词秀**：真透视歌词墙（俯仰/偏航/纵深/行内逐字左右字体差）、KTV 逐字填色、
  当前行高亮发光、翻译显示、封面模糊背景、自定义背景图/封面/光盘图片
- **桌面歌词**：系统悬浮窗（两行模式 / 全屏横幅模式），KTV 逐字变色、可拖动、
  锁定触摸穿透、位置记忆、字号/颜色/透明度可调
- **智能歌词匹配**：多关键词候选（拆括号注释/拆 feat 合唱/文件名拆分），按歌名吻合度择优

### 音乐库
- **我喜欢** / **最近播放**（各 100 条记忆）
- **本地音乐**：MediaStore 全库扫描 或 仅扫描指定目录（SAF 文件夹选择 + 手动输入路径）
- **播放队列**：顺序播放 / 单曲循环 / 随机播放三种模式
- **会话记忆**：退出后记住队列/歌曲/进度，重开无缝续播

### 独立 App 适配（相对桌面版新增）
- **纯手机布局**：底部导航栏 + 紧凑播放条，竖屏/横屏统一手机样式
- **后台持续播放**：mediaPlayback 前台服务，退后台/锁屏不中断；
  通知栏显示封面/进度 + 上一首/播放暂停/下一首/退出控制
- **锁屏与线控**：MediaSession 支持，锁屏控制、耳机/蓝牙媒体按键
- **锁屏播放保活**：MediaPlayer setWakeMode，息屏不卡顿
- **系统选择器**：背景图/封面/光盘图片走系统图片选择器（复制到应用私有目录，不怕授权过期）；
  扫描目录走系统文件夹选择器（主存储自动换算真实路径）

## 环境要求

- Android 7.0（API 24）及以上
- 部分功能需要授权：
  - 「所有文件访问」——指定目录扫描、歌曲下载到公共 Music 目录（设置页有引导）
  - 「显示在应用上层」——桌面歌词悬浮窗（设置页有引导）

## 构建方式

### 方式一：GitHub Actions 手动构建（推荐）

本项目自带手动触发的构建工作流（`.github/workflows/build.yml`）：

1. 把本项目推送到你的 GitHub 仓库
2. 打开仓库 → **Actions** 标签页 → 左侧选择 **Build AnWindMusic APK**
3. 点击 **Run workflow**，选择构建类型（`release` / `debug` / `both`）→ 运行
4. 构建完成后在该次运行页面底部 **Artifacts** 下载 APK

> release 构建使用 CI 现场生成的正式 keystore 签名（V1+V2+V3），可直接安装到任何
> Android 7.0+ 设备，不会被系统标记为 testOnly。如需固定自己的签名，可在
> `app/build.gradle.kts` 中配置 Secrets 引入自己的 keystore。

### 方式二：本地构建

```bash
git clone <本仓库> AnWindMusic
cd AnWindMusic
./gradlew assembleRelease   # 需要 JDK 17+，Android SDK 34
# 产物：app/build/outputs/apk/release/app-release.apk
```

> 本地未配置 release keystore 时，release 包会回退 debug 签名（仅适合测试）。

## 技术栈

- Kotlin 1.9.25 + Jetpack Compose（BOM 2024.06.00，Material3）
- AGP 8.5.2 / Gradle 8.7 / compileSdk 34 / minSdk 24 / targetSdk 34
- 播放引擎：android.media.MediaPlayer（流媒体 + 本地），进程级单例
- 网络：HttpURLConnection + org.json（零第三方网络/图片库，封面自实现内存缓存）
- 通知与锁屏：androidx.media（MediaSessionCompat + MediaStyle）

## 目录结构

```
AnWindMusic/
├── app/src/main/java/com/anwindmusic/
│   ├── MainActivity.kt            # 入口：SAF 选择器 / 权限 / 沉浸式全屏
│   └── music/
│       ├── MusicPlayerApp.kt      # 手机版主界面（底部导航 + 紧凑播放条）+ PickBus
│       ├── MusicEngine.kt         # 播放引擎（队列/模式/进度/会话记忆/歌词推进）
│       ├── MusicData.kt           # 数据层（歌曲模型/LRC解析/收藏/设置/缓存）
│       ├── MusicComponents.kt     # 共享 UI 组件（配色/封面/搜索框/背景）
│       ├── MusicPages.kt          # 页面（搜索/歌单/本地/下载管理 + 本地扫描）
│       ├── MusicSettingsPage.kt   # 设置中心（背景/歌词秀/桌面歌词/词源/扫描）
│       ├── Lyrics3DPage.kt        # 3D 歌词秀
│       ├── LyricEngines.kt        # QQ 音乐 / LRCLIB 词源
│       ├── KuwoMusicApi.kt        # 酷我 API（搜索/直链/歌词/下载）+ 网易云兜底
│       ├── LyricOverlayService.kt # 桌面歌词悬浮窗服务 + DesktopLyricBus
│       └── PlaybackService.kt     # 前台播放服务（通知控制/MediaSession/锁屏线控）
├── .github/workflows/build.yml    # GitHub Actions 手动构建（workflow_dispatch）
├── build.gradle.kts / settings.gradle.kts / gradle.properties
└── gradle/wrapper/                # Gradle 8.7 wrapper
```

## 版权说明

- 本项目基于 [AnWind](https://github.com/afeimod/AnWind)（MIT License）移植
- 在线音源与歌词来自酷我/网易云/QQ 音乐/LRCLIB 公开接口，仅供个人学习研究，请支持正版
