package com.example.fuel_bol_mobile.network

import com.example.fuel_bol_mobile.GeoJsonResponse
import retrofit2.Call
import retrofit2.http.GET


interface GeoJsonApiService {
    @GET("/fuel-levels/geo/3/0")
    fun getGeoJson(): Call<GeoJsonResponse>
}