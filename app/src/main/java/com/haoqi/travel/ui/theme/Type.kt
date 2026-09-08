package com.haoqi.travel.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.haoqi.travel.R

/**
 * 品牌字体：Inter（拉丁字母 + 数字）+ 思源黑体 Noto Sans SC（中文），
 * 都是可变字体，按字重配好 variation，保证粗体/中粗也正确渲染。
 * 拉丁字符优先 Inter，中文回退到 Noto Sans SC。
 */
@OptIn(ExperimentalTextApi::class)
val AppFontFamily = FontFamily(
    Font(R.font.inter, weight = FontWeight.Normal, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter, weight = FontWeight.Medium, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter, weight = FontWeight.SemiBold, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter, weight = FontWeight.Bold, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.noto_sans_sc, weight = FontWeight.Normal, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.noto_sans_sc, weight = FontWeight.Medium, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.noto_sans_sc, weight = FontWeight.SemiBold, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.noto_sans_sc, weight = FontWeight.Bold, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/**
 * 字号体系（iOS 风）：标题明显大于正文、层级靠字重和灰度区分，正文 16sp 起步。
 * 全 App 统一走这套，禁止页面自造字号。
 */
val Typography = Typography(
    // 页面大标题（Large Title）
    headlineLarge = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    // 卡片/分区标题
    titleLarge = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    // 正文
    bodyLarge = TextStyle(fontFamily = AppFontFamily, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = AppFontFamily, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = AppFontFamily, fontSize = 12.sp, lineHeight = 16.sp),
    // 按钮/标签
    labelLarge = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)
