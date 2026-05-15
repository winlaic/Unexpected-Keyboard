package juloo.keyboard2

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import juloo.keyboard2.prefs.ComposePreferenceBridge

class ComposeSettingsActivity : ComponentActivity() {
  private lateinit var prefs: SharedPreferences

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureComposeSystemBars()
    prefs = PreferenceManager.getDefaultSharedPreferences(this)
    try {
      Config.migrate(prefs)
    } catch (_: Exception) {
      finish()
      return
    }
    setContent {
      KeyboardAppTheme {
        SettingsRoot(
          prefs = prefs,
          foldableDevice = FoldStateTracker.isFoldableDevice(this),
          onBack = { finish() },
          onOpenVoiceSettings = {
            startActivity(Intent(this, ComposeVoiceInputSettingsActivity::class.java))
          },
          save = { DirectBootAwarePreferences.copy_preferences_to_protected_storage(this, prefs) }
        )
      }
    }
  }

  override fun onStop() {
    DirectBootAwarePreferences.copy_preferences_to_protected_storage(this, prefs)
    super.onStop()
  }
}

private enum class SettingsScreen {
  Main,
  Layouts,
  ExtraKeys,
  CustomExtraKeys,
  BuiltInExtraKeys,
  MarginBottom,
  KeyboardHeight,
  HorizontalMargin
}

@Composable
private fun SettingsRoot(
  prefs: SharedPreferences,
  foldableDevice: Boolean,
  onBack: () -> Unit,
  onOpenVoiceSettings: () -> Unit,
  save: () -> Unit
) {
  var screen by remember { mutableStateOf(SettingsScreen.Main) }
  val title = when (screen) {
    SettingsScreen.Main -> stringResource(R.string.app_name)
    SettingsScreen.Layouts -> stringResource(R.string.pref_category_layout)
    SettingsScreen.ExtraKeys -> stringResource(R.string.pref_extra_keys_title)
    SettingsScreen.CustomExtraKeys -> stringResource(R.string.pref_extra_keys_custom)
    SettingsScreen.BuiltInExtraKeys -> stringResource(R.string.pref_extra_keys_internal)
    SettingsScreen.MarginBottom -> stringResource(R.string.pref_margin_bottom_title)
    SettingsScreen.KeyboardHeight -> stringResource(R.string.pref_keyboard_height_title)
    SettingsScreen.HorizontalMargin -> stringResource(R.string.pref_horizontal_margin_title)
  }
  AppScaffold(
    title = title,
    canGoBack = screen != SettingsScreen.Main,
    onBack = {
      if (screen == SettingsScreen.Main) onBack() else screen = SettingsScreen.Main
    }
  ) { modifier ->
    when (screen) {
      SettingsScreen.Main -> MainSettingsScreen(
        prefs = prefs,
        foldableDevice = foldableDevice,
        onOpenLayouts = { screen = SettingsScreen.Layouts },
        onOpenExtraKeys = { screen = SettingsScreen.ExtraKeys },
        onOpenVoiceSettings = onOpenVoiceSettings,
        onOpenMarginBottom = { screen = SettingsScreen.MarginBottom },
        onOpenKeyboardHeight = { screen = SettingsScreen.KeyboardHeight },
        onOpenHorizontalMargin = { screen = SettingsScreen.HorizontalMargin },
        save = save,
        modifier = modifier
      )
      SettingsScreen.Layouts -> LayoutsScreen(prefs, save, modifier)
      SettingsScreen.ExtraKeys -> ExtraKeysMenu(
        onOpenCustom = { screen = SettingsScreen.CustomExtraKeys },
        onOpenBuiltIn = { screen = SettingsScreen.BuiltInExtraKeys },
        modifier = modifier
      )
      SettingsScreen.CustomExtraKeys -> CustomExtraKeysScreen(prefs, save, modifier)
      SettingsScreen.BuiltInExtraKeys -> BuiltInExtraKeysScreen(prefs, save, modifier)
      SettingsScreen.MarginBottom -> OrientedSlidersScreen(
        prefs = prefs,
        save = save,
        foldableDevice = foldableDevice,
        items = listOf(
          IntSliderSpec("margin_bottom_portrait", R.string.pref_portrait, 7, 0, 250, "dp", true),
          IntSliderSpec("margin_bottom_landscape", R.string.pref_landscape, 3, 0, 120, "dp", true),
          IntSliderSpec("margin_bottom_portrait_unfolded", R.string.pref_portrait_unfolded, 7, 0, 250, "dp", foldableDevice),
          IntSliderSpec("margin_bottom_landscape_unfolded", R.string.pref_landscape_unfolded, 3, 0, 250, "dp", foldableDevice)
        ),
        modifier = modifier
      )
      SettingsScreen.KeyboardHeight -> OrientedSlidersScreen(
        prefs = prefs,
        save = save,
        foldableDevice = foldableDevice,
        items = listOf(
          IntSliderSpec("keyboard_height", R.string.pref_portrait, 27, 10, 100, "%", true),
          IntSliderSpec("keyboard_height_landscape", R.string.pref_landscape, 50, 20, 65, "%", true),
          IntSliderSpec("keyboard_height_unfolded", R.string.pref_portrait_unfolded, 27, 10, 50, "%", foldableDevice),
          IntSliderSpec("keyboard_height_landscape_unfolded", R.string.pref_landscape_unfolded, 50, 20, 65, "%", foldableDevice)
        ),
        modifier = modifier
      )
      SettingsScreen.HorizontalMargin -> OrientedSlidersScreen(
        prefs = prefs,
        save = save,
        foldableDevice = foldableDevice,
        items = listOf(
          IntSliderSpec("horizontal_margin_portrait", R.string.pref_portrait, 3, 0, 30, "dp", true),
          IntSliderSpec("horizontal_margin_landscape", R.string.pref_landscape, 28, 0, 200, "dp", true),
          IntSliderSpec("horizontal_margin_portrait_unfolded", R.string.pref_portrait_unfolded, 3, 0, 30, "dp", foldableDevice),
          IntSliderSpec("horizontal_margin_landscape_unfolded", R.string.pref_landscape_unfolded, 28, 0, 200, "dp", foldableDevice)
        ),
        modifier = modifier
      )
    }
  }
}

