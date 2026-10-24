package com.biketrailplanner.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * docs/UI.md section 2 and D19: the app is ALWAYS light. It is ridden in daylight, so there is no
 * runtime theme branching at all - the light scheme below is passed to MaterialTheme
 * unconditionally, and the Android theme forbids platform auto-darkening
 * (`android:forceDarkAllowed="false"`). The dark values stay documented in docs/UI.md only.
 */
object MapsPalette {
    // light, used at runtime
    val primary = Color(0xFF1A73E8)
    val surface = Color(0xFFFFFFFF)
    val surfaceVariant = Color(0xFFF1F3F4)
    val onSurface = Color(0xFF202124)
    val onSurfaceVariant = Color(0xFF5F6368)
    val outline = Color(0xFFDADCE0)
    val primaryContainer = Color(0xFFD2E3FC)
    val onPrimaryContainer = Color(0xFF174EA6)

    // documented for a future dark mode (docs/UI.md section 2); NOT wired to the runtime
    val darkPrimary = Color(0xFF8AB4F8)
    val darkSurface = Color(0xFF202124)
    val darkSurfaceVariant = Color(0xFF303134)
    val darkOnSurface = Color(0xFFE8EAED)
    val darkOnSurfaceVariant = Color(0xFF9AA0A6)
    val darkOutline = Color(0xFF5F6368)
}

/** Grade colours: one set, used by the bar, the chips and the map. */
object GradeColors {
    val a = Color(0xFF188038)
    val b = Color(0xFF34A853)
    val c = Color(0xFFF9AB00)
    val d = Color(0xFFEA8600)
    val e = Color(0xFFD93025)
    val notMeasured = Color(0xFF9AA0A6)
}

private val LightColors = lightColorScheme(
    primary = MapsPalette.primary,
    onPrimary = Color.White,
    primaryContainer = MapsPalette.primaryContainer,
    onPrimaryContainer = MapsPalette.onPrimaryContainer,
    secondary = MapsPalette.primary,
    onSecondary = Color.White,
    background = MapsPalette.surface,
    onBackground = MapsPalette.onSurface,
    surface = MapsPalette.surface,
    onSurface = MapsPalette.onSurface,
    surfaceVariant = MapsPalette.surfaceVariant,
    onSurfaceVariant = MapsPalette.onSurfaceVariant,
    outline = MapsPalette.outline,
    error = GradeColors.e,
    onError = Color.White,
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.Normal),
        titleLarge = base.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
        displaySmall = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Medium),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp),
        labelMedium = base.labelMedium.copy(fontSize = 12.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** D19: no `darkTheme` parameter. The app is light, always. */
@Composable
fun BikeTrailPlannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** docs/UI.md section 2: bottom sheet corners. */
val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
