package com.haoqi.travel.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

@Database(
    entities = [
        TripEntity::class,
        PlaceEntity::class,
        PlanItemEntity::class,
        TicketEntity::class,
        ProfileEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun placeDao(): PlaceDao
    abstract fun planDao(): PlanDao
    abstract fun ticketDao(): TicketDao
    abstract fun profileDao(): ProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "haoqi_travel.db",
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profile ADD COLUMN homeCity TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profile ADD COLUMN pace TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v5：车票表支持「交通建议」（AI 不再编造具体车次） */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tickets ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tickets ADD COLUMN isSuggestion INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v6：地点表支持「预约/购票方式」 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE places ADD COLUMN bookingInfo TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
