package com.haoqi.travel.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "places",
    foreignKeys = [ForeignKey(
        entity = TripEntity::class,
        parentColumns = ["id"],
        childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("tripId")],
)
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val name: String,
    val type: PlaceType,
    val city: String = "",
    val address: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val durationMinutes: Int? = null,
    val needReservation: Boolean = false,
    val reservationDate: String? = null,
    val reservationStatus: String = "",
    val checkIn: String? = null,
    val checkOut: String? = null,
    val pricePerPerson: Int? = null,
    val pricePerNight: Int? = null,
    val note: String = "",
    /** 预约/购票方式（如「微信小程序「故宫」提前7天预约」「现场购票」） */
    val bookingInfo: String = "",
)
