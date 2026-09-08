package com.haoqi.travel.di

import android.content.Context
import com.haoqi.travel.data.local.AppDatabase
import com.haoqi.travel.data.repository.TravelRepository

class AppContainer(context: Context) {
    private val database = AppDatabase.get(context)

    val repository: TravelRepository = TravelRepository(
        tripDao = database.tripDao(),
        placeDao = database.placeDao(),
        planDao = database.planDao(),
        ticketDao = database.ticketDao(),
        profileDao = database.profileDao(),
    )
}