@Composable
private fun MainSettingsScreen(
  prefs: SharedPreferences,
  foldableDevice: Boolean,
  onOpenLayouts: () -> Unit,
  onOpenExtraKeys: () -> Unit,
  onOpenVoiceSettings: () -> Unit,
  onOpenMarginBottom: () -> Unit,
  onOpenKeyboardHeight: () -> Unit,
  onOpenHorizontalMargin: () -> Unit,
  save: () -> Unit,
  modifier: Modifier
) {
  LazyColumn(modifier = modifier) {
    item { SectionHeader(stringResource(R.string.pref_category_layout)) }
    item {
      SettingsCard {
        PreferenceRow(stringResource(R.string.pref_category_layout), onClick = onOpenLayouts)
        DividerInset()
        PreferenceRow(stringResource(R.string.pref_extra_keys_title), onClick = onOpenExtraKeys)
        DividerInset()
        StringListPref(
          prefs, "number_row", "symbols",
          R.string.pref_number_row_title,
          R.array.pref_show_number_row_entries,
          R.array.pref_show_number_row_values,
          save
        )
        DividerInset()
        StringListPref(
          prefs, "show_numpad", "never",
          R.string.pref_show_numpad_title,
          R.array.pref_show_numpad_entries,
          R.array.pref_show_numpad_values,
          save
        )
        DividerInset()
        StringListPref(
          prefs, "numpad_layout", "high_first",
          R.string.pref_numpad_layout,
          R.array.pref_numpad_layout_entries,
          R.array.pref_numpad_layout_values,
          save
        )
      }
    }

    item { SectionHeader(stringResource(R.string.pref_category_suggestions)) }
    item {
      SettingsCard {
        BooleanPref(prefs, "suggestions", false, R.string.pref_suggestions_title, R.string.pref_suggestions_summary, save)
        DividerInset()
        BooleanPref(prefs, "space_bar_auto_complete", false, R.string.pref_space_bar_auto_complete_title, R.string.pref_space_bar_auto_complete_summary, save)
      }
    }

    item { SectionHeader(stringResource(R.string.pref_category_typing)) }
    item {
      SettingsCard {
        StringListPref(prefs, "swipe_dist", "15", R.string.pref_swipe_dist_title, R.array.pref_swipe_dist_entries, R.array.pref_swipe_dist_values, save)
        DividerInset()
        StringListPref(prefs, "circle_sensitivity", "2", R.string.pref_circle_sensitivity_title, R.array.pref_circle_sensitivity_entries, R.array.pref_circle_sensitivity_values, save)
        DividerInset()
        StringListPref(prefs, "slider_sensitivity", "30", R.string.pref_slider_sensitivity_title, R.array.pref_slider_sensitivity_entries, R.array.pref_slider_sensitivity_values, save)
        DividerInset()
        IntSliderPref(prefs, "longpress_timeout", 600, 50, 2000, "ms", R.string.pref_long_timeout_title, save)
        DividerInset()
        val repeatEnabled = rememberBooleanPref(prefs, "keyrepeat_enabled", true)
        SwitchRow(stringResource(R.string.pref_keyrepeat_enabled), checked = repeatEnabled.value) {
          repeatEnabled.value = it
          prefs.edit().putBoolean("keyrepeat_enabled", it).apply()
          save()
        }
        DividerInset()
        IntSliderPref(prefs, "longpress_interval", 25, 5, 100, "ms", R.string.pref_long_interval_title, save, repeatEnabled.value)
        DividerInset()
        BooleanPref(prefs, "lock_double_tap", false, R.string.pref_lock_double_tap_title, R.string.pref_lock_double_tap_summary, save)
      }
    }

    item { SectionHeader(stringResource(R.string.pref_category_behavior)) }
    item {
      SettingsCard {
        BooleanPref(prefs, "autocapitalisation", false, R.string.pref_autocapitalisation_title, R.string.pref_autocapitalisation_summary, save)
        DividerInset()
        PreferenceRow(
          stringResource(R.string.pref_voice_input_settings_title),
          stringResource(R.string.pref_voice_input_settings_summary),
          onClick = onOpenVoiceSettings
        )
        DividerInset()
        StringListPref(prefs, "change_method_key_replacement", "prev", R.string.pref_change_method_key_replacement_title, R.array.pref_change_method_key_replacement_entries, R.array.pref_change_method_key_replacement_values, save)
        DividerInset()
        val vibrateCustom = rememberBooleanPref(prefs, "vibrate_custom", false)
        SwitchRow(stringResource(R.string.pref_vibrate_custom), checked = vibrateCustom.value) {
          vibrateCustom.value = it
          prefs.edit().putBoolean("vibrate_custom", it).apply()
          save()
        }
        DividerInset()
        IntSliderPref(prefs, "vibrate_duration", 20, 0, 100, "ms", R.string.pref_vibrate_duration_title, save, vibrateCustom.value)
        DividerInset()
        StringListPref(prefs, "number_entry_layout", "pin", R.string.pref_number_entry_title, R.array.pref_number_entry_entries, R.array.pref_number_entry_values, save)
      }
    }

    item { SectionHeader(stringResource(R.string.pref_category_style)) }
    item {
      SettingsCard {
        StringListPref(prefs, "theme", "system", R.string.pref_theme, R.array.pref_theme_entries, R.array.pref_theme_values, save)
        DividerInset()
        IntSliderPref(prefs, "label_brightness", 100, 50, 100, "%", R.string.pref_label_brightness, save)
        DividerInset()
        IntSliderPref(prefs, "keyboard_opacity", 100, 0, 100, "%", R.string.pref_keyboard_opacity, save)
        DividerInset()
        IntSliderPref(prefs, "key_opacity", 100, 0, 100, "%", R.string.pref_key_opacity, save)
        DividerInset()
        IntSliderPref(prefs, "key_activated_opacity", 100, 0, 100, "%", R.string.pref_key_activated_opacity, save)
        DividerInset()
        PreferenceRow(stringResource(R.string.pref_margin_bottom_title), onClick = onOpenMarginBottom)
        DividerInset()
        PreferenceRow(stringResource(R.string.pref_keyboard_height_title), if (foldableDevice) null else stringResource(R.string.pref_portrait), onClick = onOpenKeyboardHeight)
        DividerInset()
        PreferenceRow(stringResource(R.string.pref_horizontal_margin_title), onClick = onOpenHorizontalMargin)
        DividerInset()
        FloatSliderPref(prefs, "character_size", 1.15f, 0.75f, 1.5f, "x", R.string.pref_character_size_title, save)
        DividerInset()
        FloatSliderPref(prefs, "key_vertical_margin", 1.5f, 0f, 5f, "%", R.string.pref_key_vertical_space, save)
        DividerInset()
        FloatSliderPref(prefs, "key_horizontal_margin", 2f, 0f, 5f, "%", R.string.pref_key_horizontal_space, save)
        DividerInset()
        val borderConfig = rememberBooleanPref(prefs, "border_config", false)
        SwitchRow(stringResource(R.string.pref_border_config_title), checked = borderConfig.value) {
          borderConfig.value = it
          prefs.edit().putBoolean("border_config", it).apply()
          save()
        }
        DividerInset()
        IntSliderPref(prefs, "custom_border_radius", 0, 0, 100, "%", R.string.pref_corners_radius_title, save, borderConfig.value)
        DividerInset()
        FloatSliderPref(prefs, "custom_border_line_width", 0f, 0f, 5f, "dp", R.string.pref_border_width_title, save, borderConfig.value)
      }
    }

    item { SectionHeader(stringResource(R.string.pref_category_clipboard)) }
    item {
      SettingsCard {
        StringListPref(prefs, "clipboard_history_duration", "5", R.string.pref_clipboard_history_duration, R.array.pref_clipboard_duration_entries, R.array.pref_clipboard_duration_values, save)
      }
    }
  }
}

