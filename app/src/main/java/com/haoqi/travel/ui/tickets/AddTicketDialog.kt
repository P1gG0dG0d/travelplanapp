package com.haoqi.travel.ui.tickets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.haoqi.travel.data.local.entity.TicketEntity
import com.haoqi.travel.data.local.entity.TicketType
import com.haoqi.travel.ui.components.AnimatedAlertDialog
import com.haoqi.travel.ui.components.KeyboardGuardTextField
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun ticketTypeLabel(t: TicketType): String = when (t) {
    TicketType.TRAIN -> "火车"
    TicketType.HIGH_SPEED_RAIL -> "高铁"
    TicketType.FLIGHT -> "航班"
    TicketType.BUS -> "大巴"
}

/**
 * 添加 / 编辑车票。
 *
 * [initial] 不为 null 时是「编辑已有车票」，最常见的用法是把 AI 给的「交通建议」
 * 落实成真实车票：区间和交通方式已经帮你预填好，只需补上车次和发车时间。
 */
@Composable
fun AddTicketDialog(
    initialDate: String,
    onDismiss: () -> Unit,
    onConfirm: (type: TicketType, trainNo: String, from: String, to: String, departureEpoch: Long, seat: String) -> Unit,
    initial: TicketEntity? = null,
) {
    val prefilled = remember(initial) {
        initial?.departureTime?.takeIf { it > 0L }?.let { epochToDateAndTime(it) }
    }

    var type by remember { mutableStateOf(initial?.type ?: TicketType.HIGH_SPEED_RAIL) }
    var trainNo by remember { mutableStateOf(initial?.trainNo.orEmpty()) }
    var from by remember { mutableStateOf(initial?.fromStation.orEmpty()) }
    var to by remember { mutableStateOf(initial?.toStation.orEmpty()) }
    var date by remember { mutableStateOf(prefilled?.first ?: initialDate) }
    var time by remember { mutableStateOf(prefilled?.second ?: "09:00") }
    var seat by remember { mutableStateOf(initial?.seat.orEmpty()) }

    val epoch = remember(date, time) {
        runCatching {
            LocalDateTime.parse("${date.trim()}T${time.trim()}")
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    AnimatedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加车票" else "填写真实车票") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (initial?.isSuggestion == true) {
                    Text(
                        "AI 不会替你编车次。买到票后把真实信息填进来，App 就会按发车时间提醒你；区间和方式已经帮你带过来了。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TicketType.entries.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(ticketTypeLabel(t)) },
                        )
                    }
                }
                KeyboardGuardTextField(
                    value = trainNo,
                    onValueChange = { trainNo = it },
                    label = { Text("车次/航班号（如 G1234）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyboardGuardTextField(
                        value = from,
                        onValueChange = { from = it },
                        label = { Text("出发站/城市") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    KeyboardGuardTextField(
                        value = to,
                        onValueChange = { to = it },
                        label = { Text("到达站/城市") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyboardGuardTextField(
                        value = date,
                        onValueChange = { date = it },
                        label = { Text("日期 yyyy-MM-dd") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    KeyboardGuardTextField(
                        value = time,
                        onValueChange = { time = it },
                        label = { Text("时间 HH:mm") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                KeyboardGuardTextField(
                    value = seat,
                    onValueChange = { seat = it },
                    label = { Text("座位（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (epoch == null) {
                    Text("请把日期和时间填成 2026-05-03 和 14:00 这样的格式", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { epoch?.let { onConfirm(type, trainNo.trim(), from.trim(), to.trim(), it, seat.trim()) } },
                enabled = epoch != null && trainNo.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private val hmFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun epochToDateAndTime(epoch: Long): Pair<String, String> {
    val z = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault())
    return z.toLocalDate().toString() to z.format(hmFormatter)
}
