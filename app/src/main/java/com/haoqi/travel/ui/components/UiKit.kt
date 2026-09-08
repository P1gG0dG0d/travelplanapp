package com.haoqi.travel.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.haoqi.travel.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 统一 UI 零件库（UI 美化阶段①建立）：
 * 所有页面共用这里的大标题 / 小节标签 / 分组白卡 / 主按钮 / 按压反馈，
 * 保证设计语言统一，不再各页各写各的。
 */

/** 页面大标题（iOS Large Title 风格） */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** 小节标签：页面上引导一节内容的小灰字 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * 分组白卡（iOS 设置页范式）：白底 + 16dp 圆角 + 发丝描边，不用阴影。
 * 页面底色是浅灰（background），卡片自然浮出。
 */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/**
 * 主按钮：全宽、52dp 高、14dp 圆角，按压缩放反馈。
 * highlight = true 时用珊瑚橙（只给「重要且少见」的操作用，如「满意，创建旅行」）。
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlight: Boolean = false,
) {
    val (interaction, pressModifier) = rememberPressScale()
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(14.dp),
        colors = if (highlight) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(pressModifier),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 按压缩放反馈：按住缩到 0.97，松手弹回。
 * 返回 (interactionSource, 缩放 modifier)——把 source 同时传给 clickable/Button 才会生效。
 */
@Composable
fun rememberPressScale(): Pair<MutableInteractionSource, Modifier> {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = Motion.press,
        label = "pressScale",
    )
    return source to Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 通用按压反馈占位（InteractionSource 版本，供自定义 clickable 场景使用） */
@Composable
fun rememberPressScaleFor(source: InteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = Motion.press,
        label = "pressScale",
    )
    return Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 按压缩放 + 点击二合一：点下去缩到 0.97，松手触发 onClick。
 * 给自绘的可点元素（导航项、可折叠卡片头等）用，代替裸 clickable。
 */
@Composable
fun Modifier.pressScaleClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = Motion.press,
        label = "pressScale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

/**
 * 键盘友好输入框：聚焦时（键盘开着）再点它一下会收起键盘、退出输入模式；
 * 其余行为与 OutlinedTextField 一致。全 App 所有可输入框统一用它。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyboardGuardTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            maxLines = maxLines,
            label = label,
            placeholder = placeholder,
            shape = shape,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoViewRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) {
                        // 键盘弹起动画约 250ms：等它把视口压短后再滚到可见；
                        // 分两次触发，确保最终到位
                        scope.launch {
                            delay(300)
                            bringIntoViewRequester.bringIntoView()
                            delay(400)
                            bringIntoViewRequester.bringIntoView()
                        }
                    }
                },
        )
        // 聚焦时盖一层透明区域：再点一下 = 收起键盘退出输入
        if (focused) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        kb?.hide()
                        focusManager.clearFocus()
                    },
            )
        }
    }
}

/**
 * 给可滚动容器：点空白处（非输入框区域）收起键盘，避免键盘遮挡内容。
 * 用 clickable 而不是 pointerInput：输入框自己会消费点击，不会误触“点字段反而收键盘”。
 */
@Composable
fun Modifier.tapOutsideToDismissKeyboard(): Modifier {
    val focusManager = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    return this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) {
        kb?.hide()
        focusManager.clearFocus()
    }
}
