package com.biketrailplanner.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.PlannedRoute
import com.biketrailplanner.ui.theme.GradeColors

fun gradeColor(grade: Grade): Color = when (grade) {
    Grade.A -> GradeColors.a
    Grade.B -> GradeColors.b
    Grade.C -> GradeColors.c
    Grade.D -> GradeColors.d
    Grade.E -> GradeColors.e
    Grade.UNGRADED -> GradeColors.notMeasured
    Grade.REJECTED -> GradeColors.e
}

/** docs/UI.md section 3.1: the Google-Maps-style top pill. */
@Composable
fun TopPill(title: String, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            trailing()
        }
    }
}

/** docs/UI.md section 3.4: the bottom sheet with a drag handle. */
@Composable
fun BottomSheet(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    /**
     * D53: when set, the handle becomes a control. The callback gets the drag in pixels, positive
     * downwards, so the caller can resize itself to reveal more map.
     */
    onHandleDrag: ((Float) -> Unit)? = null,
    /** A drag finished: the caller snaps to a detent and remembers it. */
    onHandleDragEnd: (() -> Unit)? = null,
    /** A tap on the handle: step to the next preset size. */
    onHandleTap: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = com.biketrailplanner.ui.theme.SheetShape,
        color = color,
        shadowElevation = 3.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            // The handle doubles as the resize control (D53). It stays the same 32x4.dp bar the rest of
            // the app draws, so a panel that cannot be resized looks exactly like one that can.
            var handle = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp)
                .heightIn(min = if (onHandleDrag != null || onHandleTap != null) 32.dp else 0.dp)
            if (onHandleDrag != null) {
                handle = handle.pointerInput(onHandleDrag) {
                    detectVerticalDragGestures(
                        onDragEnd = { onHandleDragEnd?.invoke() },
                        onVerticalDrag = { _, dragAmount -> onHandleDrag(dragAmount) },
                    )
                }
            }
            if (onHandleTap != null) {
                handle = handle.pointerInput(onHandleTap) {
                    detectTapGestures(onTap = { onHandleTap() })
                }
            }
            Box(handle, contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outline),
                )
            }
            content()
        }
    }
}

/** A big number with a small label, as on the sheet. */
@Composable
fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
    }
}

/**
 * docs/UI.md section 4.2: one segmented bar, segments proportional to distance and coloured by
 * grade, 8.dp tall, animated when a plan arrives.
 */
@Composable
fun SegmentedGradeBar(route: PlannedRoute, modifier: Modifier = Modifier) {
    val total = route.breakdown.totalM.takeIf { it > 0.0 } ?: 1.0
    val grades = listOf(Grade.A, Grade.B, Grade.C, Grade.D, Grade.E, Grade.UNGRADED, Grade.REJECTED)
    val fractions = grades.map { (route.breakdown.metres(it) / total).toFloat() }
    val animated by animateFloatAsState(targetValue = 1f, animationSpec = tween(300), label = "bar")
    Row(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.outline),
    ) {
        grades.forEachIndexed { index, grade ->
            val fraction = fractions[index]
            if (fraction <= 0.001f) return@forEachIndexed
            Box(
                Modifier
                    .weight(fraction * animated)
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(gradeColor(grade)),
            )
        }
    }
}

/** A single row of the sheet: label on the left, value on the right. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The connection dot used in Settings (docs/UI.md section 1). */
@Composable
fun ConnectionDot(color: Color) {
    Spacer(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun AppCard(modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.surface, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(16.dp),
    ) { content() }
}

/**
 * D51 (docs/RIDE-RECORDER.md): the visible half of the recorder. A recording must never be a secret —
 * the rider can always see it is happening, stop it, and reach the rides it has saved. It is drawn on
 * every screen, so it reads as one app rather than a settings toggle the rider has to remember.
 */
@Composable
fun RideRecordingBanner(
    onStop: () -> Unit,
    onOpenRides: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Recording your ride", style = MaterialTheme.typography.titleSmall)
            Text(
                "The app is saving the route you actually ride, whatever streets you take.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onStop, modifier = Modifier.height(40.dp)) { Text("Stop recording") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onOpenRides) { Text("Past rides") }
            }
        }
    }
}

/**
 * D35 (docs/VELOTRIC-LINK.md option A): the on-return reminder, shown above whatever screen the
 * rider is on. Option A never presses a Velotric button, so the only thing the app can do is open the
 * Velotric app and let the rider stop the ride there. It removes the memory problem, not the tap.
 */
@Composable
fun VelotricNudgeBar(onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("You're back at your start.", style = MaterialTheme.typography.titleSmall)
            Text(
                "If your Velotric ride is still recording, open the Velotric app to stop it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOpen, modifier = Modifier.height(40.dp)) { Text("Open Velotric") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}
