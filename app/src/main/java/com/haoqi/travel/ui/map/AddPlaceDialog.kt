package com.haoqi.travel.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.haoqi.travel.data.local.entity.PlaceType
import com.haoqi.travel.ui.components.KeyboardGuardTextField

@Composable
fun AddPlaceDialog(
    initialAddress: String,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        type: PlaceType,
        address: String,
        note: String,
        needReservation: Boolean,
        reservationDate: String,
        bookingInfo: String,
    ) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(PlaceType.ATTRACTION) }
    var address by remember { mutableStateOf(initialAddress) }
    var note by remember { mutableStateOf("") }
    var needReservation by remember { mutableStateOf(false) }
    var reservationDate by remember { mutableStateOf("") }
    var bookingInfo by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加地点") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaceType.entries.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(typeLabel(t)) },
                        )
                    }
                }
                KeyboardGuardTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                KeyboardGuardTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                KeyboardGuardTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = needReservation, onCheckedChange = { needReservation = it })
                    Text("需要预约（门票 / 订位）")
                }
                if (needReservation) {
                    KeyboardGuardTextField(
                        value = reservationDate,
                        onValueChange = { reservationDate = it },
                        label = { Text("预约日期") },
                        placeholder = { Text("如 05-03 或 2026-05-03") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    KeyboardGuardTextField(
                        value = bookingInfo,
                        onValueChange = { bookingInfo = it },
                        label = { Text("预约/购票方式（可选）") },
                        placeholder = { Text("如 微信小程序「故宫」提前7天预约") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(name.trim(), type, address.trim(), note.trim(), needReservation, reservationDate.trim(), bookingInfo.trim())
                },
                enabled = name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun typeLabel(t: PlaceType): String = when (t) {
    PlaceType.ATTRACTION -> "景点"
    PlaceType.RESTAURANT -> "饭店"
    PlaceType.HOTEL -> "酒店"
}
