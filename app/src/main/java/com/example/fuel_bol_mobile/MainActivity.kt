package com.example.fuel_bol_mobile

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.Manifest
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
import android.widget.TableRow
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale


class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var pointAnnotationManager: PointAnnotationManager
    private val pointAnnotations = mutableListOf<PointAnnotation>()
    private val annotationDataMap = mutableMapOf<PointAnnotation, AnnotationData>()
    private var popupWindow: PopupWindow? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isTooltipVisible = false
    private val PERMISSION_REQUEST_CODE = 101


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        mapView = findViewById(R.id.mapView)

        mapView.getMapboxMap().loadStyleUri("mapbox://styles/mapbox/light-v10") { style ->

            val redMarker = ContextCompat.getDrawable(this, R.drawable.fuel_station_red)?.toBitmap()
            val orangeMarker = ContextCompat.getDrawable(this, R.drawable.fuel_station_orange)?.toBitmap()
            val greenMarker = ContextCompat.getDrawable(this, R.drawable.fuel_station_green)?.toBitmap()
            val blackMarker = ContextCompat.getDrawable(this, R.drawable.fuel_station_black)?.toBitmap()

            style.addImage("red-marker", redMarker!!)
            style.addImage("orange-marker", orangeMarker!!)
            style.addImage("green-marker", greenMarker!!)
            style.addImage("black-marker", blackMarker!!)

            pointAnnotationManager = mapView.annotations.createPointAnnotationManager()
            addAnnotationClickListener()

            mapView.getMapboxMap().addOnMapClickListener { point ->
                resetAllAnnotationsOpacity(1.0)
                popupWindow?.dismiss()
                isTooltipVisible = false
                true
            }
            fetchGeoJsonData()
            scheduleNextUpdate() // Start precise scheduling here
        }
        requestLocationPermission()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun scheduleNextUpdate() {
        val now = System.currentTimeMillis()
        val next10Min = (now / (10 * 60 * 1000) + 1) * (10 * 60 * 1000)
        val delay = next10Min - now

        handler.postDelayed({
            fetchGeoJsonData()
            scheduleRepeatingUpdates()
        }, delay)
    }

    private fun scheduleRepeatingUpdates() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                fetchGeoJsonData()
                handler.postDelayed(this, 10 * 60 * 1000) // Every 10 minutes
            }
        }, 10 * 60 * 1000)
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
                    // Remove old markers
                    pointAnnotationManager.deleteAll()
                    pointAnnotations.clear()
                    annotationDataMap.clear()

                    val geoJsonResponse = response.body()
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
            .withIconOpacity(1.0)

        val annotation = pointAnnotationManager.create(pointAnnotationOptions)

        annotationDataMap[annotation] = AnnotationData(
            fuelStationName = fuelStationName.toString(),
            fuelLevel = fuelLevel.toString(),
            monitoringAt = monitoringAt.toString()
        )

        pointAnnotations.add(annotation)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun addAnnotationClickListener() {

        pointAnnotationManager.addClickListener { clicked ->
            resetAllAnnotationsOpacity(0.1)
            pointAnnotations.forEach { annotation ->
                if (clicked == annotation) {
                    annotation.iconOpacity = 1.0
                    pointAnnotationManager.update(annotation)

                    val annotationData = annotationDataMap[annotation]
                    annotationData?.let {
                        showTooltip(
                            mapView,
                            it.fuelStationName,
                            it.fuelLevel,
                            it.monitoringAt
                        )
                    }
                    isTooltipVisible = true
                }
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

    private fun resetAllAnnotationsOpacity(opacity: Double) {
        pointAnnotations.forEach { annotation ->
            annotation.iconOpacity = opacity
            pointAnnotationManager.update(annotation)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showTooltip(anchorView: View, fuelStationName: String, fuelLevel: String, monitoringAt: String) {
        popupWindow?.dismiss()

        val fuelLevelValue: Number? = fuelLevel.toString().toNumber()
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
            setStroke(10, fuelStationColor)
            cornerRadius = 8f
        }
        popupView.background = popupBorder
        setTableTextColor(tableLayout, fuelStationColor)

        popupView.findViewById<TextView>(R.id.value1).text = fuelStationName
        popupView.findViewById<TextView>(R.id.value2).text = formatDecimalWithDots(fuelLevel) + " [Liters] (Approx.)"
        popupView.findViewById<TextView>(R.id.value3).text = "Last update: " + dateTimeFormatter(monitoringAt)

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
    }

    private fun requestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                PERMISSION_REQUEST_CODE
            )
        } else {
            enableUserLocation()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enableUserLocation()
        }
    }

    private fun enableUserLocation() {
        val locationComponentPlugin = mapView.location

        locationComponentPlugin.updateSettings {
            enabled = true
            pulsingEnabled = true
            pulsingMaxRadius = 100f
            layerBelow = null
        }

        val listener = object : OnIndicatorPositionChangedListener {
            override fun onIndicatorPositionChanged(point: Point) {
                centerMap(point.longitude(), point.latitude(), 12.0)
                locationComponentPlugin.removeOnIndicatorPositionChangedListener(this)
            }
        }

        locationComponentPlugin.addOnIndicatorPositionChangedListener(listener)
    }

}

data class AnnotationData(
    val fuelStationName: String,
    val fuelLevel: String,
    val monitoringAt: String
)
