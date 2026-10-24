package com.biketrailplanner.ui

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.MapShape
import com.biketrailplanner.domain.PlannedRoute
import com.biketrailplanner.domain.RideHit
import com.biketrailplanner.domain.RideTrack
import com.biketrailplanner.domain.RouteOverlay
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions

/**
 * D21: a real basemap behind the route, with no key and no billing (D17).
 *
 * MapLibre GL (org.maplibre.gl:android-sdk) renders the OpenFreeMap "positron" style: light grey,
 * the closest free equivalent of the Google Maps look, and always light, so the map cannot flip dark
 * when the phone is in night mode (D19).
 *
 * The map is orientation only. If the style or the tiles fail to load, the caller draws the route
 * over a plain light background with a small note. Planning, grading and the hand-off never depend
 * on it. All map work happens once per (map, style, route) change, never on every recomposition.
 */
object MapConfig {
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
    const val ATTRIBUTION = "\u00a9 OpenStreetMap contributors \u00b7 OpenFreeMap"
    const val DEFAULT_ZOOM = 13.0
}

/**
 * D59/D60: lets a screen drive the one shared map without owning it.
 *
 * The `MapView` lives in `MainActivity` and must not be recreated per screen (D21), so a screen that
 * wants zoom buttons cannot hold the map itself. It holds this instead; [BaseMap] binds the real map
 * when the style is ready and unbinds it on the way out.
 */
class MapController {
    private var map: MapLibreMap? = null

    internal fun bind(instance: MapLibreMap?) {
        map = instance
    }

    val ready: Boolean get() = map != null

    fun zoomIn() {
        map?.let { runCatching { it.animateCamera(CameraUpdateFactory.zoomIn()) } }
    }

    fun zoomOut() {
        map?.let { runCatching { it.animateCamera(CameraUpdateFactory.zoomOut()) } }
    }
}

