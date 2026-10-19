package com.surgemusic.app.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Surge shapes — chamfered (cut) corners, asymmetric topStart/bottomEnd. */
val SurgeShapes = Shapes(
    small = CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp),
    medium = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
    large = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp),
)
