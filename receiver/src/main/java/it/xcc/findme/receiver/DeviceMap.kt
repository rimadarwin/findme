package it.xcc.findme.receiver

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import it.xcc.findme.core.DeviceLocation
import it.xcc.findme.core.ReceiverTransmitter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

@Composable
fun DeviceMap(
    deviceName: String,
    location: DeviceLocation,
    geofence: ReceiverTransmitter? = null,
    fillAvailable: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    val styleRequested = remember(mapView) { AtomicBoolean(false) }
    val lastCameraPoint = remember(mapView) { AtomicReference<String?>(null) }

    DisposableEffect(mapView, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(
        modifier = (if (fillAvailable) {
            modifier.fillMaxSize()
        } else {
            modifier
                .fillMaxWidth()
                .height(320.dp)
        }).pointerInteropFilter { event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE,
                android.view.MotionEvent.ACTION_POINTER_DOWN,
                -> mapView.parent?.requestDisallowInterceptTouchEvent(true)

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL,
                -> mapView.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        },
        factory = { mapView },
        update = { view ->
            val point = LatLng(location.latitude, location.longitude)
            view.getMapAsync { map ->
                fun updateSources(style: Style) {
                    style.getSourceAs<GeoJsonSource>(DEVICE_SOURCE)?.setGeoJson(
                        Feature.fromGeometry(
                            Point.fromLngLat(location.longitude, location.latitude),
                        ).apply { addStringProperty("name", deviceName) },
                    )
                    val area = geofence?.takeIf { it.geofenceEnabled }?.let {
                        geofenceFeature(
                            latitude = it.geofenceCenterLatitude ?: return@let null,
                            longitude = it.geofenceCenterLongitude ?: return@let null,
                            radiusM = it.geofenceRadiusM ?: return@let null,
                        )
                    }
                    style.getSourceAs<GeoJsonSource>(GEOFENCE_SOURCE)?.let { source ->
                        if (area != null) {
                            source.setGeoJson(area)
                        } else {
                            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
                        }
                    }
                    val cameraKey = "${location.latitude}:${location.longitude}"
                    if (lastCameraPoint.getAndSet(cameraKey) != cameraKey) {
                        map.cameraPosition = CameraPosition.Builder()
                            .target(point)
                            .zoom(16.0)
                            .build()
                    }
                }

                fun configureStyle(style: Style) {
                    if (style.getSource(DEVICE_SOURCE) == null) {
                        style.addSource(GeoJsonSource(DEVICE_SOURCE))
                        style.addSource(GeoJsonSource(GEOFENCE_SOURCE))
                        style.addLayer(
                            FillLayer(GEOFENCE_FILL_LAYER, GEOFENCE_SOURCE).withProperties(
                                fillColor("#00AEEF"),
                                fillOpacity(0.18f),
                            ),
                        )
                        style.addLayer(
                            LineLayer(GEOFENCE_LINE_LAYER, GEOFENCE_SOURCE).withProperties(
                                lineColor("#00AEEF"),
                                lineWidth(2.5f),
                            ),
                        )
                        style.addLayer(
                            CircleLayer(DEVICE_LAYER, DEVICE_SOURCE).withProperties(
                                circleColor("#00AEEF"),
                                circleRadius(8f),
                                circleStrokeColor("#002B40"),
                                circleStrokeWidth(3f),
                            ),
                        )
                    }
                    updateSources(style)
                }
                if (map.style == null && styleRequested.compareAndSet(false, true)) {
                    map.setStyle(Style.Builder().fromUri(OPEN_FREE_MAP_STYLE)) {
                        configureStyle(it)
                    }
                } else {
                    map.style?.let(::configureStyle)
                }
            }
        },
    )
}

private fun geofenceFeature(
    latitude: Double,
    longitude: Double,
    radiusM: Int,
): Feature {
    val angularDistance = radiusM / EARTH_RADIUS_M
    val centerLatitude = Math.toRadians(latitude)
    val centerLongitude = Math.toRadians(longitude)
    val points = (0..64).map { step ->
        val bearing = 2.0 * Math.PI * step / 64.0
        val pointLatitude = asin(
            sin(centerLatitude) * cos(angularDistance) +
                cos(centerLatitude) * sin(angularDistance) * cos(bearing),
        )
        val pointLongitude = centerLongitude + atan2(
            sin(bearing) * sin(angularDistance) * cos(centerLatitude),
            cos(angularDistance) - sin(centerLatitude) * sin(pointLatitude),
        )
        Point.fromLngLat(Math.toDegrees(pointLongitude), Math.toDegrees(pointLatitude))
    }
    return Feature.fromGeometry(Polygon.fromLngLats(listOf(points)))
}

private const val OPEN_FREE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val DEVICE_SOURCE = "findme-device-source"
private const val GEOFENCE_SOURCE = "findme-geofence-source"
private const val DEVICE_LAYER = "findme-device-layer"
private const val GEOFENCE_FILL_LAYER = "findme-geofence-fill-layer"
private const val GEOFENCE_LINE_LAYER = "findme-geofence-line-layer"
private const val EARTH_RADIUS_M = 6_371_000.0
