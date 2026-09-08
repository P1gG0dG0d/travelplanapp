package com.haoqi.travel.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haoqi.travel.data.repository.TravelRepository
import com.haoqi.travel.ui.components.pressScaleClickable
import com.haoqi.travel.ui.profile.AiPlanScreen
import com.haoqi.travel.ui.screens.ItineraryScreen
import com.haoqi.travel.ui.screens.MapScreen
import com.haoqi.travel.ui.screens.MoreScreen
import com.haoqi.travel.ui.screens.TicketsScreen
import com.haoqi.travel.ui.theme.Motion
import com.haoqi.travel.ui.trips.TripViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 底部导航：5 个一级入口。图标未选中线性、选中实心，选中时有颜色渐变 + 弹性放大。 */
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

    // 手写双图层横滑：current 是垫在底下的「原页」，pending 是从旁边滑进来的「新页」。
    var current by remember { mutableStateOf(AppTab.MAP) }
    var pending by remember { mutableStateOf<AppTab?>(null) }
    val curOffset = remember { Animatable(0f) }
    val pendOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    fun switchTo(next: AppTab) {
        if (next == current || pending != null) return
        val forward = next.ordinal > current.ordinal
        scope.launch {
            if (next == AppTab.MAP) {
                // 去地图：原页不透明滑走，露出底下的地图
                curOffset.snapTo(0f)
                curOffset.animateTo(if (forward) -1f else 1f, Motion.pageSlideOut)
                current = AppTab.MAP
                curOffset.snapTo(0f)
            } else {
                // 去别的页：新页从对应方向滑入，盖在原页（或地图）之上
                pendOffset.snapTo(if (forward) 1f else -1f)
                pending = next
                pendOffset.animateTo(0f, Motion.pageSlideIn)
                current = next
                pending = null
                pendOffset.snapTo(0f)
            }
        }
    }

    Scaffold(
        bottomBar = { SlidingNavBar(selected = current, onSelect = ::switchTo) }
    ) { innerPadding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(innerPadding)) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }

            Box(Modifier.fillMaxSize()) {
                // 地图常驻底层：高德 MapView 销毁再重建会原生崩溃，所以永不销毁
                MapScreen(tripViewModel, mapActive = current == AppTab.MAP)

                // 原页（垫底，不透明）
                Box(
                    Modifier
                        .fillMaxSize()
                        .offset { IntOffset((curOffset.value * widthPx).roundToInt(), 0) },
                ) {
                    TabContent(current, tripViewModel, onGoToItinerary = { switchTo(AppTab.ITINERARY) })
                }

                // 滑入中的新页（叠在原页上面）
                pending?.let { tab ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .offset { IntOffset((pendOffset.value * widthPx).roundToInt(), 0) },
                    ) {
                        TabContent(tab, tripViewModel, onGoToItinerary = { switchTo(AppTab.ITINERARY) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TabContent(tab: AppTab, vm: TripViewModel, onGoToItinerary: () -> Unit) {
    when (tab) {
        AppTab.MAP -> Box(Modifier.fillMaxSize()) // 透明占位，露出底下的地图
        AppTab.ITINERARY -> TabOverlay { ItineraryScreen(vm) }
        AppTab.TICKETS -> TabOverlay { TicketsScreen(vm) }
        AppTab.PLAN -> TabOverlay { AiPlanScreen(vm, onGoToItinerary) }
        AppTab.MORE -> TabOverlay { MoreScreen(vm) }
    }
}

/** 自定义底部导航：浅青胶囊指示器在 tab 间平滑滑动，图标选中放大 + 颜色渐变 */
@Composable
private fun SlidingNavBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    val tabs = AppTab.entries
    val density = LocalDensity.current
    var barWidth by remember { mutableIntStateOf(0) }
    val tabWidthPx = if (barWidth > 0) barWidth / tabs.size else 0
    val x by animateFloatAsState(
        targetValue = (tabWidthPx * selected.ordinal).toFloat(),
        animationSpec = Motion.navSlide,
        label = "navIndicatorX",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .onSizeChanged { barWidth = it.width },
        ) {
            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt(), 0) }
                    .fillMaxHeight()
                    .width(with(density) { tabWidthPx.toDp() })
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                )
            }

            Row(Modifier.fillMaxSize()) {
                tabs.forEach { tab ->
                    val sel = tab == selected
                    val iconScale by animateFloatAsState(
                        targetValue = if (sel) 1.12f else 1f,
                        animationSpec = Motion.navIcon,
                        label = "navIconScale",
                    )
                    val tint by animateColorAsState(
                        targetValue = if (sel) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = Motion.color,
                        label = "navTint",
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pressScaleClickable { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            if (sel) tab.activeIcon else tab.icon,
                            contentDescription = tab.label,
                            tint = tint,
                            modifier = Modifier.graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            },
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(tab.label, color = tint, style = MaterialTheme.typography.labelMedium)
                    }
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
