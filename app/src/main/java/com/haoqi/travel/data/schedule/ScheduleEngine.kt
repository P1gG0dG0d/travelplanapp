package com.haoqi.travel.data.schedule

import com.haoqi.travel.data.local.entity.PlaceEntity
import com.haoqi.travel.data.local.entity.PlaceType
import com.haoqi.travel.data.local.entity.PlanItemEntity
import com.haoqi.travel.data.local.entity.PlanSlot
import com.haoqi.travel.data.local.entity.TripEntity
import kotlin.math.ceil

/**
 * 本地规则排程引擎：
 * - 景点按 上午/下午/晚上 分布，每天最多 3 个（悠闲档）。
 * - 饭店分别进「午餐」和「晚餐」时段；晚上留给夜景等景点。
 * - 酒店不参与排程（单独展示）。
 */
object ScheduleEngine {

    fun build(trip: TripEntity, places: List<PlaceEntity>): Pair<List<PlanItemEntity>, Int> {
        val attractions = places.filter { it.type == PlaceType.ATTRACTION }.sortedBy { it.id }
        val restaurants = places.filter { it.type == PlaceType.RESTAURANT }.sortedBy { it.id }

        val attDays = if (attractions.isEmpty()) 0 else ceil(attractions.size / 3.0).toInt()
        val days = maxOf(trip.days, attDays, 1)
        val items = mutableListOf<PlanItemEntity>()

        var a = 0
        var r = 0
        for (day in 1..days) {
            if (a < attractions.size) {
                items.add(planItem(trip.id, day, PlanSlot.MORNING, attractions[a].id)); a++
            }
            if (r < restaurants.size) {
                items.add(planItem(trip.id, day, PlanSlot.LUNCH, restaurants[r].id)); r++
            }
            if (a < attractions.size) {
                items.add(planItem(trip.id, day, PlanSlot.AFTERNOON, attractions[a].id)); a++
            }
            if (r < restaurants.size) {
                items.add(planItem(trip.id, day, PlanSlot.DINNER, restaurants[r].id)); r++
            }
            if (a < attractions.size) {
                items.add(planItem(trip.id, day, PlanSlot.EVENING, attractions[a].id)); a++
            }
        }
        return items to days
    }

    private fun planItem(tripId: Long, day: Int, slot: PlanSlot, placeId: Long) =
        PlanItemEntity(
            tripId = tripId,
            dayIndex = day,
            slot = slot,
            placeId = placeId,
            orderIndex = 0,
            manual = false,
        )
}
