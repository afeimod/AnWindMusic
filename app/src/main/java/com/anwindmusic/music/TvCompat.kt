package com.anwindmusic.music

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Android TV 兼容层（TV 版改造新增）：
 *
 * - [TvCompat.isTv]：设备 TV 模式检测（UiModeManager.currentModeType == TELEVISION），
 *   MainActivity 启动时初始化一次，进程内不变；
 * - [TvCompat.initialSearchFocused]：TV 首次启动自动聚焦搜索框的一次性标记；
 * - [Modifier.tvFocus]：TV 遥控焦点高亮 —— 元素自身或其子孙获得焦点时，
 *   叠加品牌红描边 + 轻微红色底衬（可选轻微放大），手机/平板上完全无操作。
 *
 * 遥控适配原理：Compose 的 clickable/focusable/Slider/Switch/TextField 均已内置
 * 键盘焦点目标，D-pad 上下左右自动几何寻焦、确认键（DPAD_CENTER/ENTER）触发点击、
 * 滑条聚焦后左右键增减、返回键走 BackHandler —— 本文件负责把「焦点在哪」
 * 以清晰可视的方式呈现出来，并处理初始焦点与焦点归还。
 */
object TvCompat {

    /** 设备是否为 Android TV（MainActivity.onCreate 中初始化） */
    @Volatile
    var isTv: Boolean = false
        private set

    /** TV 首次组合时是否已自动聚焦过搜索框（避免每次切回搜索页都抢焦点） */
    @Volatile
    var initialSearchFocused: Boolean = false

    /** 按 UI 模式检测 TV 设备；无法判定时回退看 leanback/television 特性 */
    fun init(context: Context) {
        isTv = runCatching {
            val uiModeManager =
                context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            val mode = uiModeManager?.currentModeType
                ?: context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
            when (mode) {
                Configuration.UI_MODE_TYPE_TELEVISION -> true
                else -> context.packageManager.hasSystemFeature("android.software.leanback")
            }
        }.getOrDefault(false)
    }
}

/**
 * TV 遥控焦点高亮修饰符：
 * - 元素自身（clickable/focusable）或其子孙（M3 组件内部的焦点目标）获得焦点时，
 *   显示 2dp 品牌红描边 + 10% 红色底衬；[scaleUp] 为 true 时再轻微放大 1.08 倍
 *   （适合图标按钮等小元素，10 英尺外也看得清）；
 * - 非电视设备（isTv = false）返回原修饰符，对手机/平板布局零影响。
 *
 * 使用方式：放在 .clickable() 之前（顺序不影响，onFocusChanged 观察整个布局节点），
 * 例如 Box(Modifier.clip(shape).tvFocus(shape).clickable { ... })。
 *
 * @param shape 描边圆角形状（与元素自身裁剪形状保持一致）
 * @param accent 描边颜色（默认品牌红；红底按钮等场景可换白色）
 * @param scaleUp 聚焦时是否轻微放大（图标按钮建议开启）
 */
fun Modifier.tvFocus(
    shape: Shape = RoundedCornerShape(8.dp),
    accent: Color = Mc.red,
    scaleUp: Boolean = false
): Modifier = composed {
    val isTv = TvCompat.isTv
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && scaleUp) 1.08f else 1f,
        label = "tvFocusScale"
    )
    this
        .onFocusChanged { state -> focused = state.isFocused || state.hasFocus }
        .then(
            if (isTv && focused) {
                Modifier
                    .graphicsLayer {
                        if (scaleUp) {
                            scaleX = scale
                            scaleY = scale
                        }
                    }
                    .background(accent.copy(alpha = 0.10f), shape)
                    .border(2.dp, accent, shape)
            } else {
                Modifier
            }
        )
}
