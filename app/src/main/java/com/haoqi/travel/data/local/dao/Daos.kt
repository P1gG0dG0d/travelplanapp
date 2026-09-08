package com.haoqi.travel.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.haoqi.travel.data.local.entity.PlaceEntity
import com.haoqi.travel.data.local.entity.PlanItemEntity
import com.haoqi.travel.data.local.entity.ProfileEntity
import com.haoqi.travel.data.local.entity.TicketEntity
import com.haoqi.travel.data.local.entity.TripEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY createdAt DESC")
    fun observeTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getTrip(id: Long): TripEntity?

    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    @Delete
    suspend fun delete(trip: TripEntity)
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places WHERE tripId = :tripId ORDER BY id")
    fun observePlaces(tripId: Long): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun getPlace(id: Long): PlaceEntity?

    @Query("DELETE FROM places WHERE tripId = :tripId")
    suspend fun clearForTrip(tripId: Long)

    @Insert
    suspend fun insert(place: PlaceEntity): Long

    @Update
    suspend fun update(place: PlaceEntity)

    @Delete
    suspend fun delete(place: PlaceEntity)
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM plan_items WHERE tripId = :tripId")
    fun observePlan(tripId: Long): Flow<List<PlanItemEntity>>

    @Insert
    suspend fun insert(item: PlanItemEntity): Long

    @Insert
    suspend fun insertAll(items: List<PlanItemEntity>)

    @Update
    suspend fun update(item: PlanItemEntity)

    @Delete
    suspend fun delete(item: PlanItemEntity)

    @Query("DELETE FROM plan_items WHERE tripId = :tripId")
    suspend fun clearForTrip(tripId: Long)
}

@Dao
interface TicketDao {
    // 已买好的真实车票按发车时间排在前面，AI 的交通建议排在后面
    @Query("SELECT * FROM tickets WHERE tripId = :tripId ORDER BY isSuggestion, departureTime")
    fun observeTickets(tripId: Long): Flow<List<TicketEntity>>

    @Query("DELETE FROM tickets WHERE tripId = :tripId")
    suspend fun clearForTrip(tripId: Long)

    @Insert
    suspend fun insert(ticket: TicketEntity): Long

    @Update
    suspend fun update(ticket: TicketEntity)

    @Delete
    suspend fun delete(ticket: TicketEntity)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = 1")
    fun observeProfile(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE id = 1")
    suspend fun getProfile(): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity)
}
