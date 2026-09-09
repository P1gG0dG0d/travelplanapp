package com.haoqi.travel.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,
    val spiceLevel: String = "",
    val avoidFood: String = "",
    val cuisines: String = "",
    val mealBudget: String = "",
    val hotelBudget: String = "",
    val hotelTier: String = "",
    val hotelLocation: String = "",
    val hotelBreakfast: Boolean = false,
    val transport: String = "",
    val stamina: String = "",
    val companion: String = "",
    val homeCity: String = "",
    val pace: String = "",
    /** 出行档次：经济 / 普通 / 商务（影响车票舱位推荐） */
    val transportClass: String = "",
)
