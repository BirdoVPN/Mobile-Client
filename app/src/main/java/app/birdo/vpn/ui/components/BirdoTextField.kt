package app.birdo.vpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.ui.theme.*

/**
 * Labeled glass-styled text field used across login, settings, and DNS
 * customization screens for visual consistency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirdoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle? = null,
) {
    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                color = BirdoWhite60,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp),
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(placeholder, color = BirdoWhite20, fontSize = 14.sp)
            },
            leadingIcon = leadingIcon?.let { icon ->
                {
                    Icon(icon, contentDescription = null, tint = BirdoWhite40, modifier = Modifier.size(18.dp))
                }
            },
            trailingIcon = trailingIcon,
            singleLine = singleLine,
            isError = isError,
            enabled = enabled,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = keyboardActions,
            textStyle = textStyle ?: TextStyle(fontSize = 14.sp, color = Color.White),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BirdoBrand.PurpleSoft.copy(alpha = 0.6f),
                unfocusedBorderColor = BirdoBrand.HairlineSoft,
                focusedTextColor = Color.White,
                unfocusedTextColor = BirdoWhite80,
                cursorColor = BirdoBrand.PurpleSoft,
                focusedContainerColor = GlassInput,
                unfocusedContainerColor = GlassInput,
                disabledContainerColor = GlassInput,
                disabledBorderColor = BirdoWhite05,
                disabledTextColor = BirdoWhite40,
                errorBorderColor = BirdoRed,
                errorContainerColor = GlassInput,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
