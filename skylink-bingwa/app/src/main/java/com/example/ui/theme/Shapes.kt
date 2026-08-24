package com.example.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val TagShape = RoundedCornerShape(8.dp)
val FieldButtonShape = RoundedCornerShape(12.dp)
val CardShape = RoundedCornerShape(16.dp)
val PromotionStatusShape = RoundedCornerShape(20.dp)
val BottomSheetTopShape = RoundedCornerShape(
    topStart = CornerSize(24.dp),
    topEnd = CornerSize(24.dp),
    bottomStart = CornerSize(0.dp),
    bottomEnd = CornerSize(0.dp)
)

val Shapes = Shapes(
    small = TagShape,
    medium = FieldButtonShape,
    large = CardShape,
    extraLarge = PromotionStatusShape
)
