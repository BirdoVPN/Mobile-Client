package app.birdo.vpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.ui.theme.*

/**
 * Settings list row with leading icon, title, optional subtitle, and trailing
 * content (toggle, value text, chevron).
 */
@Composable
fun BirdoListItem(
    title: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    leadingTint: Color = BirdoWhite80,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(BirdoRadii.md)
            .then(
                if (onClick != null && enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(BirdoWhite05),
                contentAlignment = Alignment.Center,
            ) {
                Icon(leadingIcon, contentDescription = null, tint = leadingTint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) Color.White else BirdoWhite40,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = BirdoWhite60,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Toggle row built on top of BirdoListItem. */
@Composable
fun BirdoToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    leadingTint: Color = BirdoWhite80,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    BirdoListItem(
        title = title,
        modifier = modifier,
        leadingIcon = leadingIcon,
        leadingTint = leadingTint,
        subtitle = subtitle,
        enabled = enabled,
        onClick = { if (enabled) onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = { if (enabled) onCheckedChange(it) },
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = BirdoBrand.Purple,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = BirdoWhite60,
                    uncheckedTrackColor = BirdoWhite10,
                    uncheckedBorderColor = BirdoBrand.HairlineSoft,
                    disabledCheckedThumbColor = BirdoWhite40,
                    disabledCheckedTrackColor = BirdoWhite10,
                    disabledUncheckedThumbColor = BirdoWhite20,
                    disabledUncheckedTrackColor = BirdoWhite05,
                ),
            )
        },
    )
}

/** Compact navigable row with chevron trailing. */
@Composable
fun BirdoNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    leadingTint: Color = BirdoWhite80,
    subtitle: String? = null,
    valueText: String? = null,
    enabled: Boolean = true,
) {
    BirdoListItem(
        title = title,
        modifier = modifier,
        leadingIcon = leadingIcon,
        leadingTint = leadingTint,
        subtitle = subtitle,
        enabled = enabled,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (valueText != null) {
                    Text(
                        text = valueText,
                        color = BirdoWhite60,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    androidx.compose.material.icons.Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = BirdoWhite40,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}