@Composable
private fun LayoutsScreen(prefs: SharedPreferences, save: () -> Unit, modifier: Modifier) {
  val context = LocalContext.current
  val layouts = remember {
    mutableStateListOf<Any>().apply {
      addAll(ComposePreferenceBridge.loadLayouts(prefs))
    }
  }
  fun persist() {
    @Suppress("UNCHECKED_CAST")
    val typed = layouts.toList() as List<juloo.keyboard2.prefs.LayoutsPreference.Layout>
    prefs.edit().also { ComposePreferenceBridge.saveLayouts(it, typed) }.apply()
    save()
  }
  var pickingIndex by remember { mutableStateOf<Int?>(null) }
  LazyColumn(modifier = modifier) {
    items(layouts) { item ->
      @Suppress("UNCHECKED_CAST")
      val layout = item as juloo.keyboard2.prefs.LayoutsPreference.Layout
      SettingsCard {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { pickingIndex = layouts.indexOf(item) }
            .padding(horizontal = 16.dp, vertical = 16.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            ComposePreferenceBridge.layoutLabel(context, layout),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
          )
          if (layouts.size > 1) {
            TextButton(onClick = {
              layouts.remove(item)
              persist()
            }) {
              Text(stringResource(R.string.pref_layouts_remove_custom))
            }
          }
        }
      }
    }
    item {
      TextButton(
        onClick = { pickingIndex = layouts.size },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
      ) {
        Text(stringResource(R.string.pref_layouts_add))
      }
    }
  }
  val index = pickingIndex
  if (index != null) {
    val choices = ComposePreferenceBridge.layoutChoices(context)
    AlertDialog(
      onDismissRequest = { pickingIndex = null },
      title = { Text(stringResource(R.string.pref_category_layout)) },
      text = {
        LazyColumn {
          items(choices) { choice ->
            Text(
              choice.label,
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  pickingIndex = null
                  if (choice.name == "custom") {
                    val initial = if (index < layouts.size) {
                      @Suppress("UNCHECKED_CAST")
                      ComposePreferenceBridge.customLayoutXml(layouts[index] as? juloo.keyboard2.prefs.LayoutsPreference.Layout)
                    } else {
                      ComposePreferenceBridge.readInitialCustomLayout(context)
                    }
                    ComposePreferenceBridge.showCustomLayoutDialog(context, initial, index < layouts.size) { custom ->
                      if (custom == null) {
                        if (index < layouts.size) layouts.removeAt(index)
                      } else if (index < layouts.size) {
                        layouts[index] = custom
                      } else {
                        layouts.add(custom)
                      }
                      persist()
                    }
                  } else {
                    val layout = ComposePreferenceBridge.layoutForChoice(choice.name)
                    if (index < layouts.size) layouts[index] = layout else layouts.add(layout)
                    persist()
                  }
                }
                .padding(vertical = 14.dp)
            )
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { pickingIndex = null }) { Text(android.R.string.cancel.asString()) }
      }
    )
  }
}

