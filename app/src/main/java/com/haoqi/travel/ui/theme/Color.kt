package com.haoqi.travel.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 「好奇旅行」固定色板 —— Apple 风 · 年轻化
 *
 * 原则（UI 美化阶段①定稿）：
 *  - 一个主色贯穿全 App（天青蓝），一个强调色只给「重要且少见」的操作（珊瑚橙）；
 *  - 页面浅灰底 + 白色分组卡片（iOS 设置页范式）；
 *  - 锁定固定色值，不使用 Android 12 动态取色（那会让颜色跟壁纸走，失去统一感）。
 */

// ---- 主色：天青（雨过天青，蓝中带青绿，年轻清爽）----
val SkyBlue = Color(0xFF00A6C8)
val OnSkyBlue = Color(0xFFFFFFFF)
val SkyBlueContainer = Color(0xFFDDF5FB)
val OnSkyBlueContainer = Color(0xFF085E70)

// ---- 强调色：珊瑚橙（重要/少见 的操作与提醒专用）----
val Coral = Color(0xFFFF6B4A)
val OnCoral = Color(0xFFFFFFFF)
val CoralContainer = Color(0xFFFFE9E3)
val OnCoralContainer = Color(0xFF9E3B24)

// ---- 中性色（iOS 分组风格 · 暖白底）----
val Ink = Color(0xFF1C1C1E) // 近黑正文
val InkSecondary = Color(0xFF8E8E93) // 次要文字
val PaperWhite = Color(0xFFFFFFFF) // 卡片
val WarmPaper = Color(0xFFFAF9F7) // 页面暖白底（米白纸感）
val Hairline = Color(0xFFE9E6E1) // 发丝分隔线/描边（暖调）
val PaperGray = Color(0xFFFAF8F5) // 略暖的白色（高层级表面）
val PaperGrayDeep = Color(0xFFF1EEE9) // 最高层级表面（暖调）

// ---- 语义色 ----
val DangerRed = Color(0xFFFF3B30) // iOS 红
val SuccessGreen = Color(0xFF34C759) // iOS 绿

// 旧色板（teal）已整体下线，替换为上面的固定色板
