package juloo.keyboard2

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class ComposeVoiceInputSettingsActivity : ComponentActivity() {
  private lateinit var prefs: SharedPreferences
  private var permissionRefresh: (() -> Unit)? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureComposeSystemBars()
    prefs = PreferenceManager.getDefaultSharedPreferences(this)
    setContent {
      KeyboardAppTheme {
        VoiceInputSettingsScreen(
          prefs = prefs,
          hasPermission = { hasRecordAudioPermission() },
          requestPermission = { requestRecordAudioPermission() },
          onBack = { finish() },
          save = { DirectBootAwarePreferences.copy_preferences_to_protected_storage(this, prefs) },
          setRefresh = { permissionRefresh = it }
        )
      }
    }
  }

  override fun onStop() {
    DirectBootAwarePreferences.copy_preferences_to_protected_storage(this, prefs)
    super.onStop()
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == REQ_RECORD_AUDIO) permissionRefresh?.invoke()
  }

  private fun requestRecordAudioPermission() {
    if (hasRecordAudioPermission()) {
      permissionRefresh?.invoke()
      return
    }
    if (Build.VERSION.SDK_INT >= 23) {
      requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
    }
  }

  private fun hasRecordAudioPermission(): Boolean =
    Build.VERSION.SDK_INT < 23 ||
      checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  companion object {
    private const val REQ_RECORD_AUDIO = 1001
  }
}

@Composable
private fun VoiceInputSettingsScreen(
  prefs: SharedPreferences,
  hasPermission: () -> Boolean,
  requestPermission: () -> Unit,
  onBack: () -> Unit,
  save: () -> Unit,
  setRefresh: (() -> Unit) -> Unit
) {
  var permissionGranted by remember { mutableStateOf(hasPermission()) }
  setRefresh { permissionGranted = hasPermission() }
  AppScaffold(
    title = stringResource(R.string.voice_input_title),
    canGoBack = true,
    onBack = onBack
  ) { modifier ->
    LazyColumn(modifier = modifier.fillMaxSize()) {
      item {
        Text(
          text = stringResource(R.string.voice_input_intro),
          style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
          color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )
      }
      item {
        SettingsCard {
          val enabled = rememberBool(prefs, VoiceInputConfig.PREF_ENABLED, VoiceInputConfig.is_enabled(prefs))
          SwitchRow(stringResource(R.string.voice_input_enabled_title), checked = enabled.value) {
            enabled.value = it
            prefs.edit().putBoolean(VoiceInputConfig.PREF_ENABLED, it).apply()
            save()
          }
          DividerInset()
          var apiKey by remember { mutableStateOf(VoiceInputConfig.get_api_key(prefs)) }
          Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            OutlinedTextField(
              value = apiKey,
              onValueChange = {
                apiKey = it
                prefs.edit().putString(VoiceInputConfig.PREF_API_KEY, it.trim()).apply()
                save()
              },
              label = { Text(stringResource(R.string.voice_input_api_key_title)) },
              placeholder = { Text(stringResource(R.string.voice_input_api_key_hint)) },
              visualTransformation = PasswordVisualTransformation(),
              singleLine = true,
              modifier = Modifier.fillMaxWidth()
            )
          }
        }
      }
      item { SectionHeader(stringResource(R.string.voice_input_model_title)) }
      item {
        SettingsCard {
          var model by remember { mutableStateOf(VoiceInputConfig.get_streaming_model(prefs)) }
          ListPreferenceRow(
            title = stringResource(R.string.voice_input_model_title),
            value = model,
            entries = arrayOf(
              stringResource(R.string.voice_input_model_v1),
              stringResource(R.string.voice_input_model_v2)
            ),
            values = arrayOf(
              VoiceInputConfig.MODEL_DOUBAO_V1,
              VoiceInputConfig.MODEL_DOUBAO_V2
            )
          ) {
            model = it
            prefs.edit().putString(VoiceInputConfig.PREF_STREAMING_MODEL, it).apply()
            save()
          }
        }
      }
      item { SectionHeader(stringResource(R.string.voice_input_trigger_delay_title)) }
      item {
        SettingsCard {
          IntVoiceSlider(
            prefs = prefs,
            key = VoiceInputConfig.PREF_TRIGGER_DELAY_MS,
            default = VoiceInputConfig.get_trigger_delay_ms(prefs),
            min = VoiceInputConfig.MIN_TRIGGER_DELAY_MS,
            max = VoiceInputConfig.MAX_TRIGGER_DELAY_MS,
            step = VoiceInputConfig.TRIGGER_DELAY_STEP_MS,
            title = stringResource(R.string.voice_input_trigger_delay_title),
            suffix = "ms",
            save = save
          )
          DividerInset()
          IntVoiceSlider(
            prefs = prefs,
            key = VoiceInputConfig.PREF_OFFLINE_CHUNK_BYTES,
            default = VoiceInputConfig.get_offline_chunk_bytes(prefs),
            min = VoiceInputConfig.MIN_OFFLINE_CHUNK_BYTES,
            max = VoiceInputConfig.MAX_OFFLINE_CHUNK_BYTES,
            step = 256,
            title = stringResource(R.string.voice_input_offline_chunk_title),
            suffix = " bytes",
            save = save
          )
        }
      }
      item { SectionHeader(stringResource(R.string.voice_input_permission_title)) }
      item {
        SettingsCard {
          PreferenceRow(
            title = stringResource(R.string.voice_input_permission_title),
            summary = stringResource(
              if (permissionGranted) R.string.voice_input_permission_granted
              else R.string.voice_input_permission_missing
            )
          )
          if (!permissionGranted) {
            DividerInset()
            PreferenceRow(
              title = stringResource(R.string.voice_input_permission_button),
              onClick = requestPermission
            )
          }
        }
      }
    }
  }
}

@Composable
private fun IntVoiceSlider(
  prefs: SharedPreferences,
  key: String,
  default: Int,
  min: Int,
  max: Int,
  step: Int,
  title: String,
  suffix: String,
  save: () -> Unit
) {
  var value by remember { mutableStateOf(prefs.getInt(key, default)) }
  SliderRow(
    title = title,
    value = value,
    min = min.toFloat(),
    max = max.toFloat(),
    step = step.toFloat(),
    suffix = suffix,
    defaultValue = default.toFloat(),
    onResetToDefault = {
      value = default
      prefs.edit().putInt(key, default).apply()
      save()
    }
  ) {
    value = it.toInt()
    prefs.edit().putInt(key, value).apply()
    save()
  }
}

@Composable
private fun rememberBool(prefs: SharedPreferences, key: String, default: Boolean) =
  remember { mutableStateOf(prefs.getBoolean(key, default)) }
