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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Android TV 兼容层（TV 版改造新增）：
 *
 * - [TvCompat.isTv]：设备 TV 模式检测（UiModeManager.currentModeType == TELEVISION），
 *   MainActivity 启动时初始化一次，进程内不变；
 * - [TvCompat.initialSearchFocused]：TV 首次启动自动聚焦搜索框的一次性标记；
 * - [Modifier.tvFocus]：TV 遥控焦点高亮 —— 元素自身或其子孙获得焦点时，
 *   叠加品牌红描边 + 轻微红色底衬（可选轻微放大），手机/平板上完全无操作。
 *
 * 遥控适配原理：Compose 的 clickable/focusable/Switch/TextField 均已内置
 * 键盘焦点目标，D-pad 上下左右自动几何寻焦、确认键（DPAD_CENTER/ENTER）触发点击、
 * 返回键走 BackHandler —— 本文件负责把「焦点在哪」
 * 以清晰可视的方式呈现出来，并处理初始焦点与焦点归还。
 *
 * v1.4 新增两个专用修饰符（修复电视实测反馈）：
 * - [Modifier.tvSliderSteps]：滑条聚焦后 D-pad 左右键按刻度步进增减
 *   （M3 Slider 在部分电视固件上不响应左右键，焦点会跑掉）；
 * - [Modifier.tvTextFieldNav]：输入框聚焦时 D-pad 上下键移动焦点离开
 *   （单行输入框会吞上下键光标事件，导致遥控卡死在搜索框里）。
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

/**
 * TV 滑条键盘步进（v1.4）：聚焦后拦截 D-pad 左右键按刻度增减值。
 *
 * 修复：部分电视固件上 M3 Slider 虽可聚焦但左右键被焦点系统拿去做寻焦，
 * 值不变、焦点跑丢（设置页全部滑条调不了的根因）。onPreviewKeyEvent
 * 在隧道阶段拦截并消费事件，保证左右键 100% 步进。
 *
 * 步长：steps>0 时按 M3 刻度（steps+1 段）对齐刻度；steps=0 连续滑条按
 * 全程 1/50 步进。非 TV 设备原样返回，不影响触摸拖动。
 *
 * @param value 当前值（调用方与 Slider 同源）
 * @param onValue 新值回调（已按刻度对齐并夹在区间内）
 */
fun Modifier.tvSliderSteps(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValue: (Float) -> Unit
): Modifier = composed {
    if (!TvCompat.isTv) return@composed this
    this.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val delta = (range.endInclusive - range.start) /
            (if (steps > 0) (steps + 1) * 1f else 50f)
        when (e.key) {
            Key.DirectionLeft -> {
                val raw = value - delta
                val snapped = if (steps > 0) {
                    val k = ((raw - range.start) / delta).roundToInt().coerceIn(0, steps + 1)
                    range.start + k * delta
                } else raw
                onValue(snapped.coerceIn(range.start, range.endInclusive))
                true
            }
            Key.DirectionRight -> {
                val raw = value + delta
                val snapped = if (steps > 0) {
                    val k = ((raw - range.start) / delta).roundToInt().coerceIn(0, steps + 1)
                    range.start + k * delta
                } else raw
                onValue(snapped.coerceIn(range.start, range.endInclusive))
                true
            }
            else -> false
        }
    }
}

/**
 * TV 输入框焦点逃逸（v1.4）：聚焦时 D-pad 上下键移出输入框。
 *
 * 修复：单行 TextField 会吞上下键（光标移动事件），遥控器在搜索框/手动
 * 输入框里按上下键无反应，永远出不去（“搜索后无法再点选音源芯片”的
 * 根因之一）。左右键保留给光标移动不动。
 */
fun Modifier.tvTextFieldNav(): Modifier = composed {
    if (!TvCompat.isTv) return@composed this
    val focusManager = LocalFocusManager.current
    this.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
            else -> return@onPreviewKeyEvent false
        }
        true
    }
}