@Composable
fun BaseMap(
    shape: MapShape?,
    origin: LatLon,
    modifier: Modifier = Modifier,
    onAvailability: (Boolean) -> Unit,
    /** D60: a tap on the map, in coordinates. Null when the screen does not want taps. */
    onTap: ((LatLon) -> Unit)? = null,
    /** D60: the node or street the rider has just tapped, drawn so the answer has a place. */
    highlight: RideHit? = null,
    /** D59: the zoom buttons live on the screen; they drive the map through this. */
    controller: MapController? = null,
) {
    val context = LocalContext.current
    // D21 rule 5: the basemap must never be able to break the app.
    val mapView = remember {
        runCatching { MapView(context).apply { onCreate(Bundle()) } }.getOrNull()
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }

    val rideColor = MaterialTheme.colorScheme.primary

    DisposableEffect(Unit) {
        mapView?.onStart()
        mapView?.onResume()
        onDispose {
            mapView?.onPause()
            mapView?.onStop()
            mapView?.onDestroy()
            controller?.bind(null)
        }
    }

    if (mapView == null) {
        LaunchedEffect(Unit) { onAvailability(false) }
        return
    }

    AndroidView(
        factory = {
            mapView.apply {
                getMapAsync { instance ->
                    instance.uiSettings.isAttributionEnabled = true
                    instance.uiSettings.isLogoEnabled = false
                    instance.uiSettings.isCompassEnabled = false
                    instance.setStyle(Style.Builder().fromUri(MapConfig.STYLE_URL)) {
                        styleReady = true
                        onAvailability(true)
                        map = instance
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    )

    // if the style has not arrived after a few seconds, treat the basemap as unavailable
    LaunchedEffect(styleReady) {
        if (!styleReady) {
            kotlinx.coroutines.delay(7000)
            if (!styleReady) onAvailability(false)
        }
    }

    LaunchedEffect(map) { controller?.bind(map) }

    // D60: a tap on the map. Added and removed with the callback so a screen that stops wanting taps
    // stops getting them.
    DisposableEffect(map, onTap) {
        val instance = map
        val listener = onTap?.let { callback ->
            MapLibreMap.OnMapClickListener { latLng ->
                callback(LatLon(latLng.latitude, latLng.longitude))
                true
            }
        }
        if (instance != null && listener != null) instance.addOnMapClickListener(listener)
        onDispose { if (instance != null && listener != null) instance.removeOnMapClickListener(listener) }
    }

    LaunchedEffect(map, styleReady, shape, rideColor) {
        val instance = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        instance.clear()
        if (shape == null) {
            instance.cameraPosition = CameraPosition.Builder()
                .target(LatLng(origin.lat, origin.lon))
                .zoom(MapConfig.DEFAULT_ZOOM)
                .build()
        } else {
            drawShape(instance, shape, rideColor)
            fitToShape(instance, shape, origin)
        }
    }

    // D60: the marker for what the rider just tapped.
    //
    // Deliberately a SEPARATE effect from the one above, keyed on the highlight rather than on the
    // shape. Sharing one effect meant every tap re-ran `fitToShape`, so the camera snapped back to the
    // whole route each time and threw away the zoom and pan the rider had just made - measured on the
    // emulator, where three taps 165 m apart all answered with the same point because each tap had
    // re-centred the map under the last one.
    var highlightMarker by remember { mutableStateOf<org.maplibre.android.annotations.Marker?>(null) }
    LaunchedEffect(map, styleReady, shape, highlight) {
        val instance = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        highlightMarker?.remove()
        highlightMarker = highlight?.let { hit ->
            instance.addMarker(
                MarkerOptions()
                    .position(LatLng(hit.at.lat, hit.at.lon))
                    .title(if (hit is RideHit.Edge) hit.name else "Point on ride"),
            )
        }
    }
}

/** Long runs are sampled down: the emulator renders this in software, and 8 x 1 000 points stalls it. */
private const val MAX_POINTS_PER_RUN = 120

private fun simplify(points: List<LatLon>): List<LatLon> {
    if (points.size <= MAX_POINTS_PER_RUN) return points
    val step = points.size / MAX_POINTS_PER_RUN + 1
    return points.filterIndexed { index, _ -> index % step == 0 } + points.last()
}

private fun drawRoute(map: MapLibreMap, route: PlannedRoute) {
    RouteOverlay.build(route).forEach { run ->
        if (run.points.size < 2) return@forEach
        val color = gradeColor(run.grade)
        map.addPolyline(
            PolylineOptions()
                .addAll(simplify(run.points).map { LatLng(it.lat, it.lon) })
                .color(
                    android.graphics.Color.argb(
                        (color.alpha * 255).toInt(),
                        (color.red * 255).toInt(),
                        (color.green * 255).toInt(),
                        (color.blue * 255).toInt(),
                    ),
                )
                .width(8f),
        )
    }
    val start = route.commute.points.firstOrNull()
    if (start != null) {
        map.addMarker(MarkerOptions().position(LatLng(start.lat, start.lon)).title("Start"))
    }
    map.addMarker(
        MarkerOptions()
            .position(LatLng(route.accessPoint.point.lat, route.accessPoint.point.lon))
            .title("Trail entry"),
    )
    map.addMarker(MarkerOptions().position(LatLng(route.turnaround.lat, route.turnaround.lon)).title("Turnaround"))
}

private fun drawShape(map: MapLibreMap, shape: MapShape, rideColor: androidx.compose.ui.graphics.Color) {
    when (shape) {
        is MapShape.Planned -> drawRoute(map, shape.route)
        is MapShape.Recorded -> drawRide(map, shape.ride, rideColor)
    }
}

/**
 * D59: a recorded ride, drawn as ONE line.
 *
 * A ride stores its grades per traced street segment, not per GPS point, so a grade-coloured line would
 * be a claim the data cannot support - it would paint metres that were never assessed as if they had a
 * grade. The colours on this screen come from the card instead, which knows exactly how much was
 * measured.
 */
private fun drawRide(map: MapLibreMap, ride: RideTrack, color: androidx.compose.ui.graphics.Color) {
    val points = ride.points
    if (points.size >= 2) {
        map.addPolyline(
            PolylineOptions()
                .addAll(simplify(points).map { LatLng(it.lat, it.lon) })
                .color(
                    android.graphics.Color.argb(
                        (color.alpha * 255).toInt(),
                        (color.red * 255).toInt(),
                        (color.green * 255).toInt(),
                        (color.blue * 255).toInt(),
                    ),
                )
                .width(8f),
        )
    }
    points.firstOrNull()?.let {
        map.addMarker(MarkerOptions().position(LatLng(it.lat, it.lon)).title("Start"))
    }
    if (points.size >= 2) {
        val last = points.last()
        map.addMarker(MarkerOptions().position(LatLng(last.lat, last.lon)).title("End"))
    }
}

/** The ride the way [fitToRoute] treats a plan, plus the one case a plan never has: a single point. */
private fun fitToShape(map: MapLibreMap, shape: MapShape, fallback: LatLon) {
    if (shape is MapShape.Recorded && shape.ride.points.size < 2) {
        // A ride too short to draw still deserves to be looked at, so zoom in on the one fix there is.
        shape.ride.points.firstOrNull()?.let { only ->
            runCatching {
                map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(LatLng(only.lat, only.lon)).zoom(16.0).build(),
                    ),
                )
            }
        }
        return
    }
    val points = when (shape) {
        is MapShape.Planned ->
            RouteOverlay.build(shape.route).flatMap { it.points } + fallback + shape.route.accessPoint.point
        is MapShape.Recorded -> shape.ride.points
    }
    val bounds = RouteOverlay.bounds(points) ?: return
    val (sw, ne) = bounds
    if (Geo.distanceM(sw, ne) < 50.0) return
    val latLngBounds = LatLngBounds.from(ne.lat, ne.lon, sw.lat, sw.lon)
    runCatching {
        // move, do not animate: this runs on the main thread and the emulator renders in software
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, 80, 220, 80, 720))
    }
}
