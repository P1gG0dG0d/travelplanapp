package com.haoqi.travel.data.local.entity

enum class PlaceType { ATTRACTION, RESTAURANT, HOTEL }

enum class PlanSlot { MORNING, LUNCH, AFTERNOON, DINNER, EVENING }

enum class TicketType { TRAIN, HIGH_SPEED_RAIL, FLIGHT, BUS }

/** 各种交通方式默认的提前提醒分钟数（航班要值机安检，提得最早） */
fun defaultRemindMinutes(type: TicketType): Int = when (type) {
    TicketType.FLIGHT -> 120
    TicketType.BUS -> 30
    else -> 45
}
