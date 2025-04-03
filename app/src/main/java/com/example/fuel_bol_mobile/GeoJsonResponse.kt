package com.example.fuel_bol_mobile

import com.google.gson.annotations.SerializedName


data class GeoJsonResponse(
    @SerializedName("type") val type: String,
    @SerializedName("features") val features: List<Feature>
)

data class Feature(
    @SerializedName("type") val type: String,
    @SerializedName("geometry") val geometry: Geometry,
    @SerializedName("properties") val properties: Map<String, Any>
)

data class Geometry(
    @SerializedName("type") val type: String,
    @SerializedName("coordinates") val coordinates: List<Double>
)