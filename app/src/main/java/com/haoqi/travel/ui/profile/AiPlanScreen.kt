package com.haoqi.travel.ui.profile

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haoqi.travel.data.importer.MdDocument
import com.haoqi.travel.data.importer.placeTypeLabel
import com.haoqi.travel.data.local.entity.PlaceType
import com.haoqi.travel.data.local.entity.ProfileEntity
import com.haoqi.travel.ui.components.AnimatedAlertDialog
import com.haoqi.travel.ui.components.GroupCard
import com.haoqi.travel.ui.components.KeyboardGuardTextField
import com.haoqi.travel.ui.components.PrimaryButton
import com.haoqi.travel.ui.components.ThinkingDots
import com.haoqi.travel.ui.components.tapOutsideToDismissKeyboard
import com.haoqi.travel.ui.theme.Motion
import com.haoqi.travel.ui.tickets.ticketTypeLabel
import com.haoqi.travel.ui.trips.AiPlanState
import com.haoqi.travel.ui.trips.ChatMsg
import com.haoqi.travel.ui.trips.PlanPhase
import com.haoqi.travel.ui.trips.TripViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val spiceOptions = listOf("不吃辣", "微辣", "中辣", "重辣")
private val tierOptions = listOf("经济型", "中端", "高端")
private val staminaOptions = listOf("能走", "一般", "需少走多休息")
private val transportOptions = listOf("地铁优先", "打车", "公交", "步行")
private val paceOptions = listOf("紧凑型", "普通型", "悠闲型")

@Composable
fun AiPlanScreen(vm: TripViewModel, onGoToItinerary: () -> Unit) {
    val context = LocalContext.current
    val plan by vm.planState.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()

    // 首次进入时，把画像里的「常住城市」预填到表单出发地
    LaunchedEffect(profile) {
        if (plan.homeCity.isBlank()) {
            profile?.homeCity?.takeIf { it.isNotBlank() }?.let { vm.updatePlanForm(homeCity = it) }
        }
    }

    if (plan.phase == PlanPhase.CHAT) {
        ChatPanel(plan, vm, context, onGoToItinerary)
    } else {
        FormPanel(plan, profile, vm, context)
    }
}

// ================= 表单阶段 =================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormPanel(plan: AiPlanState, profile: ProfileEntity?, vm: TripViewModel, context: Context) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .tapOutsideToDismissKeyboard()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "AI 规划",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 2.dp),
        )
        Text(
            "填时间和城市，AI 自动生成行程，可改到满意再导入",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        PreferenceCard(profile, vm, context)

        GroupCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("旅行信息", style = MaterialTheme.typography.titleMedium)
                DateField(plan.startDate) { vm.updatePlanForm(startDate = it) }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("天数", style = MaterialTheme.typography.bodyMedium)
                    IconButton(
                        onClick = { if (plan.days > 1) vm.updatePlanForm(days = plan.days - 1) },
                        enabled = plan.days > 1,
                    ) { Icon(Icons.Filled.Remove, contentDescription = "减少天数") }
                    Text("${plan.days}", style = MaterialTheme.typography.titleMedium)
                    IconButton(
                        onClick = { if (plan.days < 15) vm.updatePlanForm(days = plan.days + 1) },
                        enabled = plan.days < 15,
                    ) { Icon(Icons.Filled.Add, contentDescription = "增加天数") }
                    Text("天", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                KeyboardGuardTextField(
                    value = plan.homeCity,
                    onValueChange = { vm.updatePlanForm(homeCity = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("出发地") },
                    placeholder = { Text("如：济南（可留空）") },
                    singleLine = true,
                )

                Text("每天去的城市", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                plan.cities.forEachIndexed { i, city ->
                    KeyboardGuardTextField(
                        value = city,
                        onValueChange = { v ->
                            val list = plan.cities.toMutableList()
                            list[i] = v
                            vm.updatePlanForm(cities = list)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("第 ${i + 1} 天") },
                        placeholder = { Text(if (i == 0) "如：济南" else "留空则沿用前一天") },
                        singleLine = true,
                    )
                }

                if (plan.running) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThinkingDots()
                        Text(
                            "AI 正在规划…（可切到其它页面，后台继续）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                plan.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                PrimaryButton(
                    text = if (plan.running) "AI 正在规划…" else "AI 生成旅行",
                    onClick = { vm.generatePlan(context.applicationContext) },
                    enabled = !plan.running,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreferenceCard(profile: ProfileEntity?, vm: TripViewModel, context: Context) {
    var expanded by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(profile ?: ProfileEntity()) }
    LaunchedEffect(profile) { draft = profile ?: ProfileEntity() }
    val chevronAngle by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    GroupCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("个人偏好", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "口味 / 预算 / 节奏，不填用默认",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(chevronAngle),
                )
            }
            if (expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .animateContentSize(),
                ) {
                    OptionChips("口味辣度", spiceOptions, draft.spiceLevel) { draft = draft.copy(spiceLevel = it) }
                    PrefField(draft.avoidFood, { draft = draft.copy(avoidFood = it) }, "忌口 / 过敏（无就留空）")
                    PrefField(draft.cuisines, { draft = draft.copy(cuisines = it) }, "偏爱菜系")
                    PrefField(draft.mealBudget, { draft = draft.copy(mealBudget = it) }, "每餐人均（如 60 元）")
                    PrefField(draft.hotelBudget, { draft = draft.copy(hotelBudget = it) }, "酒店每晚（如 400 元）")
                    OptionChips("酒店档位", tierOptions, draft.hotelTier) { draft = draft.copy(hotelTier = it) }
                    PrefField(draft.hotelLocation, { draft = draft.copy(hotelLocation = it) }, "酒店位置（如市中心 / 近地铁）")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("需要含早餐", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = draft.hotelBreakfast, onCheckedChange = { draft = draft.copy(hotelBreakfast = it) })
                    }
                    OptionChips("出行方式", transportOptions, draft.transport) { draft = draft.copy(transport = it) }
                    OptionChips("体力", staminaOptions, draft.stamina) { draft = draft.copy(stamina = it) }
                    OptionChips("旅行节奏", paceOptions, draft.pace) { draft = draft.copy(pace = it) }
                    PrefField(draft.companion, { draft = draft.copy(companion = it) }, "同行（独自 / 情侣 / 带老人小孩）")
                    PrefField(draft.homeCity, { draft = draft.copy(homeCity = it) }, "常住城市（出发地）")
                    Button(
                        onClick = {
                            vm.saveProfile(draft)
                            Toast.makeText(context, "已保存偏好", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) { Text("保存偏好", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionChips(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { opt ->
                FilterChip(selected = selected == opt, onClick = { onSelect(opt) }, label = { Text(opt) })
            }
        }
    }
}

@Composable
private fun PrefField(value: String, onValueChange: (String) -> Unit, label: String) {
    KeyboardGuardTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(value: String, onChange: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("开始日期") },
            singleLine = true,
        )
        // 透明覆盖层：readOnly 输入框自己会吃掉点击，所以在它上面盖一层可点击区域
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { showPicker = true },
        )
    }
    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = parseToMillis(value))
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(millisToDate(it)) }
                    showPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } },
        ) { DatePicker(state = state) }
    }
}

