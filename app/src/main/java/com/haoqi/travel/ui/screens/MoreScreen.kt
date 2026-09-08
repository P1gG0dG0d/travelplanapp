package com.haoqi.travel.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haoqi.travel.data.local.entity.TripEntity
import com.haoqi.travel.data.remote.AiSettings
import com.haoqi.travel.ui.components.AnimatedAlertDialog
import com.haoqi.travel.ui.components.GroupCard
import com.haoqi.travel.ui.components.KeyboardGuardTextField
import com.haoqi.travel.ui.components.PrimaryButton
import com.haoqi.travel.ui.components.ScreenTitle
import com.haoqi.travel.ui.components.tapOutsideToDismissKeyboard
import com.haoqi.travel.ui.trips.TripViewModel

@Composable
fun MoreScreen(vm: TripViewModel) {
    val context = LocalContext.current
    val trips by vm.trips.collectAsStateWithLifecycle()
    val activeId by vm.activeTripId.collectAsStateWithLifecycle()
    var tripToDelete by remember { mutableStateOf<TripEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .tapOutsideToDismissKeyboard()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("更多")

        AiSettingsCard(context.applicationContext)

        GroupCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("旅行管理", style = MaterialTheme.typography.titleMedium)
                if (trips.isEmpty()) {
                    Text(
                        "还没有旅行。去「AI 规划」页生成第一个旅行。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    trips.forEach { trip ->
                        TripRow(
                            trip = trip,
                            active = trip.id == activeId,
                            onSelect = { vm.setActiveTrip(trip.id) },
                            onDelete = { tripToDelete = trip },
                        )
                    }
                }
            }
        }
    }

    tripToDelete?.let { trip ->
        AnimatedAlertDialog(
            onDismissRequest = { tripToDelete = null },
            title = { Text("删除旅行") },
            text = { Text("确定删除「${trip.name}」吗？该旅行的地点、行程、车票都会一起删除，且不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteTrip(context.applicationContext, trip)
                        tripToDelete = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { tripToDelete = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSettingsCard(context: Context) {
    val config = remember { AiSettings.load(context) }
    var providerIndex by remember {
        mutableStateOf(
            AiSettings.providers.indexOfFirst { it.baseUrl == config.baseUrl }.takeIf { it >= 0 } ?: 0
        )
    }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var webSearch by remember { mutableStateOf(AiSettings.isWebSearchEnabled(context)) }

    // 切换服务商：地址用该服务商默认值，Key 读该服务商自己保存的（互不串）
    fun selectProvider(i: Int) {
        providerIndex = i
        val p = AiSettings.providers[i]
        baseUrl = p.baseUrl
        apiKey = AiSettings.loadKey(context, p.name)
        saved = false
    }

    GroupCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI 生成设置", style = MaterialTheme.typography.titleMedium)
            Text(
                "填 DeepSeek 的 API Key 即可用 AI 规划。Key 只存在本机。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("服务商", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AiSettings.providers.forEachIndexed { i, p ->
                    FilterChip(
                        selected = providerIndex == i,
                        onClick = { selectProvider(i) },
                        label = { Text(p.name) },
                    )
                }
            }
            KeyboardGuardTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    saved = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            imageVector = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showKey) "隐藏 Key" else "显示 Key",
                        )
                    }
                },
            )
            KeyboardGuardTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    saved = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("接口地址") },
                singleLine = true,
            )
            PrimaryButton(
                text = "保存",
                onClick = {
                    AiSettings.save(context, AiSettings.providers[providerIndex].name, baseUrl, apiKey)
                    saved = true
                },
                enabled = apiKey.isNotBlank(),
            )
            if (saved) {
                Text(
                    "✅ 已保存（仅存在本机）",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("联网查真实车次/航班", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "更慢但更准；查不到会退回交通建议，订票前请以 12306 为准。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = webSearch,
                    onCheckedChange = {
                        webSearch = it
                        AiSettings.setWebSearchEnabled(context, it)
                    },
                )
            }
            Text(
                "申请入口：platform.deepseek.com（API Keys → 创建 Key）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TripRow(
    trip: TripEntity,
    active: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                trip.name,
                style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${trip.startDate} · ${trip.days} 天",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onSelect, enabled = !active) { Text(if (active) "当前" else "切换") }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "删除旅行", tint = MaterialTheme.colorScheme.error)
        }
    }
}
