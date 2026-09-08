package com.haoqi.travel.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haoqi.travel.data.repository.TravelRepository
import com.haoqi.travel.ui.profile.AiPlanScreen
import com.haoqi.travel.ui.screens.ItineraryScreen
import com.haoqi.travel.ui.screens.MapScreen
import com.haoqi.travel.ui.screens.MoreScreen
import com.haoqi.travel.ui.screens.TicketsScreen
import com.haoqi.travel.ui.trips.TripViewModel

enum class AppTab(val label: String, val icon: ImageVector) {
    MAP("地图", Icons.Filled.Map),
    ITINERARY("行程", Icons.Filled.DateRange),
    TICKETS("车票", Icons.Filled.ConfirmationNumber),
    PLAN("AI 规划", Icons.Filled.AutoAwesome),
    MORE("更多", Icons.Filled.MoreHoriz),
}

@Composable
fun HaoQiTravelApp(repository: TravelRepository) {
    val tripViewModel: TripViewModel = viewModel(factory = TripViewModel.Factory(repository))
    var selected by remember { mutableStateOf(AppTab.MAP) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
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

            when (selected) {
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

@Composable
private fun TabOverlay(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        content()
    }
}
