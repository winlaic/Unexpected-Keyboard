package juloo.keyboard2

import android.content.Context
import android.os.Build
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import kotlin.math.roundToInt

private val UkLightColors = lightColorScheme(
  primary = Color(0xff46655f),
  onPrimary = Color.White,
  secondary = Color(0xff735b69),
  tertiary = Color(0xff6e5e36),
  surface = Color(0xfffbfcf8),
  surfaceVariant = Color(0xffe4ebe5),
  background = Color(0xfffbfcf8),
  outline = Color(0xff737970)
)

fun ComponentActivity.configureComposeSystemBars() {
  window.statusBarColor = android.graphics.Color.TRANSPARENT
  window.navigationBarColor = android.graphics.Color.WHITE
  WindowCompat.getInsetsController(window, window.decorView).apply {
    isAppearanceLightStatusBars = true
    isAppearanceLightNavigationBars = true
  }
}

@Composable
fun KeyboardAppTheme(content: @Composable () -> Unit) {
  val context = LocalContext.current
  val colors =
    if (Build.VERSION.SDK_INT >= 31) dynamicLightColorScheme(context)
    else UkLightColors
  MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
  title: String,
  canGoBack: Boolean = false,
  onBack: () -> Unit = {},
  actions: @Composable () -> Unit = {},
  content: @Composable (Modifier) -> Unit
) {
  Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
          if (canGoBack) {
            IconButton(onClick = onBack) {
              Icon(
                painter = painterResource(R.drawable.arrow_back_outline),
                contentDescription = android.R.string.cancel.asString()
              )
            }
          }
        },
        actions = { actions() }
      )
    }
  ) { padding ->
    Surface(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
      color = MaterialTheme.colorScheme.background
    ) {
      content(Modifier.fillMaxSize())
    }
  }
}

@Composable
fun SectionHeader(title: String) {
  Text(
    text = title,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 6.dp)
  )
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 4.dp),
    shape = RoundedCornerShape(8.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
  ) {
    content()
  }
}

@Composable
fun PreferenceRow(
  title: String,
  summary: String? = null,
  enabled: Boolean = true,
  trailing: @Composable (() -> Unit)? = null,
  onClick: (() -> Unit)? = null
) {
  val modifier = if (onClick != null) {
    Modifier.clickable(enabled = enabled) { onClick() }
  } else {
    Modifier
  }
  ListItem(
    headlineContent = {
      KeyAwareText(
        text = title,
        color = rowTextColor(enabled),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Normal
      )
    },
    supportingContent = summary?.let {
      {
        KeyAwareText(
          text = it,
          color = rowSubTextColor(enabled),
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Normal
        )
      }
    },
    trailingContent = trailing,
    modifier = modifier
  )
}

@Composable
fun KeyAwareText(
  text: String,
  color: Color,
  style: androidx.compose.ui.text.TextStyle,
  modifier: Modifier = Modifier,
  fontWeight: FontWeight? = null
) {
  if (!containsKeyFontGlyph(text)) {
    Text(text, color = color, style = style, fontWeight = fontWeight, modifier = modifier)
    return
  }
  val context = LocalContext.current
  val typeface = remember { Theme.getKeyFont(context) }
  val textSizeSp = style.fontSize.takeIf { it != TextUnit.Unspecified }
  Box(modifier = modifier) {
    AndroidView(
      factory = { ctx ->
        TextView(ctx).apply {
          this.typeface = typeface
        }
      },
      update = { view ->
        view.text = text
        view.setTextColor(color.toArgb())
        val sizeSp = if (textSizeSp != null) textSizeSp.value else 16f
        view.textSize = sizeSp
      }
    )
  }
}

private fun containsKeyFontGlyph(text: String): Boolean =
  text.any { ch -> ch.code in 0xE000..0xF8FF }

@Composable
fun SwitchRow(
  title: String,
  summary: String? = null,
  checked: Boolean,
  enabled: Boolean = true,
  onCheckedChange: (Boolean) -> Unit
) {
  PreferenceRow(
    title = title,
    summary = summary,
    enabled = enabled,
    trailing = {
      Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled
      )
    },
    onClick = { onCheckedChange(!checked) }
  )
}

