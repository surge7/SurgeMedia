package com.surgemusic.app.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** CyberDeck shapes — chamfered (cut) corners, asymmetric topStart/bottomEnd. */
val CyberShapes = Shapes(
    small = CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp),
    medium = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
    large = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp),
)
