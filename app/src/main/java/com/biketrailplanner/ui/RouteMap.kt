package com.biketrailplanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.MapShape
import com.biketrailplanner.domain.PlannedRoute
import com.biketrailplanner.domain.RideHit
import com.biketrailplanner.domain.RideTrack
import com.biketrailplanner.domain.RouteOverlay
import com.biketrailplanner.ui.theme.GradeColors

/**
 * D17: the in-app drawing surface. There is no Google Maps SDK in this app, so the route is drawn
 * on a Compose canvas: same colours, same grades, no key and no billing.
 *
 * docs/UI.md section 3.2: it fills the screen, and before a plan exists it shows a marker for the
 * PLANNING origin centred under a 40% dim. docs/LOCATION-CONTROL.md B3: that marker is the live
 * position whenever one is known (D22); the saved home is only the fallback when there is no fix, so
 * the caller decides what to pass in.
 */
@Composable
fun RouteMap(
    shape: MapShape?,
    origin: LatLon,
    modifier: Modifier = Modifier,
    showOriginWhenEmpty: Boolean = true,
    /** D60: a tap on the map. Null when the screen showing it does not want taps. */
    onTap: ((LatLon) -> Unit)? = null,
    /** D60: the node or street the rider just tapped, drawn on the map. */
    highlight: RideHit? = null,
    /** D59: the zoom buttons live on the screen; this is how they reach the one shared map. */
    controller: MapController? = null,
    /** D59: show the +/- buttons. On for the ride screen, where the map is the point. */
    showZoomControls: Boolean = false,
    /**
     * D53/D64: how much of the bottom of the frame the sheet covers, 0..1, so the camera can fit the
     * ride into the part of the map the rider can actually see.
     */
    coveredBottomFraction: Float = 0f,
) {
    var mapAvailable by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.surface)) {
        // D21: a real basemap (MapLibre + OpenFreeMap positron), always light, no key.
        BaseMap(
            shape = shape,
            origin = origin,
            modifier = Modifier.fillMaxSize(),
            onAvailability = { available -> mapAvailable = available },
            onTap = onTap,
            highlight = highlight,
            controller = controller,
            coveredBottomFraction = coveredBottomFraction,
        )
        if (shape == null) {
            if (!mapAvailable && showOriginWhenEmpty) {
                OriginMarker()
            }
            // A light scrim keeps the pill and the sheet readable over the map.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.18f)),
            )
            Text(
                MapConfig.ATTRIBUTION,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 80.dp)
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)),
            )
            return@Box
        }
        // The shape is drawn by the map itself (so it pans and zooms); the canvas is the fallback when
        // the basemap cannot load, and keeps the same colours.
        if (!mapAvailable) {
            when (shape) {
                is MapShape.Planned -> RouteCanvasOverlay(shape.route, origin, Modifier.fillMaxSize())
                is MapShape.Recorded -> RideCanvasOverlay(shape.ride.points, Modifier.fillMaxSize())
                // D64: a recording in progress is the same line, drawn from the fixes so far.
                is MapShape.Live -> RideCanvasOverlay(shape.points, Modifier.fillMaxSize())
            }
        }
        // D59: the zoom buttons. The rider asked to be able to zoom the recorded ride; the map already
        // pinches, but a phone in a bar mount, or an emulator, cannot.
        if (showZoomControls && controller != null) {
            ZoomControls(
                controller = controller,
                // Top-end, not centre-end: the sheet covers the middle and the bottom of the frame, and
                // a zoom button behind the sheet is a zoom button the rider does not have.
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 88.dp, end = 12.dp),
            )
        }
        Text(
            MapConfig.ATTRIBUTION,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 4.dp)
                .background(androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
        )
        if (!mapAvailable) {
            androidx.compose.material3.Text(
                "Map unavailable - showing the route only",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 72.dp, end = 16.dp)
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
            )
        }
        return@Box
    }
}

/** D59: the +/- buttons. Big enough to hit on a bar mount, and they never cover the sheet. */
@Composable
private fun ZoomControls(controller: MapController, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier) {
        FilledTonalButton(
            onClick = { controller.zoomIn() },
            modifier = Modifier.size(44.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("+", style = androidx.compose.material3.MaterialTheme.typography.titleLarge) }
        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = { controller.zoomOut() },
            modifier = Modifier.size(44.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("\u2212", style = androidx.compose.material3.MaterialTheme.typography.titleLarge) }
    }
}

