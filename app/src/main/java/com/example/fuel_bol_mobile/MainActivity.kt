package com.example.fuel_bol_mobile

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupWindow
import android.widget.TableLayout
import android.widget.TextView
import android.widget.TableRow
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.example.fuel_bol_mobile.network.RetrofitClient
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotation
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager

import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.toString


class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var pointAnnotationManager: PointAnnotationManager
    private var popupWindow: PopupWindow? = null
    private val handler = Handler(Looper.getMainLooper())

    private val runnable = object : Runnable {
        override fun run() {
            fetchGeoJsonData()
            handler.postDelayed(this, 5 * 60 * 1000) // runs every 5 minutes
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        mapView = findViewById(R.id.mapView)

        // Load Mapbox map and fetch GeoJSON
        mapView.getMapboxMap().loadStyleUri("mapbox://styles/mapbox/light-v10") { style ->

            val redMarker = (ContextCompat.getDrawable(this, R.drawable.fuel_station_red)?.toBitmap())
                ?: throw RuntimeException("Could not load marker image")

            val orangeMarker = (ContextCompat.getDrawable(this, R.drawable.fuel_station_orange)?.toBitmap())
                ?: throw RuntimeException("Could not load marker image")

            val greenMarker = (ContextCompat.getDrawable(this, R.drawable.fuel_station_green)?.toBitmap())
                ?: throw RuntimeException("Could not load marker image")

            mapView.getMapboxMap().getStyle { style ->
                style.addImage("red-marker", redMarker)
            }

            mapView.getMapboxMap().getStyle { style ->
                style.addImage("orange-marker", orangeMarker)
            }

            mapView.getMapboxMap().getStyle { style ->
                style.addImage("green-marker", greenMarker)
            }
            centerMap(-66.15689955340157, -17.39228242512834, 12.0)

            pointAnnotationManager = mapView.annotations.createPointAnnotationManager()
            fetchGeoJsonData()
        }
        handler.post(runnable)
    }

    private fun centerMap(longitude: Double, latitude: Double, zoom: Double) {
        mapView.getMapboxMap().setCamera(
            CameraOptions.Builder()
                .center(Point.fromLngLat(longitude, latitude))
                .zoom(zoom)
                .build()
        )
    }

    private fun fetchGeoJsonData() {
        RetrofitClient.instance.getGeoJson().enqueue(object : Callback<GeoJsonResponse> {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onResponse(call: Call<GeoJsonResponse>, response: Response<GeoJsonResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val geoJsonResponse = response.body()
                    Log.d("GeoJSON", "Data: $geoJsonResponse")

                    geoJsonResponse?.features?.forEach { feature ->
                        val coordinates = feature.geometry.coordinates
                        if (coordinates.size == 2) {
                            val point = Point.fromLngLat(coordinates[0], coordinates[1])
                            val fuelStationName = feature.properties.get("fuelStationName")
                            val fuelLevel = feature.properties.get("levelBsa")
                            val monitoringAt = feature.properties.get("monitoringAt")
                            addMarker(point, fuelStationName, fuelLevel, monitoringAt)
                        }
                    }
                }
            }

            override fun onFailure(call: Call<GeoJsonResponse>, t: Throwable) {
                Log.e("GeoJSON", "Failed to fetch data", t)
            }
        })
    }

    fun String.toNumber(): Number? {
        return toIntOrNull() ?: toLongOrNull() ?: toDoubleOrNull() ?:
        runCatching { toBigDecimal() }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun addMarker(point: Point, fuelStationName: Any?, fuelLevel: Any?, monitoringAt: Any?) {
        val annotationApi = mapView.annotations
        pointAnnotationManager = annotationApi.createPointAnnotationManager()

        val fuelLevelValue: Number? = fuelLevel.toString().toNumber()

        val iconName = when {
            fuelLevelValue == null -> "red-marker"
            (fuelLevelValue.toDouble() >= 15000) -> "green-marker"
            (fuelLevelValue.toDouble() >= 5000) -> "orange-marker"
            else -> "red-marker"
        }

        val pointAnnotationOptions = PointAnnotationOptions()
            .withPoint(point)
            .withIconImage(iconName)
            .withIconSize(0.1)

        val annotation = pointAnnotationManager.create(pointAnnotationOptions)

        annotation.let { marker ->
            markerClickListener(marker, fuelStationName, fuelLevelValue?.toInt(), monitoringAt)
        }

    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun markerClickListener(marker: PointAnnotation, fuelStationName: Any?, fuelLevel: Any?, monitoringAt: Any?) {
        pointAnnotationManager.addClickListener { clickedMarker ->
            if (clickedMarker == marker) {
                showTooltip(
                    mapView,
                    fuelStationName.toString(),
                    fuelLevel.toString(),
                    monitoringAt.toString()
                )
            }
            true
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun dateTimeFormatter(dateStr: String): String {
        val dateTime = LocalDateTime.parse(dateStr)
        val formatter = DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a")
        val formattedDate = dateTime.format(formatter)
        return formattedDate
    }

    fun formatDecimalWithDots(numberStr: String): String {
        val number = numberStr.toDouble()
        return NumberFormat.getNumberInstance(Locale.GERMAN).format(number)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showTooltip(anchorView: View, fuelStationName: String, fuelLevel: String, monitoringAt: String) {

        popupWindow?.dismiss()

        val fuelLevelValue: Number? = fuelLevel.toNumber()
        System.out.println(" fuelLevelValue: " + fuelLevelValue)
        val fuelStationColor = when {
            fuelLevelValue == null -> Color.RED
            (fuelLevelValue.toDouble() >= 15000) -> Color.GREEN
            (fuelLevelValue.toDouble() >= 5000) -> Color.parseColor("#FF8C00")
            else -> Color.RED
        }

        val popupView = LayoutInflater.from(this).inflate(R.layout.tooltip_table, null)
        val tableLayout = popupView.findViewById<TableLayout>(R.id.tooltip_table)

        val popupBorder = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            setStroke(
                10,
                fuelStationColor
            )
            cornerRadius = 8f
        }
        popupView.background = popupBorder
        setTableTextColor(tableLayout, fuelStationColor)

        popupView.findViewById<TextView>(R.id.value1).text = fuelStationName
        popupView.findViewById<TextView>(R.id.value2).text = formatDecimalWithDots(fuelLevel) + " [L] (Approx)"
        popupView.findViewById<TextView>(R.id.value3).text = dateTimeFormatter(monitoringAt)

        popupWindow = PopupWindow(
            popupView,
            TableLayout.LayoutParams.WRAP_CONTENT,
            TableLayout.LayoutParams.WRAP_CONTENT,
            true
        )

        val rootView = findViewById<View>(android.R.id.content)
        anchorView.post {
            popupWindow?.showAtLocation(
                rootView,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                0,
                150
            )
        }
    }

    fun setTableTextColor(tableLayout: TableLayout, textColor: Int) {
        for (i in 0 until tableLayout.childCount) {
            val child = tableLayout.getChildAt(i)
            if (child is TableRow) {
                for (j in 0 until child.childCount) {
                    val cell = child.getChildAt(j)
                    if (cell is TextView) {
                        cell.setTextColor(textColor)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable)
    }
}