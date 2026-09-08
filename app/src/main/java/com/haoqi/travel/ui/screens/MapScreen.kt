package com.haoqi.travel.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Text
import com.amap.api.maps.model.TextOptions
import com.haoqi.travel.data.remote.GeocodeHelper
import com.haoqi.travel.data.remote.NavigationHelper
import com.haoqi.travel.ui.components.ScreenTitle
import com.haoqi.travel.ui.map.AddPlaceDialog
import com.haoqi.travel.ui.theme.Motion
import com.haoqi.travel.ui.map.AmapMapView
import com.haoqi.travel.ui.trips.TripViewModel
import kotlinx.coroutines.launch

private data class AddPlaceState(val latLng: LatLng, val address: String, val city: String)

/**
 * 地图标注只显示干净的名称：把尾部括号里的备注剥掉
 * （AI 常把「(需提前预约/近地铁/建议游玩…)」塞进名称，地图上显示一大串很难看）。
 * 只影响地图展示，地点保存的完整名称不变。
 */
private fun mapLabel(name: String): String {
    var s = name.trim()
    while (true) {
        val m = Regex("""[（(][^（）()]*[)）]\s*$""").find(s) ?: return s
        s = s.removeRange(m.range).trim()
    }
}

@Composable
fun MapScreen(vm: TripViewModel, mapActive: Boolean) {
    val context = LocalContext.current
    val trips by vm.trips.collectAsStateWithLifecycle()
    val activeId by vm.activeTripId.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val activeTrip = trips.firstOrNull { it.id == activeId }

    var aMap by remember { mutableStateOf<AMap?>(null) }
    var addPlaceState by remember { mutableStateOf<AddPlaceState?>(null) }
    var navTarget by remember { mutableStateOf<Pair<String, LatLng>?>(null) }
    val scope = rememberCoroutineScope()

    // 切到其它标签、或当前没有旅行时禁用地图手势，避免触摸穿透；切回且已有旅行时恢复
    LaunchedEffect(aMap, mapActive, activeTrip != null) {
        aMap?.uiSettings?.setAllGesturesEnabled(mapActive && activeTrip != null)
        if (!mapActive) navTarget = null
    }

    LaunchedEffect(aMap, places) {
        aMap?.let { map ->
            map.clear()
            val located = places.filter { it.latitude != null && it.longitude != null }
            located.forEach { p ->
                val pos = LatLng(p.latitude!!, p.longitude!!)
                val label = mapLabel(p.name)
                map.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .title(label)
                        .snippet(p.address)
                )
                // 常驻显示名称标签，不用点开才看到
                map.addText(
                    TextOptions()
                        .position(pos)
                        .text(label)
                        .fontSize(36)
                        .fontColor(0xFF1F2937.toInt())
                        .backgroundColor(0xE6FFFFFF.toInt())
                        .align(Text.ALIGN_CENTER_HORIZONTAL, Text.ALIGN_BOTTOM)
                )
            }
            if (located.isNotEmpty()) {
                val first = located.first()
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(first.latitude!!, first.longitude!!), 13f)
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("好奇旅行")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(trips, key = { it.id }) { trip ->
                FilterChip(
                    selected = trip.id == activeId,
                    onClick = { vm.setActiveTrip(trip.id) },
                    label = { Text(trip.name) },
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // 地图常驻底层：即使没有任何旅行，也不销毁 MapView，
            // 避免高德地图销毁时触发原生崩溃；无旅行时用不透明遮罩盖住。
            AmapMapView(
                onMapReady = { aMap = it },
                onMapLongPress = { latLng ->
                    if (activeTrip != null) {
                        scope.launch {
                            val addr = try {
                                GeocodeHelper.reverseGeocode(context, latLng.latitude, latLng.longitude)
                            } catch (e: Exception) {
                                null
                            }
                            addPlaceState = AddPlaceState(
                                latLng = latLng,
                                address = addr?.formatted ?: "",
                                city = addr?.city ?: "",
                            )
                        }
                    }
                },
                onMarkerTap = { name, pos -> navTarget = name to pos },
                modifier = Modifier.fillMaxSize(),
            )

            if (activeTrip == null) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Placeholder("还没有旅行", "去「AI 规划」页生成第一个旅行")
                }
            }

            AnimatedContent(
                targetState = navTarget,
                transitionSpec = {
                    (fadeIn(Motion.fadeIn) + slideInVertically(Motion.slideEnter) { it })
                        .togetherWith(fadeOut(Motion.fadeOut) + slideOutVertically(Motion.slideExit) { it })
                },
                label = "navCard",
                modifier = Modifier.align(Alignment.BottomCenter),
            ) { target ->
                target?.let { (name, pos) ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        shadowElevation = 2.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { NavigationHelper.navigateTo(context, name, pos.latitude, pos.longitude) }) {
                                Text("导航")
                            }
                            IconButton(onClick = { navTarget = null }) {
                                Icon(Icons.Filled.Close, contentDescription = "关闭")
                            }
                        }
                    }
                }
            }
        }
    }

    addPlaceState?.let { state ->
        AddPlaceDialog(
            initialAddress = state.address,
            onDismiss = { addPlaceState = null },
            onConfirm = { name, type, address, note, needReservation, reservationDate, bookingInfo ->
                vm.addPlace(
                    context.applicationContext,
                    name,
                    type,
                    state.city,
                    address,
                    state.latLng.latitude,
                    state.latLng.longitude,
                    note,
                    needReservation,
                    reservationDate,
                    bookingInfo,
                )
                addPlaceState = null
            },
        )
    }
}
