/**
 * @author Infinity
 * @description Mappa della distanza fra trasmettitore e ricevitore.
 * @modified 29.09.2026 - MDS | Aggiunti marker distinti, linea tratteggiata e bounds dinamici.
 */
package it.xcc.findme.receiver

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import it.xcc.findme.core.DeviceLocation
import it.xcc.findme.core.TrackingConfigResolver
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Visualizza i due telefoni e il segmento tratteggiato che li unisce. */
@Composable
fun DistanceMap(
    transmitterLocation: DeviceLocation,
    receiverLocation: DeviceLocation,
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
    val lastBoundsKey = remember(mapView) { AtomicReference<String?>(null) }

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
            modifier.fillMaxWidth().height(320.dp)
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
            view.getMapAsync { map ->
                /** Aggiorna geometrie e inquadratura senza ricreare lo stile. */
                fun updateSources(style: Style) {
                    val transmitterPoint = Point.fromLngLat(
                        transmitterLocation.longitude,
                        transmitterLocation.latitude,
                    )
                    val receiverPoint = Point.fromLngLat(
                        receiverLocation.longitude,
                        receiverLocation.latitude,
                    )
                    style.getSourceAs<GeoJsonSource>(LINE_SOURCE)?.setGeoJson(
                        Feature.fromGeometry(
                            LineString.fromLngLats(listOf(transmitterPoint, receiverPoint)),
                        ),
                    )
                    style.getSourceAs<GeoJsonSource>(TRANSMITTER_SOURCE)
                        ?.setGeoJson(Feature.fromGeometry(transmitterPoint))
                    style.getSourceAs<GeoJsonSource>(RECEIVER_SOURCE)
                        ?.setGeoJson(Feature.fromGeometry(receiverPoint))
                    val boundsKey =
                        "${transmitterLocation.latitude}:${transmitterLocation.longitude}:" +
                            "${receiverLocation.latitude}:${receiverLocation.longitude}"
                    if (lastBoundsKey.getAndSet(boundsKey) != boundsKey) {
                        val distanceMeters = TrackingConfigResolver.distanceMeters(
                            transmitterLocation,
                            receiverLocation,
                        )
                        val cameraUpdate = if (distanceMeters < CLOSE_DISTANCE_METERS) {
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(
                                    (transmitterLocation.latitude + receiverLocation.latitude) / 2,
                                    (transmitterLocation.longitude + receiverLocation.longitude) / 2,
                                ),
                                CLOSE_DISTANCE_ZOOM,
                            )
                        } else {
                            val bounds = LatLngBounds.Builder()
                                .include(
                                    LatLng(
                                        transmitterLocation.latitude,
                                        transmitterLocation.longitude,
                                    ),
                                )
                                .include(
                                    LatLng(
                                        receiverLocation.latitude,
                                        receiverLocation.longitude,
                                    ),
                                )
                                .build()
                            CameraUpdateFactory.newLatLngBounds(bounds, 72)
                        }
                        map.easeCamera(cameraUpdate, 500)
                    }
                }

                /** Configura una sola volta sorgenti e livelli della mappa. */
                fun configureStyle(style: Style) {
                    if (style.getSource(LINE_SOURCE) == null) {
                        style.addSource(
                            GeoJsonSource(
                                LINE_SOURCE,
                                FeatureCollection.fromFeatures(emptyList()),
                            ),
                        )
                        style.addSource(GeoJsonSource(TRANSMITTER_SOURCE))
                        style.addSource(GeoJsonSource(RECEIVER_SOURCE))
                        style.addLayer(
                            LineLayer(LINE_LAYER, LINE_SOURCE).withProperties(
                                lineColor("#7FDBFF"),
                                lineWidth(4f),
                                lineDasharray(arrayOf(2f, 2f)),
                            ),
                        )
                        style.addLayer(
                            CircleLayer(TRANSMITTER_LAYER, TRANSMITTER_SOURCE).withProperties(
                                circleColor("#00AEEF"),
                                circleRadius(9f),
                                circleStrokeColor("#002B40"),
                                circleStrokeWidth(3f),
                            ),
                        )
                        style.addLayer(
                            CircleLayer(RECEIVER_LAYER, RECEIVER_SOURCE).withProperties(
                                circleColor("#FF5252"),
                                circleRadius(9f),
                                circleStrokeColor("#4A0000"),
                                circleStrokeWidth(3f),
                            ),
                        )
                    }
                    updateSources(style)
                }

                if (map.style == null && styleRequested.compareAndSet(false, true)) {
                    map.setStyle(Style.Builder().fromUri(DISTANCE_MAP_STYLE), ::configureStyle)
                } else {
                    map.style?.let(::configureStyle)
                }
            }
        },
    )
}

private const val DISTANCE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val CLOSE_DISTANCE_METERS = 100.0
private const val CLOSE_DISTANCE_ZOOM = 17.0
private const val LINE_SOURCE = "findme-distance-line-source"
private const val TRANSMITTER_SOURCE = "findme-distance-transmitter-source"
private const val RECEIVER_SOURCE = "findme-distance-receiver-source"
private const val LINE_LAYER = "findme-distance-line-layer"
private const val TRANSMITTER_LAYER = "findme-distance-transmitter-layer"
private const val RECEIVER_LAYER = "findme-distance-receiver-layer"