@Composable
fun SliderRow(
  title: String,
  value: Number,
  min: Float,
  max: Float,
  step: Float,
  suffix: String,
  defaultValue: Float? = null,
  enabled: Boolean = true,
  onResetToDefault: (() -> Unit)? = null,
  onValueChangeFinished: (Float) -> Unit
) {
  var localValue by remember(value) { mutableStateOf(value.toFloat()) }
  val quantizedValue = quantize(localValue, min, max, step)
  val quantizedDefaultValue = defaultValue?.let { quantize(it, min, max, step) }
  val discreteValueCount = (((max - min) / step).roundToInt() + 1).coerceAtLeast(1)
  val useDiscreteSlider = discreteValueCount < 15
  val canReset = enabled && quantizedDefaultValue != null && kotlin.math.abs(quantizedValue - quantizedDefaultValue) > 0.0001f
  val displayed =
    if (step >= 1f) quantizedValue.roundToInt().toString()
    else "%.2f".format(quantizedValue)
  Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(title, color = rowTextColor(enabled), style = MaterialTheme.typography.bodyLarge)
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (onResetToDefault != null && quantizedDefaultValue != null) {
          IconButton(
            onClick = {
              localValue = quantizedDefaultValue
              onResetToDefault()
            },
            enabled = canReset
          ) {
            Icon(
              painter = painterResource(R.drawable.restore_default_outline),
              contentDescription = "Reset to default",
              tint = if (canReset) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
            )
          }
        }
        Text("$displayed$suffix", color = rowSubTextColor(enabled), style = MaterialTheme.typography.bodyMedium)
      }
    }
    Slider(
      value = localValue.coerceIn(min, max),
      valueRange = min..max,
      steps = if (useDiscreteSlider) (discreteValueCount - 2).coerceAtLeast(0) else 0,
      enabled = enabled,
      onValueChange = {
        val clamped = it.coerceIn(min, max)
        localValue = if (useDiscreteSlider) quantize(clamped, min, max, step) else clamped
      },
      onValueChangeFinished = {
        val snapped = quantize(localValue, min, max, step)
        localValue = snapped
        onValueChangeFinished(snapped)
      }
    )
  }
}

@Composable
fun ListPreferenceRow(
  title: String,
  value: String,
  entries: Array<String>,
  values: Array<String>,
  enabled: Boolean = true,
  onSelected: (String) -> Unit
) {
  var dialogOpen by remember { mutableStateOf(false) }
  val selectedLabel = entries.getOrNull(values.indexOf(value)) ?: value
  PreferenceRow(
    title = title,
    summary = selectedLabel,
    enabled = enabled,
    onClick = { dialogOpen = true }
  )
  if (dialogOpen) {
    AlertDialog(
      onDismissRequest = { dialogOpen = false },
      title = { Text(title) },
      text = {
        LazyColumn {
          itemsIndexed(values.toList()) { index, item ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  onSelected(item)
                  dialogOpen = false
                }
                .padding(vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              RadioButton(selected = item == value, onClick = null)
              Spacer(Modifier.width(12.dp))
              Text(entries.getOrElse(index) { item })
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { dialogOpen = false }) {
          Text(android.R.string.cancel.asString())
        }
      }
    )
  }
}

@Composable
fun PrimaryActionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Button(onClick = onClick, modifier = modifier) {
    Text(text)
  }
}

@Composable
fun DividerInset() {
  HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
}

@Composable
fun TextInputDialog(
  title: String,
  value: String,
  onValueChange: (String) -> Unit,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
      )
    },
    confirmButton = {
      TextButton(onClick = onConfirm) { Text(android.R.string.ok.asString()) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(android.R.string.cancel.asString()) }
    }
  )
}

@Composable
private fun rowTextColor(enabled: Boolean): Color =
  if (enabled) MaterialTheme.colorScheme.onSurface
  else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

@Composable
private fun rowSubTextColor(enabled: Boolean): Color =
  if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
  else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)

private fun quantize(value: Float, min: Float, max: Float, step: Float): Float {
  val steps = ((value - min) / step).roundToInt()
  return (min + steps * step).coerceIn(min, max)
}

@Composable
fun Int.asString(): String = LocalContext.current.getString(this)

fun Context.stringArray(id: Int): Array<String> = resources.getStringArray(id)
