package com.haoqi.travel.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haoqi.travel.data.repository.TravelRepository
import com.haoqi.travel.ui.profile.AiPlanScreen
import com.haoqi.travel.ui.screens.ItineraryScreen
import com.haoqi.travel.ui.screens.MapScreen
import com.haoqi.travel.ui.screens.MoreScreen
import com.haoqi.travel.ui.screens.TicketsScreen
import com.haoqi.travel.ui.trips.TripViewModel

/**
 * 底部导航：5 个一级入口保持不变。
 * 图标：未选中用线性（Outlined）、选中用实心（Filled），选中瞬间有弹性缩放。
 */
enum class AppTab(val label: String, val icon: ImageVector, val activeIcon: ImageVector) {
    MAP("地图", Icons.Outlined.Map, Icons.Filled.Map),
    ITINERARY("行程", Icons.Outlined.DateRange, Icons.Filled.DateRange),
    TICKETS("车票", Icons.Outlined.ConfirmationNumber, Icons.Filled.ConfirmationNumber),
    PLAN("AI 规划", Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
    MORE("更多", Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
}

@Composable
fun HaoQiTravelApp(repository: TravelRepository) {
    val tripViewModel: TripViewModel = viewModel(factory = TripViewModel.Factory(repository))
    var selected by remember { mutableStateOf(AppTab.MAP) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                AppTab.entries.forEach { tab ->
                    val selectedNow = selected == tab
                    val iconScale by animateFloatAsState(
                        targetValue = if (selectedNow) 1.12f else 1f,
                        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
                        label = "tabIconScale",
                    )
                    NavigationBarItem(
                        selected = selectedNow,
                        onClick = { selected = tab },
                        icon = {
                            Icon(
                                if (selectedNow) tab.activeIcon else tab.icon,
                                contentDescription = tab.label,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                },
                            )
                        },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            // 地图常驻在底层：高德 MapView 一旦销毁再重建，在部分机型上会触发原生崩溃。
            // 因此切到其它标签时不再移除地图，而是用不透明的覆盖层盖住它，避免反复销毁/重建。
            // 覆盖层不加 pointerInput 拦截，避免拖慢其它标签页的滚动；改为在地图层禁用手势。
            MapScreen(tripViewModel, mapActive = selected == AppTab.MAP)

            // 页面切换：淡入 + 轻微上滑 + 微缩放，进 220ms / 出 120ms，快而不闹
            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    (fadeIn(tween(220)) +
                        slideInVertically(tween(220)) { it / 24 } +
                        scaleIn(initialScale = 0.985f, animationSpec = tween(220)))
                        .togetherWith(fadeOut(tween(120)))
                },
                label = "tabContent",
            ) { tab ->
                when (tab) {
                    AppTab.MAP -> Unit
                    AppTab.ITINERARY -> TabOverlay { ItineraryScreen(tripViewModel) }
                    AppTab.TICKETS -> TabOverlay { TicketsScreen(tripViewModel) }
                    AppTab.PLAN -> TabOverlay {
                        AiPlanScreen(tripViewModel, onGoToItinerary = { selected = AppTab.ITINERARY })
                    }
                    AppTab.MORE -> TabOverlay { MoreScreen(tripViewModel) }
                }
            }
        }
    }
}

@Composable
private fun TabOverlay(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        content()
    }
}