private fun parseToMillis(s: String): Long = runCatching {
    LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrDefault(System.currentTimeMillis())

private fun millisToDate(m: Long): String =
    Instant.ofEpochMilli(m).atZone(ZoneId.systemDefault()).toLocalDate().toString()

// ================= 对话改稿阶段 =================

@Composable
private fun ChatPanel(plan: AiPlanState, vm: TripViewModel, context: Context, onGoToItinerary: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var confirmOpen by remember { mutableStateOf(false) }

    // imePadding：软键盘弹出时自动把输入框顶上去，避免被键盘遮住
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("AI 规划", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.resetPlan() }) { Text("退出") }
        }

        // 点消息区空白收键盘（防遮挡），输入框自己会消费点击，不会误触
        Column(
            modifier = Modifier
                .weight(1f)
                .tapOutsideToDismissKeyboard()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CurrentPlanCard(plan)
            plan.messages.forEach { msg -> AnimatedMessageBubble(msg) }
            if (plan.running || plan.importing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    ThinkingDots()
                    if (plan.importing) {
                        Text(
                            "正在导入行程…可切到其它页面",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        ThinkingStatus()
                    }
                }
            }
            plan.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (plan.searchNote.isNotBlank()) {
                Text(
                    plan.searchNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                KeyboardGuardTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("告诉 AI 怎么改") },
                    maxLines = 3,
                    shape = RoundedCornerShape(20.dp),
                )
                val canSend = input.isNotBlank() && !plan.running && !plan.importing
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (canSend) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer {
                            alpha = if (canSend) 1f else 0.6f
                        },
                ) {
                    IconButton(
                        onClick = {
                            vm.sendRefine(context.applicationContext, input)
                            input = ""
                        },
                        enabled = canSend,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            PrimaryButton(
                text = if (plan.importing) "正在导入…" else "满意，导入旅行",
                onClick = { confirmOpen = true },
                enabled = !plan.running && !plan.importing,
                highlight = true,
            )
        }
    }

    if (confirmOpen) {
        val defaultName = autoTripName(
            resolveCities(PlanSpec(plan.startDate, plan.days, plan.homeCity, plan.cities)),
            plan.days,
        )
        ConfirmTripDialog(
            defaultName = defaultName,
            onDismiss = { confirmOpen = false },
            onConfirm = { name ->
                confirmOpen = false
                vm.confirmPlan(context.applicationContext, name, onGoToItinerary)
            },
        )
    }
}

