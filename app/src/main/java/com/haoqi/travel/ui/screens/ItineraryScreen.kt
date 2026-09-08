package com.haoqi.travel.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haoqi.travel.data.importer.placeTypeLabel
import com.haoqi.travel.data.importer.planSlotLabel
import com.haoqi.travel.data.remote.NavigationHelper
import com.haoqi.travel.data.local.entity.PlaceEntity
import com.haoqi.travel.data.local.entity.PlaceType
import com.haoqi.travel.data.local.entity.PlanItemEntity
import com.haoqi.travel.data.local.entity.PlanSlot
import com.haoqi.travel.data.local.entity.TripEntity
import com.haoqi.travel.ui.trips.TripViewModel

@Composable
fun ItineraryScreen(vm: TripViewModel) {
    val activeTrip by vm.activeTrip.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()

    if (activeTrip == null) {
        Placeholder("还没有旅行", "去「AI 规划」页生成第一个旅行")
        return
    }
    val trip = activeTrip!!
    val context = LocalContext.current
    val placesById = remember(places) { places.associateBy { it.id } }
    val hotels = places.filter { it.type == PlaceType.HOTEL }
    val plannedIds = plan.map { it.placeId }.toSet()
    val unplanned = places.filter { it.id !in plannedIds && it.type != PlaceType.HOTEL }

    val dayCount = trip.days.coerceAtLeast(1)
    var selectedDay by remember { mutableStateOf(1) }
    if (selectedDay > dayCount) selectedDay = dayCount

    var moving by remember { mutableStateOf<PlanItemEntity?>(null) }
    var adding by remember { mutableStateOf<PlaceEntity?>(null) }
    var locatingId by remember { mutableStateOf<Long?>(null) }

    // 手动给没坐标的地点重新搜索定位
    val locate: (PlaceEntity) -> Unit = { p ->
        locatingId = p.id
        vm.locatePlace(context.applicationContext, p.id) { ok ->
            locatingId = null
            Toast.makeText(
                context,
                if (ok) "已定位「${p.name}」，现在可以导航了" else "没搜到「${p.name}」，试试换个更具体的名字",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "行程 · ${trip.name}",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        )

        StatsCard(trip, places)

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { vm.autoSchedule() }) { Text("一键排程") }
            Text(
                "上午/午餐/下午/晚餐/晚上 · 每天 2–3 个景点",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items((1..dayCount).toList()) { day ->
                FilterChip(
                    selected = day == selectedDay,
                    onClick = { selectedDay = day },
                    label = { Text("第 $day 天") },
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val dayItems = plan.filter { it.dayIndex == selectedDay }
            for (slot in PlanSlot.entries) {
                val slotItems = dayItems.filter { it.slot == slot }.sortedBy { it.orderIndex }
                item(key = "header-$slot") {
                    Text(
                        planSlotLabel(slot),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                itemsIndexed(slotItems, key = { _, pi -> "plan-${pi.placeId}" }) { index, pi ->
                    PlanItemCard(
                        place = placesById[pi.placeId],
                        canUp = index > 0,
                        canDown = index < slotItems.size - 1,
                        onUp = { vm.shiftPlace(pi.placeId, -1) },
                        onDown = { vm.shiftPlace(pi.placeId, 1) },
                        onMove = { moving = pi },
                        onRemove = { vm.removeFromPlan(pi.placeId) },
                        onLocate = { placesById[pi.placeId]?.let(locate) },
                        locating = placesById[pi.placeId]?.id == locatingId,
                    )
                }
            }

            item(key = "header-hotel") {
                Text(
                    "酒店",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            items(hotels, key = { "hotel-${it.id}" }) { h ->
                PlaceRow(h, action = null, onLocate = { locate(h) }, locating = h.id == locatingId)
            }

            item(key = "header-unplanned") {
                Text(
                    "未安排",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            items(unplanned, key = { "unplanned-${it.id}" }) { p ->
                PlaceRow(p, action = { adding = p }, onLocate = { locate(p) }, locating = p.id == locatingId)
            }
        }
    }

    moving?.let { pi ->
        PlanPickerDialog(
            title = "移动「${placesById[pi.placeId]?.name ?: ""}」",
            dayCount = dayCount,
            initialDay = pi.dayIndex,
            initialSlot = pi.slot,
            onDismiss = { moving = null },
            onConfirm = { day, slot ->
                vm.movePlace(pi.placeId, day, slot)
                moving = null
            },
        )
    }

    adding?.let { p ->
        PlanPickerDialog(
            title = "把「${p.name}」加入行程",
            dayCount = dayCount,
            initialDay = selectedDay,
            initialSlot = PlanSlot.MORNING,
            onDismiss = { adding = null },
            onConfirm = { day, slot ->
                vm.addPlaceToPlan(p.id, day, slot)
                adding = null
            },
        )
    }

}

@Composable
private fun StatsCard(trip: TripEntity, places: List<PlaceEntity>) {
    val cities = places.map { it.city }.filter { it.isNotBlank() }.distinct().size
    val att = places.count { it.type == PlaceType.ATTRACTION }
    val res = places.count { it.type == PlaceType.RESTAURANT }
    val hotel = places.count { it.type == PlaceType.HOTEL }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Stat("天数", trip.days.toString())
            Stat("城市", cities.toString())
            Stat("景点", att.toString())
            Stat("饭店", res.toString())
            Stat("酒店", hotel.toString())
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlanItemCard(
    place: PlaceEntity?,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
    onLocate: () -> Unit,
    locating: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(place?.name ?: "?", style = MaterialTheme.typography.titleMedium)
                    val sub = place?.let { p ->
                        listOfNotNull(
                            placeTypeLabel(p.type),
                            p.address.takeIf { it.isNotBlank() },
                            p.durationMinutes?.let { fmtDuration(it) },
                        ).joinToString(" · ")
                    }.orEmpty()
                    if (sub.isNotBlank()) {
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BookingInfo(place)
                }
                IconButton(onClick = onUp, enabled = canUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
                }
                IconButton(onClick = onDown, enabled = canDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onMove) { Text("移动") }
                TextButton(onClick = onRemove) { Text("移除", color = MaterialTheme.colorScheme.error) }
                NavigateButton(place, onLocate = onLocate, locating = locating)
            }
        }
    }
}

@Composable
private fun PlaceRow(place: PlaceEntity, action: (() -> Unit)?, onLocate: () -> Unit, locating: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(place.name, style = MaterialTheme.typography.titleMedium)
                val sub = listOfNotNull(
                    placeTypeLabel(place.type),
                    place.address.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BookingInfo(place)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NavigateButton(place, onLocate = onLocate, locating = locating)
                if (action != null) {
                    TextButton(onClick = action) { Text("加入行程") }
                }
            }
        }
    }
}

/** 行程页里展示景点的预约/购票说明（需要预约的用强调色标出） */
@Composable
private fun BookingInfo(place: PlaceEntity?) {
    val p = place ?: return
    if (p.type != PlaceType.ATTRACTION) return
    val parts = mutableListOf<String>()
    if (p.needReservation) parts.add("需预约/购票")
    p.reservationDate?.takeIf { it.isNotBlank() }?.let { parts.add("日期 $it") }
    p.bookingInfo.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" ｜ "),
        style = MaterialTheme.typography.bodySmall,
        color = if (p.needReservation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun NavigateButton(place: PlaceEntity?, onLocate: () -> Unit = {}, locating: Boolean = false) {
    val context = LocalContext.current
    val p = place ?: return
    if (p.latitude != null && p.longitude != null) {
        TextButton(onClick = { NavigationHelper.navigateTo(context, p.name, p.latitude!!, p.longitude!!) }) {
            Text("导航")
        }
    } else {
        TextButton(onClick = onLocate, enabled = !locating) {
            Text(if (locating) "定位中…" else "定位")
        }
    }
}

@Composable
private fun PlanPickerDialog(
    title: String,
    dayCount: Int,
    initialDay: Int,
    initialSlot: PlanSlot,
    onDismiss: () -> Unit,
    onConfirm: (day: Int, slot: PlanSlot) -> Unit,
) {
    var day by remember { mutableStateOf(initialDay) }
    var slot by remember { mutableStateOf(initialSlot) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("第几天", style = MaterialTheme.typography.bodyMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((1..dayCount).toList()) { d ->
                        FilterChip(selected = d == day, onClick = { day = d }, label = { Text("第 $d 天") })
                    }
                }
                Text("时段", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlanSlot.entries.forEach { s ->
                        FilterChip(selected = s == slot, onClick = { slot = s }, label = { Text(planSlotLabel(s)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(day, slot) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun fmtDuration(min: Int): String = when {
    min % 60 == 0 -> "${min / 60}小时"
    min < 60 -> "${min}分钟"
    else -> "${min / 60}小时${min % 60}分钟"
}