/** D59: a recorded ride on a plain surface, for when the basemap cannot load. */
@Composable
private fun RideCanvasOverlay(points: List<LatLon>, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    Box(modifier) {
        val primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
        val bounds = RouteOverlay.bounds(points) ?: return@Box
        Canvas(Modifier.fillMaxSize()) {
            val (sw, ne) = bounds
            val midLat = Math.toRadians((sw.lat + ne.lat) / 2)
            val kx = kotlin.math.cos(midLat)
            val spanX = ((ne.lon - sw.lon) * kx).coerceAtLeast(1e-6)
            val spanY = (ne.lat - sw.lat).coerceAtLeast(1e-6)
            val pad = 96f
            val scale = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
            val offsetX = (size.width - spanX * scale) / 2
            val offsetY = (size.height - spanY * scale) / 2
            fun project(p: LatLon): Offset {
                val x = ((p.lon - sw.lon) * kx) * scale + offsetX
                val y = size.height - ((p.lat - sw.lat) * scale + offsetY)
                return Offset(x.toFloat(), y.toFloat())
            }
            val path = Path()
            val first = project(points.first())
            path.moveTo(first.x, first.y)
            points.drop(1).forEach { val o = project(it); path.lineTo(o.x, o.y) }
            drawPath(path = path, color = primary, style = Stroke(width = 12f, cap = StrokeCap.Round))
            drawCircle(primary, radius = 14f, center = first)
            drawCircle(GradeColors.d, radius = 14f, center = project(points.last()))
        }
    }
}

/** The grade-coloured route on a plain surface: the fallback when the basemap cannot load. */
@Composable
private fun RouteCanvasOverlay(route: PlannedRoute, origin: LatLon, modifier: Modifier = Modifier) {
    Box(modifier) {
        val primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
        val onPrimary = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
        val runs = RouteOverlay.build(route)
        val all = runs.flatMap { it.points } + origin + route.accessPoint.point + route.turnaround
        val bounds = RouteOverlay.bounds(all) ?: return@Box
        Canvas(Modifier.fillMaxSize()) {
            val (sw, ne) = bounds
            val midLat = Math.toRadians((sw.lat + ne.lat) / 2)
            val kx = kotlin.math.cos(midLat)
            val spanX = ((ne.lon - sw.lon) * kx).coerceAtLeast(1e-6)
            val spanY = (ne.lat - sw.lat).coerceAtLeast(1e-6)
            val pad = 72f
            val scale = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
            val offsetX = (size.width - spanX * scale) / 2
            val offsetY = (size.height - spanY * scale) / 2
            fun project(p: LatLon): Offset {
                val x = ((p.lon - sw.lon) * kx) * scale + offsetX
                val y = size.height - ((p.lat - sw.lat) * scale + offsetY)
                return Offset(x.toFloat(), y.toFloat())
            }
            runs.forEach { run ->
                if (run.points.size < 2) return@forEach
                val path = Path()
                val first = project(run.points.first())
                path.moveTo(first.x, first.y)
                run.points.drop(1).forEach { val o = project(it); path.lineTo(o.x, o.y) }
                drawPath(
                    path = path,
                    color = gradeColor(run.grade),
                    style = Stroke(
                        width = 12f,
                        cap = StrokeCap.Round,
                        pathEffect = if (run.grade == Grade.UNGRADED) PathEffect.dashPathEffect(floatArrayOf(22f, 14f)) else null,
                    ),
                )
            }
            drawCircle(primary, radius = 16f, center = project(origin))
            drawCircle(onPrimary, radius = 8f, center = project(origin))
            drawCircle(GradeColors.a, radius = 14f, center = project(route.accessPoint.point))
            drawCircle(GradeColors.d, radius = 14f, center = project(route.turnaround))
        }
    }
}

@Composable
private fun OriginMarker() {
    val primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val onPrimary = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(primary),
        )
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(onPrimary),
        )
    }
}

