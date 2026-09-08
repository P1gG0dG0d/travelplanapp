package com.haoqi.travel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 浅色单主题（UI 美化阶段①定稿）：
 *  - 不做深色模式（结构留好，以后要加再放开）；
 *  - 关闭动态取色，锁定品牌色板，保证全 App 设计语言统一。
 */
private val LightColors = lightColorScheme(
    primary = SkyBlue,
    onPrimary = OnSkyBlue,
    primaryContainer = SkyBlueContainer,
    onPrimaryContainer = OnSkyBlueContainer,
    secondary = Coral,
    onSecondary = OnCoral,
    secondaryContainer = CoralContainer,
    onSecondaryContainer = OnCoralContainer,
    tertiary = Coral,
    onTertiary = OnCoral,
    tertiaryContainer = CoralContainer,
    onTertiaryContainer = OnCoralContainer,
    error = DangerRed,
    background = WarmPaper,
    onBackground = Ink,
    surface = PaperWhite,
    onSurface = Ink,
    surfaceVariant = WarmPaper,
    onSurfaceVariant = InkSecondary,
    surfaceContainerLowest = PaperWhite,
    surfaceContainerLow = PaperWhite,
    surfaceContainer = PaperWhite,
    surfaceContainerHigh = PaperGray,
    surfaceContainerHighest = PaperGrayDeep,
    outline = Hairline,
    outlineVariant = Hairline,
)

@Composable
fun HaoQiTravelTheme(
    // 参数保留但暂不启用：深色模式本期不做，动态取色永久关闭
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography,
        content = content,
    )
}
