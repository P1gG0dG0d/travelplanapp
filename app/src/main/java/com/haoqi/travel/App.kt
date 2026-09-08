package com.haoqi.travel

import android.app.Application
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings
import com.haoqi.travel.data.reminder.ReminderReceiver
import com.haoqi.travel.di.AppContainer

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 高德地图合规初始化（隐私授权，需在地图使用前调用）
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)

        // 高德「搜索/地理编码」服务也需要单独做隐私合规初始化，否则 forwardGeocode 可能不返回结果
        ServiceSettings.updatePrivacyShow(this, true, true)
        ServiceSettings.updatePrivacyAgree(this, true)

        // 通知渠道（提醒用）
        ReminderReceiver.ensureChannel(this)
    }
}
