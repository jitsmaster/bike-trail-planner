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
    /**
     * D53/D64: what the sheet covers at the bottom of the frame, 0..1.
     *
     * The map is full-screen behind the sheet, so fitting a shape into the whole view puts most of it
     * behind the sheet: measured on the emulator on 2026-10-25, selecting the 19.5 km ride showed a
     * strip of it at the sheet's edge and nothing else, because a fixed 720 px of bottom padding was
     * less than the sheet's 1093 px. The padding now follows the sheet the rider is actually looking at.
     */
    coveredBottomFraction: Float = 0f,
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

    // D64: what the map is showing, as a STABLE key.
    //
    // A live recording changes identity on every fix, so keying the draw effect on `shape` itself
    // would clear and re-fit the camera once a second - the D60 bug again (every tap re-ran
    // `fitToShape` and threw away the rider's zoom). The full redraw happens when the KIND of shape
    // changes; the live growth is handled separately, below, by an effect that never moves the camera
    // unless the rider has ridden off the screen.
    val shapeKey: String? = when (shape) {
        null -> null
        is MapShape.Planned -> "planned:" + shape.route.signature
        is MapShape.Recorded -> "recorded:" + shape.ride.signature
        is MapShape.Live -> "live"
    }
    val livePoints = (shape as? MapShape.Live)?.points
    // D53/D64: how much of the map's own height the sheet covers, in pixels, read at fit time because
    // the view has no size until it has been laid out.
    fun fitPadding(): IntArray = paddings(mapView?.height ?: 0, coveredBottomFraction)
    var livePolyline by remember { mutableStateOf<org.maplibre.android.annotations.Polyline?>(null) }

    LaunchedEffect(map, styleReady, shapeKey, rideColor) {
        val instance = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        instance.clear()
        livePolyline = null
        if (shape == null) {
            instance.cameraPosition = CameraPosition.Builder()
                .target(LatLng(origin.lat, origin.lon))
                .zoom(MapConfig.DEFAULT_ZOOM)
                .build()
        } else if (shape is MapShape.Live) {
            // The first full draw of a recording happens here; every later fix only extends the line.
            if (shape.points.size >= 2) {
                livePolyline = drawLiveLine(instance, shape.points, rideColor)
                fitToShape(instance, shape, origin, fitPadding())
            }
        } else {
            drawShape(instance, shape, rideColor)
            fitToShape(instance, shape, origin, fitPadding())
        }
    }

    // D64: extend the live line as fixes arrive. Never re-fits while the newest fix is on screen, so
    // the rider watches the trip draw itself instead of watching the camera zoom out every second.
    LaunchedEffect(map, styleReady, livePoints?.size ?: -1) {
        val instance = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val points = livePoints ?: return@LaunchedEffect
        if (points.size < 2) return@LaunchedEffect
        livePolyline?.remove()
        livePolyline = drawLiveLine(instance, points, rideColor)
        followIfOffScreen(instance, points, fitPadding())
    }

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

    // D60: the marker for what the rider just tapped.
    //
    // Deliberately a SEPARATE effect from the one above, keyed on the highlight rather than on the
    // shape. Sharing one effect meant every tap re-ran `fitToShape`, so the camera snapped back to the
    // whole route each time and threw away the zoom and pan the rider had just made - measured on the
    // emulator, where three taps 165 m apart all answered with the same point because each tap had
    // re-centred the map under the last one.
    var highlightMarker by remember { mutableStateOf<org.maplibre.android.annotations.Marker?>(null) }
    LaunchedEffect(map, styleReady, shapeKey, highlight) {
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
        is MapShape.Live -> drawLiveLine(map, shape.points, rideColor)
    }
}

/**
 * D64: the line of a recording in progress.
 *
 * Same colour as a stored ride, because it is the same thing - a ride, not a graded plan. Grade colours
 * are never used here: nothing has been traced or graded yet, so colouring the line would be a claim
 * about metres that have not been assessed.
 */
private fun drawLiveLine(
    map: MapLibreMap,
    points: List<LatLon>,
    color: androidx.compose.ui.graphics.Color,
): org.maplibre.android.annotations.Polyline? {
    if (points.size < 2) return null
    return map.addPolyline(
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

/**
 * D53/D64: the four paddings, in pixels, that keep a fitted shape in the part of the map the sheet
 * does not cover.
 *
 * The bottom padding is the sheet the rider actually has, not a constant: a sheet at 40% of a 3088 px
 * screen is 1235 px, and the fixed 720 this used to add left the shape centred behind it - measured on
 * 2026-10-25, where selecting the 19.5 km ride showed a strip of it at the sheet's edge and nothing
 * else. Clamped at 0.85, because a full-height sheet leaves no map to fit into.
 */
private fun paddings(viewHeightPx: Int, coveredBottomFraction: Float): IntArray {
    val height = viewHeightPx.takeIf { it > 0 } ?: 2400
    val bottom = (height * coveredBottomFraction.coerceIn(0f, 0.85f)).toInt() + BOTTOM_PADDING_PX
    return intArrayOf(SIDE_PADDING_PX, TOP_PADDING_PX, SIDE_PADDING_PX, bottom)
}

private const val SIDE_PADDING_PX = 80
private const val TOP_PADDING_PX = 220

/** Air under the shape on top of the sheet, so it does not touch the sheet's edge. */
private const val BOTTOM_PADDING_PX = 160

/**
 * D64: move the camera only when the newest fix has left the screen.
 *
 * A recording is redrawn once a second. Re-fitting on every fix would zoom out a little on every fix
 * and make the map unreadable; never moving would let the rider's line walk off the screen. "Re-fit
 * only when it needs to" is the middle: the camera holds still while the rider is in view, then frames
 * the whole ride again when they leave it.
 */
private fun followIfOffScreen(map: MapLibreMap, points: List<LatLon>, padding: IntArray) {
    val newest = points.lastOrNull() ?: return
    val visible = runCatching { map.projection.visibleRegion.latLngBounds }.getOrNull() ?: return
    val latSpan = visible.latitudeNorth - visible.latitudeSouth
    val lonSpan = visible.longitudeEast - visible.longitudeWest
    val margin = 0.15
    val inside = newest.lat > visible.latitudeSouth + margin * latSpan &&
        newest.lat < visible.latitudeNorth - margin * latSpan &&
        newest.lon > visible.longitudeWest + margin * lonSpan &&
        newest.lon < visible.longitudeEast - margin * lonSpan
    if (inside) return
    val bounds = RouteOverlay.bounds(points) ?: return
    val (sw, ne) = bounds
    if (Geo.distanceM(sw, ne) < 50.0) return
    runCatching {
        map.moveCamera(
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.from(ne.lat, ne.lon, sw.lat, sw.lon),
                padding[0], padding[1], padding[2], padding[3],
            ),
        )
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
private fun fitToShape(
    map: MapLibreMap,
    shape: MapShape,
    fallback: LatLon,
    padding: IntArray = paddings(0, 0f),
) {
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
        is MapShape.Live -> shape.points
    }
    val bounds = RouteOverlay.bounds(points) ?: return
    val (sw, ne) = bounds
    if (Geo.distanceM(sw, ne) < 50.0) return
    val latLngBounds = LatLngBounds.from(ne.lat, ne.lon, sw.lat, sw.lon)
    runCatching {
        map.moveCamera(
            CameraUpdateFactory.newLatLngBounds(latLngBounds, padding[0], padding[1], padding[2], padding[3]),
        )
    }
}
