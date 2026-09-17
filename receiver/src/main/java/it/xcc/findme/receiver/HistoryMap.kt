package it.xcc.findme.receiver

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
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
import it.xcc.findme.core.LocationHistoryPoint
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
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

@Composable
fun HistoryMap(
    points: List<LocationHistoryPoint>,
    selectedIndex: Int,
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
    val lastRouteKey = remember(mapView) { AtomicReference<String?>(null) }

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
                .height(300.dp)
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
                fun updateSources(style: Style) {
                    val safeIndex = selectedIndex.coerceIn(0..points.lastIndex.coerceAtLeast(0))
                    val visiblePoints = points.take(safeIndex + 1)
                    val routePoints = visiblePoints.map {
                        Point.fromLngLat(it.longitude, it.latitude)
                    }
                    style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)
                        ?.setGeoJson(
                            FeatureCollection.fromFeatures(
                                if (routePoints.size >= 2) {
                                    listOf(Feature.fromGeometry(LineString.fromLngLats(routePoints)))
                                } else {
                                    emptyList()
                                },
                            ),
                        )
                    style.getSourceAs<GeoJsonSource>(START_SOURCE)
                        ?.setGeoJson(points.firstOrNull()?.toFeatureCollection())
                    style.getSourceAs<GeoJsonSource>(END_SOURCE)
                        ?.setGeoJson(points.lastOrNull()?.toFeatureCollection())
                    val selected = points.getOrNull(selectedIndex)
                    style.getSourceAs<GeoJsonSource>(CURRENT_SOURCE)
                        ?.setGeoJson(selected.toFeatureCollection())

                    val routeKey = points.routeKey()
                    if (points.isNotEmpty() && lastRouteKey.getAndSet(routeKey) != routeKey) {
                        val bounds = LatLngBounds.Builder()
                            .includes(points.map { LatLng(it.latitude, it.longitude) })
                            .build()
                        map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 48), 500)
                    } else if (selected != null) {
                        map.easeCamera(
                            CameraUpdateFactory.newLatLng(
                                LatLng(selected.latitude, selected.longitude),
                            ),
                            250,
                        )
                    }
                }

                fun configureStyle(style: Style) {
                    if (style.getSource(ROUTE_SOURCE) == null) {
                        style.addSource(GeoJsonSource(ROUTE_SOURCE, FeatureCollection.fromFeatures(emptyList())))
                        style.addSource(GeoJsonSource(START_SOURCE, FeatureCollection.fromFeatures(emptyList())))
                        style.addSource(GeoJsonSource(END_SOURCE, FeatureCollection.fromFeatures(emptyList())))
                        style.addSource(GeoJsonSource(CURRENT_SOURCE, FeatureCollection.fromFeatures(emptyList())))
                        style.addLayer(
                            LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                                lineColor("#00AEEF"),
                                lineWidth(5f),
                            ),
                        )
                        style.addLayer(
                            CircleLayer(START_LAYER, START_SOURCE).withProperties(
                                circleColor("#2ECC71"),
                                circleRadius(7f),
                            ),
                        )
                        style.addLayer(
                            CircleLayer(END_LAYER, END_SOURCE).withProperties(
                                circleColor("#FF5252"),
                                circleRadius(7f),
                            ),
                        )
                        style.addLayer(
                            CircleLayer(CURRENT_LAYER, CURRENT_SOURCE).withProperties(
                                circleColor("#FFC107"),
                                circleRadius(8f),
                                circleStrokeColor("#3E2723"),
                                circleStrokeWidth(3f),
                            ),
                        )
                    }
                    updateSources(style)
                }

                val style = map.style
                if (style == null && styleRequested.compareAndSet(false, true)) {
                    map.setStyle(Style.Builder().fromUri(HISTORY_MAP_STYLE), ::configureStyle)
                } else if (style != null) {
                    configureStyle(style)
                }
            }
        },
    )
}

private fun LocationHistoryPoint?.toFeatureCollection(): FeatureCollection =
    FeatureCollection.fromFeatures(
        this?.let {
            listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)))
        } ?: emptyList(),
    )

private fun List<LocationHistoryPoint>.routeKey(): String? =
    if (isEmpty()) null else "${first().id}:${last().id}:$size"

private const val HISTORY_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val ROUTE_SOURCE = "findme-history-route-source"
private const val START_SOURCE = "findme-history-start-source"
private const val END_SOURCE = "findme-history-end-source"
private const val CURRENT_SOURCE = "findme-history-current-source"
private const val ROUTE_LAYER = "findme-history-route-layer"
private const val START_LAYER = "findme-history-start-layer"
private const val END_LAYER = "findme-history-end-layer"
private const val CURRENT_LAYER = "findme-history-current-layer"