@Composable
private fun CurrentPlanCard(plan: AiPlanState) {
    val doc: MdDocument = plan.currentDoc ?: return
    var showRaw by remember { mutableStateOf(false) }
    GroupCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("当前行程", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "收起原文" else "查看原文") }
            }
            val placeCount = doc.days.sumOf { it.items.size }
            val realTicketCount = doc.tickets.count { !it.isSuggestion }
            val suggestCount = doc.tickets.count { it.isSuggestion }
            val trafficDesc = listOfNotNull(
                if (realTicketCount > 0) "$realTicketCount 张车票" else null,
                if (suggestCount > 0) "$suggestCount 条建议" else null,
            ).joinToString(" · ")
            Text(
                listOf("共 ${doc.days.size} 天", "$placeCount 个地点", trafficDesc)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showRaw) {
                SelectionContainer {
                    Text(plan.currentMd, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                doc.days.forEach { day ->
                    Text(
                        "第 ${day.dayIndex} 天 · ${day.city.ifBlank { "未标注城市" }}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    day.items.forEach { p ->
                        val suffix = if (p.type == PlaceType.HOTEL) {
                            when {
                                day.dayIndex == 1 -> "（入住）"
                                day.dayIndex == doc.days.size -> "（退房）"
                                else -> "（续住）"
                            }
                        } else ""
                        Text("· ${placeTypeLabel(p.type)} ${p.name}$suffix", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (doc.tickets.isNotEmpty()) {
                    Text(
                        if (doc.tickets.all { it.isSuggestion }) "交通建议" else "车票",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    doc.tickets.forEach { t ->
                        val parts = if (t.isSuggestion) {
                            listOfNotNull(
                                ticketTypeLabel(t.type),
                                "${t.fromStation}→${t.toStation}"
                                    .takeIf { t.fromStation.isNotBlank() || t.toStation.isNotBlank() },
                                t.note.takeIf { it.isNotBlank() },
                            )
                        } else {
                            val dep = formatDateTime(t.departureTime)
                            val arr = if (t.arrivalTime > 0L) formatDateTime(t.arrivalTime) else null
                            val dur = if (t.arrivalTime > 0L && t.departureTime > 0L && t.arrivalTime > t.departureTime) {
                                formatDurationMs(t.arrivalTime - t.departureTime)
                            } else null
                            listOfNotNull(
                                "${t.trainNo} ${t.fromStation}→${t.toStation}".trim(),
                                dep.takeIf { it.isNotBlank() }?.let { "$it 开" },
                                arr?.let { "$it 到" },
                                dur?.let { "约$it" },
                                t.seat.takeIf { it.isNotBlank() },
                            )
                        }
                        Text("· ${parts.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (doc.tickets.any { it.isSuggestion }) {
                        Text(
                            "带「建议」的还没查到确切班次，导入后可补填；填好才会发提醒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 生成/修改中轮流显示“思考流程”，让用户知道 AI 在干嘛 */
@Composable
private fun ThinkingStatus() {
    val steps = listOf(
        "正在查询交通班次…",
        "正在规划每日行程…",
        "正在比选酒店与饭店…",
        "正在整理成行程…",
    )
    var i by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2600)
            i = (i + 1) % steps.size
        }
    }
    Text(
        steps[i],
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 消息气泡：新出现时从下淡入 + 轻微上浮 */
@Composable
private fun AnimatedMessageBubble(msg: ChatMsg) {
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { state.targetState = true }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(Motion.fadeIn) + slideInVertically(Motion.slideEnter) { it / 20 },
    ) {
        MessageBubble(msg)
    }
}

/** iOS 风格聊天气泡：自己靠右浅青底，AI 靠左白底细描边 */
@Composable
private fun MessageBubble(msg: ChatMsg) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start) {
        Box(Modifier.fillMaxWidth(0.86f)) {
            Surface(
                color = if (msg.fromUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (msg.fromUser) 18.dp else 6.dp,
                    bottomEnd = if (msg.fromUser) 6.dp else 18.dp,
                ),
                border = if (msg.fromUser) null
                else androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.align(if (msg.fromUser) Alignment.CenterEnd else Alignment.CenterStart),
            ) {
                Text(
                    msg.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = if (msg.fromUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ConfirmTripDialog(defaultName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    AnimatedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入旅行") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前行程会创建为一个新旅行，名称可改：", style = MaterialTheme.typography.bodyMedium)
                KeyboardGuardTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("旅行名称") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.ifBlank { defaultName }) }) { Text("创建并查看") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private val dtf = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatDateTime(epoch: Long): String =
    if (epoch == 0L) "" else Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(dtf)

private fun formatDurationMs(ms: Long): String {
    val minutes = (ms / 60_000L).toInt()
    return when {
        minutes < 60 -> "${minutes}分钟"
        minutes % 60 == 0 -> "${minutes / 60}小时"
        else -> "${minutes / 60}小时${minutes % 60}分钟"
    }
}
