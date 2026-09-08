package com.haoqi.travel.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("更多", style = MaterialTheme.typography.headlineSmall)

        AiSettingsCard(context.applicationContext)

        Card(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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

        Text(
            "备份 · 节奏设置 · 更多设置（后续版本逐步上线）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    tripToDelete?.let { trip ->
        AlertDialog(
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
    var model by remember { mutableStateOf(config.model) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var webSearch by remember { mutableStateOf(AiSettings.isWebSearchEnabled(context)) }

    // 切换服务商：地址/模型用该服务商默认值，Key 读该服务商自己保存的（互不串）
    fun selectProvider(i: Int) {
        providerIndex = i
        val p = AiSettings.providers[i]
        baseUrl = p.baseUrl
        model = p.model
        apiKey = AiSettings.loadKey(context, p.name)
        saved = false
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("AI 生成设置", style = MaterialTheme.typography.titleMedium)
            Text(
                "填写 DeepSeek 的 API Key，即可在「AI 规划」页一键自动生成行程。Key 只保存在本机，不会上传。",
                style = MaterialTheme.typography.bodyMedium,
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
            OutlinedTextField(
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
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    saved = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("接口地址") },
                singleLine = true,
            )
            OutlinedTextField(
                value = model,
                onValueChange = {
                    model = it
                    saved = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("模型名称") },
                singleLine = true,
            )
            // 常用模型快选：点一下填进上面的输入框，仍可手动改
            val presetModels = AiSettings.providers[providerIndex].models
            if (presetModels.isNotEmpty()) {
                Text("常用模型（点选）", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presetModels.forEach { m ->
                        FilterChip(
                            selected = model == m,
                            onClick = {
                                model = m
                                saved = false
                            },
                            label = {
                                Text(
                                    when {
                                        m.contains("v4") -> "$m（更聪明，联网搜索推荐）"
                                        else -> "$m（便宜稳定）"
                                    },
                                )
                            },
                        )
                    }
                }
                if (webSearch && model == "deepseek-chat") {
                    Text(
                        "提示：联网搜索（尤其查机票）用 deepseek-v4-flash 效果更好，点上面的芯片切换后记得「保存」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Button(
                onClick = {
                    AiSettings.save(context, AiSettings.providers[providerIndex].name, baseUrl, model, apiKey)
                    saved = true
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = apiKey.isNotBlank(),
            ) { Text("保存") }
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
                    Text("让 AI 联网查资料（实验功能）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "开启后改用 DeepSeek 的 Responses API，让模型联网搜索 12306 / 航司的真实班次，能查到就给出具体车次和发车时间；" +
                            "查不到会退回「交通建议」。如果这个接口调不通，会自动退回普通模式，不影响你正常使用。\n" +
                            "注意：AI 给的车次和时间仍可能有误差，订票前请以 12306 实时查询为准。",
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
