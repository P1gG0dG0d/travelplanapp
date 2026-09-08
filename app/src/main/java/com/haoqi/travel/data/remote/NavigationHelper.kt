package com.haoqi.travel.data.remote

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 一键跳转高德地图「路线规划」页（可切换驾车/公交/步行/骑行/打车等）。
 * 有坐标时按坐标规划路线；没坐标时按名称搜索。
 * 若手机没装高德 App，则退回高德网页版。
 */
object NavigationHelper {

    fun navigateTo(context: Context, name: String, latitude: Double?, longitude: Double?) {
        val appUri = if (latitude != null && longitude != null) {
            "androidamap://route?sourceApplication=${Uri.encode("好奇旅行")}" +
                "&dlat=$latitude&dlon=$longitude" +
                "&dname=${Uri.encode(name)}&dev=0&t=0"
        } else {
            "androidamap://poi?sourceApplication=${Uri.encode("好奇旅行")}" +
                "&keywords=${Uri.encode(name)}&dev=0"
        }

        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(appUri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val opened = runCatching { context.startActivity(appIntent) }.isSuccess

        if (!opened) {
            val webUri = if (latitude != null && longitude != null) {
                "https://uri.amap.com/navigation?to=$longitude,$latitude,${Uri.encode(name)}&mode=car"
            } else {
                "https://uri.amap.com/search?keyword=${Uri.encode(name)}"
            }
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(webUri)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }
}
