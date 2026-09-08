package com.haoqi.travel.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tickets",
    foreignKeys = [ForeignKey(
        entity = TripEntity::class,
        parentColumns = ["id"],
        childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("tripId")],
)
data class TicketEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val type: TicketType,
    val trainNo: String,
    val fromStation: String,
    val toStation: String,
    val departureTime: Long, // epoch millis
    val arrivalTime: Long? = null,
    val seat: String = "",
    val remindMinutesBefore: Int = 45,
    val reminded: Boolean = false,
    /** AI 给的交通建议说明（建议时段/大约时长/大约票价等），真实车票一般为空 */
    val note: String = "",
    /** true = 这只是 AI 的「交通建议」，还没有车次和发车时间；false = 已落实的真实车票 */
    val isSuggestion: Boolean = false,
)
