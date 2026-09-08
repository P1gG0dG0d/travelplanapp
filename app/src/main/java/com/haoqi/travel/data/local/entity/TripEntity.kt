package com.haoqi.travel.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startDate: String, // yyyy-MM-dd
    val days: Int,
    val createdAt: Long = System.currentTimeMillis(),
)
