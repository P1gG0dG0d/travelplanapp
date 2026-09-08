package com.haoqi.travel.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.haoqi.travel.data.local.entity.PlaceEntity
import com.haoqi.travel.data.local.entity.TicketEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 本地提醒调度：用 AlarmManager 在出发前/预约当天提醒。
 * 不依赖网络，纯本地闹钟。
 */
object ReminderManager {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun scheduleTicket(context: Context, ticket: TicketEntity) {
        // 「交通建议」还没有真实发车时间，不排提醒（等用户填了真实车票再排）
        if (ticket.isSuggestion || ticket.departureTime <= 0L) return
        val triggerAt = ticket.departureTime - ticket.remindMinutesBefore * 60_000L
        if (triggerAt <= System.currentTimeMillis()) return
        val route = if (ticket.fromStation.isNotBlank() && ticket.toStation.isNotBlank()) {
            "${ticket.fromStation} → ${ticket.toStation} "
        } else ""
        val label = ticket.trainNo.ifBlank { "${ticket.fromStation}→${ticket.toStation}" }
        schedule(
            context,
            triggerAt,
            "出发提醒：$label",
            "${formatTime(ticket.departureTime)} ${route}发车，记得提前到站",
            requestCodeForTicket(ticket.id),
        )
    }

    fun cancelTicket(context: Context, ticket: TicketEntity) {
        cancel(context, requestCodeForTicket(ticket.id))
    }

    fun scheduleReservation(context: Context, tripStartDate: String, place: PlaceEntity) {
        if (!place.needReservation) return
        val date = parseReservationDate(tripStartDate, place.reservationDate) ?: return
        val triggerAt = date.atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (triggerAt <= System.currentTimeMillis()) return
        schedule(
            context,
            triggerAt,
            "预约提醒：${place.name}",
            "今天需要预约「${place.name}」，别忘了",
            requestCodeForPlace(place.id),
        )
    }

    fun cancelReservation(context: Context, place: PlaceEntity) {
        cancel(context, requestCodeForPlace(place.id))
    }

    private fun schedule(context: Context, triggerAt: Long, title: String, message: String, requestCode: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, requestCode, title, message)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            // 没有精确闹钟权限时退化为普通闹钟（可能延迟几分钟，仍会响）
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancel(context: Context, requestCode: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, requestCode, "", ""))
    }

    private fun pendingIntent(context: Context, requestCode: Int, title: String, message: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("title", title)
            putExtra("message", message)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestCodeForTicket(id: Long): Int = (id % 100_000).toInt()

    private fun requestCodeForPlace(id: Long): Int = ((id % 100_000) + 100_000).toInt()

    private fun formatTime(epoch: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZoneId.systemDefault()).format(timeFormatter)

    private fun parseReservationDate(tripStart: String, res: String?): LocalDate? {
        if (res.isNullOrBlank()) return null
        val trimmed = res.trim()
        return runCatching { LocalDate.parse(trimmed) }.getOrElse {
            runCatching {
                val year = LocalDate.parse(tripStart).year
                val parts = trimmed.split("-", ".").map { it.trim() }.filter { it.isNotBlank() }
                LocalDate.of(year, parts[0].toInt(), parts[1].toInt())
            }.getOrNull()
        }
    }
}
