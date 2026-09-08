package com.haoqi.travel.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun AmapMapView(
    onMapReady: (AMap) -> Unit,
    onMapLongPress: (LatLng) -> Unit,
    onMarkerTap: (String, LatLng) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnMapReady by rememberUpdatedState(onMapReady)
    val currentOnLongPress by rememberUpdatedState(onMapLongPress)
    val currentOnMarkerTap by rememberUpdatedState(onMarkerTap)

    val mapView = remember { MapView(context).apply { onCreate(null) } }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                mapView.onPause()
                mapView.onDestroy()
            } catch (_: Exception) {
                // 高德 MapView 销毁时偶尔抛异常（尤其是切页重新创建时），
                // 捕获后避免整页崩溃；本版本用 try/catch 兜底，后续优化为常驻方案。
            }
        }
    }

    LaunchedEffect(mapView) {
        // 高德通过 getMap() 同步获取地图对象；若尚未就绪则短暂重试
        val map = withTimeoutOrNull(8_000) {
            var m = mapView.getMap()
            while (m == null) {
                delay(100)
                m = mapView.getMap()
            }
            m
        }
        if (map != null) {
            map.setOnMapLongClickListener { latLng -> currentOnLongPress(latLng) }
            map.setOnMarkerClickListener { marker ->
                marker.position?.let { pos -> currentOnMarkerTap(marker.title ?: "", pos) }
                false
            }
            currentOnMapReady(map)
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
