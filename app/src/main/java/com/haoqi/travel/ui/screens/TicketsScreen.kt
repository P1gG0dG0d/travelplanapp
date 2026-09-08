package com.haoqi.travel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.haoqi.travel.data.local.entity.TicketEntity
import com.haoqi.travel.data.local.entity.defaultRemindMinutes
import com.haoqi.travel.ui.tickets.AddTicketDialog
import com.haoqi.travel.ui.tickets.ticketTypeLabel
import com.haoqi.travel.ui.trips.TripViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TicketsScreen(vm: TripViewModel) {
    val context = LocalContext.current
    val activeTrip by vm.activeTrip.collectAsStateWithLifecycle()
    val tickets by vm.tickets.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TicketEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        Text(
            "车票",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        )

        if (activeTrip == null) {
            Placeholder("还没有旅行", "去「AI 规划」页生成第一个旅行")
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = { showAdd = true }) { Text("添加车票") }
        }

        if (tickets.isEmpty()) {
            Text(
                "还没有车票。添加后会按出发时间自动提醒（高铁/火车提前 45 分钟，航班 2 小时，大巴 30 分钟）。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            if (tickets.any { it.isSuggestion }) {
                Text(
                    "标着「交通建议」的是 AI 给的出行方案——它不会替你编车次和发车时间。" +
                        "自己去 12306 或航司 App 买到票后，点卡片上的「填写真实车票」补上，提醒才会生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tickets, key = { it.id }) { ticket ->
                    TicketCard(
                        ticket = ticket,
                        onEdit = { editing = ticket },
                        onDelete = { vm.deleteTicket(context.applicationContext, ticket) },
                    )
                }
            }
        }
    }

    if (showAdd && activeTrip != null) {
        AddTicketDialog(
            initialDate = activeTrip!!.startDate,
            onDismiss = { showAdd = false },
            onConfirm = { type, no, from, to, dep, seat ->
                vm.addTicket(context.applicationContext, type, no, from, to, dep, seat)
                showAdd = false
            },
        )
    }

    editing?.let { target ->
        AddTicketDialog(
            initialDate = activeTrip?.startDate.orEmpty(),
            initial = target,
            onDismiss = { editing = null },
            onConfirm = { type, no, from, to, dep, seat ->
                vm.saveTicket(
                    context.applicationContext,
                    target.copy(
                        type = type,
                        trainNo = no,
                        fromStation = from,
                        toStation = to,
                        departureTime = dep,
                        seat = seat,
                        // 落实成真实车票：清掉建议标记和旧的估算说明，提醒按新方式重排
                        isSuggestion = false,
                        note = "",
                        remindMinutesBefore = defaultRemindMinutes(type),
                    ),
                )
                editing = null
            },
        )
    }
}

@Composable
private fun TicketCard(ticket: TicketEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (ticket.isSuggestion) "${ticketTypeLabel(ticket.type)} · 交通建议"
                    else "${ticketTypeLabel(ticket.type)} · ${ticket.trainNo}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑车票")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (ticket.fromStation.isNotBlank() || ticket.toStation.isNotBlank()) {
                Text("${ticket.fromStation} → ${ticket.toStation}", style = MaterialTheme.typography.bodyLarge)
            }

            if (ticket.isSuggestion) {
                if (ticket.note.isNotBlank()) {
                    Text(ticket.note, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "车次与时刻请以 12306 实时查询为准",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = onEdit,
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("填写真实车票") }
            } else {
                val dep = formatDeparture(ticket.departureTime)
                val arr = ticket.arrivalTime?.takeIf { it > 0L }?.let { formatDeparture(it) }
                val dur = ticket.arrivalTime?.takeIf { it > ticket.departureTime && ticket.departureTime > 0L }
                    ?.let { formatDurationMs(it - ticket.departureTime) }
                val timeLine = listOfNotNull(
                    dep.takeIf { it.isNotBlank() }?.let { "$it 发车" },
                    arr?.let { "$it 到达" },
                    dur?.let { "全程约 $it" },
                ).joinToString(" · ")
                if (timeLine.isNotBlank()) {
                    Text(timeLine, style = MaterialTheme.typography.bodyMedium)
                }
                if (ticket.seat.isNotBlank()) {
                    Text("座位：${ticket.seat}", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "提前 ${ticket.remindMinutesBefore} 分钟提醒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private val dtf = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatDeparture(epoch: Long): String =
    if (epoch == 0L) "" else Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(dtf)

private fun formatDurationMs(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}小时${m}分钟"
        h > 0 -> "${h}小时"
        else -> "${m}分钟"
    }
}
