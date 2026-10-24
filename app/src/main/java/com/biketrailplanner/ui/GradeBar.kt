package com.biketrailplanner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.biketrailplanner.domain.Formatting
import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.PlannedRoute

/**
 * docs/UI.md section 4.2/4.3: one bar plus a collapsed Details expander that lists each grade in
 * words and each unmeasured street with its traffic count.
 */
@Composable
fun GradeSummary(route: PlannedRoute, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        SegmentedGradeBar(route)
        Spacer(Modifier.height(6.dp))
        Text(
            RiderText.measuredCaption(route),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { expanded = !expanded }) { Text("Details") }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth()) {
                listOf(Grade.A, Grade.B, Grade.C, Grade.D, Grade.E).forEach { grade ->
                    val metres = route.breakdown.metres(grade)
                    if (metres > 1.0) {
                        Text(RiderText.gradeRow(grade, metres), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val unmeasured = route.breakdown.metres(Grade.UNGRADED)
                if (unmeasured > 1.0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        RiderText.gradeRow(Grade.UNGRADED, unmeasured),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    route.ungraded.take(6).forEach {
                        Text(RiderText.streetRow(it), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        RiderText.NOT_MEASURED_EXPLANATION,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (route.cautions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Take care on", style = MaterialTheme.typography.titleMedium)
                    route.cautions.take(5).forEach {
                        Text(RiderText.streetRow(it), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
