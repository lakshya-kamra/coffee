package com.laksh.coffee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.Num
import com.laksh.coffee.ui.theme.WellShape

/**
 * The app's text field: an eyebrow label, then a filled well with no idle border. Focus adds
 * a soft inkHigh edge; [error] and [warning] add a fault or attention edge plus a helper line
 * (a blank warning shows the edge only). Replaces every OutlinedTextField.
 *
 * [onFocusLost] fires only on a real focused-to-unfocused change, never on first composition.
 */
@Composable
fun FieldWell(
    label: String?,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    suffix: String? = null,
    prefix: String? = null,
    numeric: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePassword: (() -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    error: String? = null,
    warning: String? = null,
    helper: String? = null,
    onCanvas: Boolean = false,
    height: Dp = 52.dp,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    onFocusLost: (() -> Unit)? = null,
) {
    val colors = CoffeeTheme.colors
    var focused by remember { mutableStateOf(false) }
    // A plain holder, not state: it only exists to spot the true-to-false edge.
    val wasFocused = remember { booleanArrayOf(false) }
    val latestFocusLost by rememberUpdatedState(onFocusLost)

    val edge = when {
        error != null -> colors.fault
        warning != null -> colors.attention
        focused -> colors.inkHigh.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    val baseStyle = if (numeric) {
        Num.Inline.copy(fontSize = 16.sp)
    } else {
        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
    }
    val textStyle = baseStyle.copy(color = if (enabled) colors.inkHigh else colors.inkLow)
    val placeholderStyle = baseStyle.copy(color = colors.inkLow)
    val options = keyboardOptions ?: when {
        numeric -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
        isPassword -> KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
        else -> KeyboardOptions.Default
    }
    val mask = remember { PasswordVisualTransformation() }
    val transformation = if (isPassword && !passwordVisible) mask else VisualTransformation.None
    val cursor = remember(colors.crema) { SolidColor(colors.crema) }

    Column(modifier) {
        if (label != null) {
            EyebrowLabel(label)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (singleLine) height else Dimens.FieldMultiline)
                .clip(WellShape)
                .background(if (onCanvas) colors.surface1 else colors.surface2)
                // No border modifier at rest: a 0dp border still draws a 1px hairline.
                .then(if (edge.alpha > 0f) Modifier.border(1.5.dp, edge, WellShape) else Modifier)
                .padding(horizontal = 14.dp, vertical = if (singleLine) 0.dp else 14.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, tint = colors.inkLow, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            if (prefix != null) {
                Text(prefix, style = MaterialTheme.typography.bodyLarge, color = colors.inkLow)
                Spacer(Modifier.width(4.dp))
            }
            Box(
                Modifier.weight(1f),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state ->
                            val now = state.isFocused
                            if (wasFocused[0] && !now) latestFocusLost?.invoke()
                            wasFocused[0] = now
                            focused = now
                        },
                    enabled = enabled,
                    textStyle = textStyle,
                    keyboardOptions = options,
                    keyboardActions = keyboardActions,
                    singleLine = singleLine,
                    minLines = if (singleLine) 1 else minLines.coerceAtLeast(1),
                    visualTransformation = transformation,
                    cursorBrush = cursor,
                    // The placeholder lives inside the field so it merges into the field's own
                    // node: TalkBack says "Search coffee, recipe, notes, edit box", not just "edit box".
                    decorationBox = { inner ->
                        Box(
                            Modifier.fillMaxWidth(),
                            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                        ) {
                            if (value.isEmpty() && placeholder != null) {
                                Text(
                                    text = placeholder,
                                    style = placeholderStyle,
                                    maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            if (suffix != null) {
                Spacer(Modifier.width(8.dp))
                Text(suffix, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = colors.inkLow)
            }
            if (isPassword && onTogglePassword != null) {
                Spacer(Modifier.width(8.dp))
                IconWell(
                    icon = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (passwordVisible) "Hide" else "Show",
                    onClick = onTogglePassword,
                    size = 32.dp,
                    onCanvas = false,
                    iconSize = 18.dp,
                )
            }
            trailing?.invoke(this)
        }

        val message = error ?: warning ?: helper
        val messageColor = when {
            error != null -> colors.fault
            warning != null -> colors.attention
            else -> colors.inkLow
        }
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = messageColor,
            )
        }
    }
}
