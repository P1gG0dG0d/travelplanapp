package com.haoqi.travel.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset

/**
 * 全 App 统一动画参数（UI 美化阶段⑥定稿）：
 * 所有时长 / 缓动 / 回弹都从这里取，改一处全局生效，保证动画语言一致。
 */
object Motion {
    /** 淡入 / 内容切换（200ms，均衡缓动） */
    val fadeIn = tween<Float>(200, easing = FastOutSlowInEasing)

    /** 淡出（120ms，快出） */
    val fadeOut = tween<Float>(120, easing = FastOutSlowInEasing)

    /** 按压反馈（120ms） */
    val press = tween<Float>(120, easing = FastOutSlowInEasing)

    /** 气泡 / 卡片滑入的位移（220ms） */
    val slideEnter = tween<IntOffset>(220, easing = FastOutSlowInEasing)

    /** 滑出位移（120ms） */
    val slideExit = tween<IntOffset>(120, easing = FastOutSlowInEasing)

    /** 页面横滑进入（320ms，稍慢更从容） */
    val pageSlideIn = tween<Float>(320, easing = FastOutSlowInEasing)

    /** 页面横滑退出（240ms） */
    val pageSlideOut = tween<Float>(240, easing = FastOutSlowInEasing)

    /** 导航指示器胶囊滑动：与页面横滑同参（320ms 同缓动），保证同步移动 */
    val navSlide = tween<Float>(320, easing = FastOutSlowInEasing)

    /** 导航图标选中放大：弹性 */
    val navIcon: SpringSpec<Float> = spring(dampingRatio = 0.5f, stiffness = 600f)

    /** 颜色渐变过渡（180ms） */
    val color = tween<Color>(180, easing = FastOutSlowInEasing)
}
