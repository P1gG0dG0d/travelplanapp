package com.haoqi.travel.data.remote

import android.content.Context
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.geocoder.GeocodeQuery
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.geocoder.RegeocodeResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class GeoAddress(val formatted: String, val city: String)

data class GeoPoint(val lat: Double, val lng: Double)

object GeocodeHelper {

    /** 逆地理编码：经纬度 → 地址、城市 */
    suspend fun reverseGeocode(context: Context, lat: Double, lng: Double): GeoAddress? =
        suspendCancellableCoroutine { cont ->
            val search = GeocodeSearch(context)
            search.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                override fun onRegeocodeSearched(result: RegeocodeResult, rCode: Int) {
                    val addr = result.regeocodeAddress
                    cont.resume(GeoAddress(formatted = addr?.formatAddress ?: "", city = addr?.city ?: ""))
                }

                override fun onGeocodeSearched(result: GeocodeResult, rCode: Int) {
                    cont.resume(null)
                }
            })
            search.getFromLocationAsyn(RegeocodeQuery(LatLonPoint(lat, lng), 200f, GeocodeSearch.AMAP))
            cont.invokeOnCancellation { }
        }

    /** 正向地理编码：名称/地址 → 经纬度（用于 MD 导入时自动定位） */
    suspend fun forwardGeocode(context: Context, name: String, city: String): GeoPoint? =
        suspendCancellableCoroutine { cont ->
            val search = GeocodeSearch(context)
            search.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                override fun onRegeocodeSearched(result: RegeocodeResult, rCode: Int) {
                    cont.resume(null)
                }

                override fun onGeocodeSearched(result: GeocodeResult, rCode: Int) {
                    val p = result.geocodeAddressList.firstOrNull()?.latLonPoint
                    cont.resume(if (p != null) GeoPoint(p.latitude, p.longitude) else null)
                }
            })
            search.getFromLocationNameAsyn(GeocodeQuery(name, city))
            cont.invokeOnCancellation { }
        }
}
