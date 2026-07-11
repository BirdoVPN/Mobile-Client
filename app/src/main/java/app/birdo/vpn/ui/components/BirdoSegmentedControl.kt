package app.birdo.vpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.ui.theme.BirdoColors
import app.birdo.vpn.ui.theme.BirdoMotion

/**
 * Canonical "pick one of N" control: raised track, accent-tinted selected
 * segment, accent border. Selection changes animate; each segment carries
 * radio-button semantics so TalkBack announces the current choice.
 */
@Composable
fun BirdoSegmentedControl(
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BirdoColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surfaceRaised)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (key, label) ->
            val isSelected = selectedKey == key
            val segmentColor by animateColorAsState(
                targetValue = if (isSelected) {
                    palette.accent.copy(alpha = if (palette.isLight) 0.18f else 0.22f)
                } else {
                    Color.Transparent
                },
                animationSpec = tween(BirdoMotion.Quick, easing = BirdoMotion.EaseStandard),
                label = "segment",
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) palette.accent else palette.onSurfaceMuted,
                animationSpec = tween(BirdoMotion.Quick, easing = BirdoMotion.EaseStandard),
                label = "segmentText",
            )
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(9.dp),
                color = segmentColor,
                border = if (isSelected) BorderStroke(1.dp, palette.accent.copy(alpha = 0.55f)) else null,
            ) {
                Text(
                    text = label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelect(key) },
                        )
                        .wrapContentHeight(Alignment.CenterVertically),
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = textColor,
                )
            }
        }
    }
}
