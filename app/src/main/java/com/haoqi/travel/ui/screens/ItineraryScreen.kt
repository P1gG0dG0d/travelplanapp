package com.haoqi.travel.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import com.haoqi.travel.ui.components.GroupCard
import com.haoqi.travel.ui.components.ScreenTitle
import com.haoqi.travel.ui.components.SectionLabel
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
                if (ok) "已定位「${p.name}」" else "没搜到「${p.name}」，换个更具体的名字试试",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("行程")
        Text(
            trip.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 0.dp, bottom = 4.dp),
        )

        StatsCard(trip, places)

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
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val dayItems = plan.filter { it.dayIndex == selectedDay }
            if (dayItems.isEmpty()) {
                item(key = "empty-day") {
                    Text(
                        "这一天还没有安排",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .padding(top = 24.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            for (slot in PlanSlot.entries) {
                val slotItems = dayItems.filter { it.slot == slot }.sortedBy { it.orderIndex }
                if (slotItems.isEmpty()) continue
                item(key = "header-$slot") {
                    SectionLabel(planSlotLabel(slot), modifier = Modifier.animateItem())
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
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            if (hotels.isNotEmpty()) {
                item(key = "header-hotel") {
                    SectionLabel("酒店", modifier = Modifier.animateItem().padding(top = 8.dp))
                }
                items(hotels, key = { "hotel-${it.id}" }) { h ->
                    PlaceRow(
                        h,
                        action = null,
                        onLocate = { locate(h) },
                        locating = h.id == locatingId,
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            if (unplanned.isNotEmpty()) {
                item(key = "header-unplanned") {
                    SectionLabel("未安排", modifier = Modifier.animateItem().padding(top = 8.dp))
                }
                items(unplanned, key = { "unplanned-${it.id}" }) { p ->
                    PlaceRow(
                        p,
                        action = { adding = p },
                        onLocate = { locate(p) },
                        locating = p.id == locatingId,
                        modifier = Modifier.animateItem(),
                    )
                }
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

    GroupCard {
        Row(
            Modifier.fillMaxWidth(),
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
        Text(value, style = MaterialTheme.typography.titleLarge)
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
    modifier: Modifier = Modifier,
) {
    GroupCard(modifier = modifier) {
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
        HorizontalDivider(
            Modifier.padding(vertical = 2.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onMove) { Text("移动", style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = onRemove) {
                Text("移除", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            NavigateButton(place, onLocate = onLocate, locating = locating)
        }
    }
}

@Composable
private fun PlaceRow(
    place: PlaceEntity,
    action: (() -> Unit)?,
    onLocate: () -> Unit,
    locating: Boolean,
    modifier: Modifier = Modifier,
) {
    GroupCard(modifier = modifier) {
        Row(
            Modifier.fillMaxWidth(),
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
                    TextButton(onClick = action) { Text("加入行程", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

/** 行程页里展示景点的预约/购票说明（需要预约的用珊瑚橙强调） */
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
        color = if (p.needReservation) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun NavigateButton(place: PlaceEntity?, onLocate: () -> Unit = {}, locating: Boolean = false) {
    val context = LocalContext.current
    val p = place ?: return
    if (p.latitude != null && p.longitude != null) {
        TextButton(onClick = { NavigationHelper.navigateTo(context, p.name, p.latitude!!, p.longitude!!) }) {
            Text("导航", style = MaterialTheme.typography.labelMedium)
        }
    } else {
        TextButton(onClick = onLocate, enabled = !locating) {
            Text(if (locating) "定位中…" else "定位", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
