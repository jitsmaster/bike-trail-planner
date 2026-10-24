package com.biketrailplanner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * ODbL attribution is mandatory: "© OpenStreetMap contributors" must appear anywhere route or
 * trail data is shown (docs/SPEC.md section 9, docs/DATA-LICENSES.md).
 */
@Composable
fun OsmAttribution(modifier: Modifier = Modifier) {
    Text(
        "© OpenStreetMap contributors (ODbL) | traffic counts: WSDOT / Pierce County",
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier,
    )
}
