package com.haoqi.travel.data.repository

import com.haoqi.travel.data.importer.MdDocument
import com.haoqi.travel.data.local.dao.PlaceDao
import com.haoqi.travel.data.local.dao.PlanDao
import com.haoqi.travel.data.local.dao.ProfileDao
import com.haoqi.travel.data.local.dao.TicketDao
import com.haoqi.travel.data.local.dao.TripDao
import com.haoqi.travel.data.local.entity.PlaceEntity
import com.haoqi.travel.data.local.entity.PlanItemEntity
import com.haoqi.travel.data.local.entity.ProfileEntity
import com.haoqi.travel.data.local.entity.TicketEntity
import com.haoqi.travel.data.local.entity.TripEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TravelRepository(
    private val tripDao: TripDao,
    private val placeDao: PlaceDao,
    private val planDao: PlanDao,
    private val ticketDao: TicketDao,
    private val profileDao: ProfileDao,
) {
    // ---- 旅行 ----
    fun observeTrips(): Flow<List<TripEntity>> = tripDao.observeTrips()

    suspend fun updateTrip(trip: TripEntity) = tripDao.update(trip)

    suspend fun deleteTrip(trip: TripEntity) = tripDao.delete(trip)

    // ---- 地点 ----
    fun observePlaces(tripId: Long): Flow<List<PlaceEntity>> = placeDao.observePlaces(tripId)

    suspend fun addPlace(place: PlaceEntity): Long = placeDao.insert(place)

    suspend fun updatePlace(place: PlaceEntity) = placeDao.update(place)

    suspend fun deletePlace(place: PlaceEntity) = placeDao.delete(place)

    // ---- 日程 ----
    fun observePlan(tripId: Long): Flow<List<PlanItemEntity>> =
        planDao.observePlan(tripId).map { list ->
            list.sortedWith(compareBy({ it.dayIndex }, { it.slot.ordinal }, { it.orderIndex }))
        }

    suspend fun replacePlan(tripId: Long, items: List<PlanItemEntity>) {
        planDao.clearForTrip(tripId)
        if (items.isNotEmpty()) planDao.insertAll(items)
    }

    // ---- 车票 ----
    fun observeTickets(tripId: Long): Flow<List<TicketEntity>> = ticketDao.observeTickets(tripId)

    suspend fun addTicket(ticket: TicketEntity): Long = ticketDao.insert(ticket)

    suspend fun updateTicket(ticket: TicketEntity) = ticketDao.update(ticket)

    suspend fun deleteTicket(ticket: TicketEntity) = ticketDao.delete(ticket)

    // ---- AI 生成：把解析出的行程写成一个新旅行 ----
    suspend fun createTripFromDoc(
        name: String,
        startDate: String,
        doc: MdDocument,
        coords: Map<Int, Pair<Double, Double>>,
    ): Long {
        val tripId = tripDao.insert(
            TripEntity(
                name = name,
                startDate = startDate,
                days = doc.days.maxOfOrNull { it.dayIndex } ?: 1,
            )
        )
        writeContent(tripId, doc, coords)
        return tripId
    }

    private suspend fun writeContent(tripId: Long, doc: MdDocument, coords: Map<Int, Pair<Double, Double>>) {
        val planItems = mutableListOf<PlanItemEntity>()
        var idx = 0
        for (day in doc.days) {
            for (p in day.items) {
                val c = coords[idx]
                val placeId = placeDao.insert(
                    PlaceEntity(
                        tripId = tripId,
                        name = p.name,
                        type = p.type,
                        city = day.city,
                        address = p.address,
                        latitude = c?.first,
                        longitude = c?.second,
                        durationMinutes = p.durationMinutes,
                        needReservation = p.needReservation,
                        reservationDate = p.reservationDate,
                        checkIn = p.checkIn,
                        checkOut = p.checkOut,
                        pricePerPerson = p.pricePerPerson,
                        pricePerNight = p.pricePerNight,
                        note = p.note,
                        bookingInfo = p.bookingInfo,
                    )
                )
                p.slot?.let { slot ->
                    planItems.add(
                        PlanItemEntity(
                            tripId = tripId,
                            dayIndex = day.dayIndex,
                            slot = slot,
                            placeId = placeId,
                            orderIndex = planItems.size,
                            manual = false,
                        )
                    )
                }
                idx++
            }
        }
        if (planItems.isNotEmpty()) planDao.insertAll(planItems)
        for (t in doc.tickets) {
            ticketDao.insert(
                TicketEntity(
                    tripId = tripId,
                    type = t.type,
                    trainNo = t.trainNo,
                    fromStation = t.fromStation,
                    toStation = t.toStation,
                    departureTime = t.departureTime,
                    arrivalTime = t.arrivalTime,
                    seat = t.seat,
                    note = t.note,
                    isSuggestion = t.isSuggestion,
                )
            )
        }
    }

    // ---- 个人画像 ----
    fun observeProfile(): Flow<ProfileEntity?> = profileDao.observeProfile()

    suspend fun saveProfile(profile: ProfileEntity) = profileDao.upsert(profile)
}