@Composable
private fun ExtraKeysMenu(onOpenCustom: () -> Unit, onOpenBuiltIn: () -> Unit, modifier: Modifier) {
  LazyColumn(modifier = modifier) {
    item {
      SettingsCard {
        PreferenceRow(stringResource(R.string.pref_extra_keys_custom), onClick = onOpenCustom)
        DividerInset()
        PreferenceRow(stringResource(R.string.pref_extra_keys_internal), onClick = onOpenBuiltIn)
      }
    }
  }
}

@Composable
private fun CustomExtraKeysScreen(prefs: SharedPreferences, save: () -> Unit, modifier: Modifier) {
  val keys = remember {
    mutableStateListOf<String>().apply { addAll(ComposePreferenceBridge.loadCustomExtraKeys(prefs)) }
  }
  var editingIndex by remember { mutableStateOf<Int?>(null) }
  var editingText by remember { mutableStateOf("") }
  fun persist() {
    prefs.edit().also { ComposePreferenceBridge.saveCustomExtraKeys(it, keys) }.apply()
    save()
  }
  LazyColumn(modifier = modifier) {
    items(keys) { key ->
      SettingsCard {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable {
              editingIndex = keys.indexOf(key)
              editingText = key
            }
            .padding(horizontal = 16.dp, vertical = 16.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(key, modifier = Modifier.weight(1f))
          TextButton(onClick = {
            keys.remove(key)
            persist()
          }) { Text(stringResource(R.string.pref_layouts_remove_custom)) }
        }
      }
    }
    item {
      TextButton(
        onClick = {
          editingIndex = keys.size
          editingText = ""
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
      ) {
        Text(stringResource(R.string.pref_layouts_add))
      }
    }
  }
  val index = editingIndex
  if (index != null) {
    TextInputDialog(
      title = stringResource(R.string.pref_extra_keys_custom),
      value = editingText,
      onValueChange = { editingText = it },
      onDismiss = { editingIndex = null },
      onConfirm = {
        val value = editingText.trim()
        if (value.isNotEmpty()) {
          if (index < keys.size) keys[index] = value else keys.add(value)
          persist()
        }
        editingIndex = null
      }
    )
  }
}

@Composable
private fun BuiltInExtraKeysScreen(prefs: SharedPreferences, save: () -> Unit, modifier: Modifier) {
  val context = LocalContext.current
  val items = remember { ComposePreferenceBridge.extraKeyItems(context) }
  LazyColumn(modifier = modifier) {
    items(items) { item ->
      val state = rememberBooleanPref(prefs, item.prefKey, item.defaultChecked)
      SettingsCard {
        SwitchRow(
          title = item.title,
          summary = item.summary,
          checked = state.value
        ) {
          state.value = it
          prefs.edit().putBoolean(item.prefKey, it).apply()
          save()
        }
      }
    }
  }
}

private data class IntSliderSpec(
  val key: String,
  val title: Int,
  val default: Int,
  val min: Int,
  val max: Int,
  val suffix: String,
  val enabled: Boolean
)

@Composable
private fun OrientedSlidersScreen(
  prefs: SharedPreferences,
  save: () -> Unit,
  foldableDevice: Boolean,
  items: List<IntSliderSpec>,
  modifier: Modifier
) {
  LazyColumn(modifier = modifier) {
    item {
      SettingsCard {
        for ((index, spec) in items.withIndex()) {
          if (index > 0) DividerInset()
          IntSliderPref(
            prefs = prefs,
            key = spec.key,
            default = spec.default,
            min = spec.min,
            max = spec.max,
            suffix = spec.suffix,
            titleId = spec.title,
            save = save,
            enabled = spec.enabled
          )
        }
      }
    }
    if (!foldableDevice && items.any { !it.enabled }) {
      item {
        Text(
          stringResource(R.string.pref_portrait_unfolded),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(24.dp)
        )
      }
    }
  }
}

@Composable
private fun BooleanPref(
  prefs: SharedPreferences,
  key: String,
  default: Boolean,
  titleId: Int,
  summaryId: Int?,
  save: () -> Unit
) {
  val state = rememberBooleanPref(prefs, key, default)
  SwitchRow(
    title = stringResource(titleId),
    summary = summaryId?.let { stringResource(it) },
    checked = state.value
  ) {
    state.value = it
    prefs.edit().putBoolean(key, it).apply()
    save()
  }
}

@Composable
private fun StringListPref(
  prefs: SharedPreferences,
  key: String,
  default: String,
  titleId: Int,
  entriesId: Int,
  valuesId: Int,
  save: () -> Unit
) {
  val context = LocalContext.current
  var value by remember { mutableStateOf(prefs.getString(key, default) ?: default) }
  ListPreferenceRow(
    title = stringResource(titleId),
    value = value,
    entries = context.stringArray(entriesId),
    values = context.stringArray(valuesId)
  ) {
    value = it
    prefs.edit().putString(key, it).apply()
    save()
  }
}

@Composable
private fun IntSliderPref(
  prefs: SharedPreferences,
  key: String,
  default: Int,
  min: Int,
  max: Int,
  suffix: String,
  titleId: Int,
  save: () -> Unit,
  enabled: Boolean = true
) {
  var value by remember { mutableStateOf(prefs.getInt(key, default)) }
  SliderRow(
    title = stringResource(titleId),
    value = value,
    min = min.toFloat(),
    max = max.toFloat(),
    step = 1f,
    suffix = suffix,
    enabled = enabled
  ) {
    value = it.toInt()
    prefs.edit().putInt(key, value).apply()
    save()
  }
}

@Composable
private fun FloatSliderPref(
  prefs: SharedPreferences,
  key: String,
  default: Float,
  min: Float,
  max: Float,
  suffix: String,
  titleId: Int,
  save: () -> Unit,
  enabled: Boolean = true
) {
  var value by remember { mutableStateOf(prefs.getFloat(key, default)) }
  SliderRow(
    title = stringResource(titleId),
    value = value,
    min = min,
    max = max,
    step = 0.05f,
    suffix = suffix,
    enabled = enabled
  ) {
    value = it
    prefs.edit().putFloat(key, value).apply()
    save()
  }
}

@Composable
private fun rememberBooleanPref(prefs: SharedPreferences, key: String, default: Boolean) =
  remember { mutableStateOf(prefs.getBoolean(key, default)) }
