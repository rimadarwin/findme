package it.xcc.findme.receiver

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import it.xcc.findme.core.DeviceLocation
import java.util.concurrent.atomic.AtomicBoolean
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@Composable
fun DeviceMap(
    deviceName: String,
    location: DeviceLocation,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    val styleRequested = remember(mapView) { AtomicBoolean(false) }

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
        modifier = modifier
            .fillMaxWidth()
            .height(320.dp),
        factory = { mapView },
        update = { view ->
            val point = LatLng(location.latitude, location.longitude)
            view.getMapAsync { map ->
                fun updateDevice() {
                    map.clear()
                    map.addMarker(MarkerOptions().position(point).title(deviceName))
                    map.cameraPosition = CameraPosition.Builder()
                        .target(point)
                        .zoom(16.0)
                        .build()
                }
                if (map.style == null && styleRequested.compareAndSet(false, true)) {
                    map.setStyle(Style.Builder().fromUri(OPEN_FREE_MAP_STYLE)) {
                        updateDevice()
                    }
                } else if (map.style != null) {
                    updateDevice()
                }
            }
        },
    )
}

private const val OPEN_FREE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
