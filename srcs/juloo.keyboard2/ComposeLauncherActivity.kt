package juloo.keyboard2

import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import juloo.keyboard2.dict.ComposeDictionariesActivity
import kotlinx.coroutines.delay

class ComposeLauncherActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureComposeSystemBars()
    setContent {
      KeyboardAppTheme {
        LauncherScreen(
          openSettings = {
            startActivity(Intent(this, ComposeSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          },
          openImeSettings = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
          openImePicker = {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
          },
          openDictionaries = {
            startActivity(Intent(this, ComposeDictionariesActivity::class.java))
          }
        )
      }
    }
  }
}

@Composable
private fun LauncherScreen(
  openSettings: () -> Unit,
  openImeSettings: () -> Unit,
  openImePicker: () -> Unit,
  openDictionaries: () -> Unit
) {
  AppScaffold(
    title = stringResource(R.string.app_name),
    actions = {
      TextButton(onClick = openSettings) {
        Text(stringResource(R.string.launcher_button_settings))
      }
    }
  ) { modifier ->
    LazyColumn(
      modifier = modifier,
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      item {
        Text(
          stringResource(R.string.launcher_description),
          style = MaterialTheme.typography.bodyLarge
        )
      }
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          PrimaryActionButton(stringResource(R.string.launcher_button_imesettings), Modifier.weight(1f), openImeSettings)
          PrimaryActionButton(stringResource(R.string.launcher_button_imepicker), Modifier.weight(1f), openImePicker)
        }
      }
      item {
        PrimaryActionButton(stringResource(R.string.launcher_button_dictionaries), Modifier.fillMaxWidth(), openDictionaries)
      }
      item {
        LauncherAnimationRow(R.drawable.doc_key_u, R.drawable.doc_anim_swipe, stringResource(R.string.launcher_anim_7))
      }
      item {
        LauncherAnimationRow(R.drawable.doc_key_g, R.drawable.doc_anim_circle, stringResource(R.string.launcher_anim_g))
      }
      item {
        LauncherAnimationRow(R.drawable.doc_key_g, R.drawable.doc_anim_round_trip, stringResource(R.string.launcher_anim_dash))
      }
      item {
        TryHere()
      }
      item {
        Text(
          stringResource(R.string.launcher_sourcecode),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      item {
        val uriHandler = LocalUriHandler.current
        val repoUrl = stringResource(R.string.launcher_repo_url)
        TextButton(onClick = { uriHandler.openUri(repoUrl) }) {
          Text(repoUrl)
        }
      }
    }
  }
}

@Composable
private fun LauncherAnimationRow(background: Int, foreground: Int, label: String) {
  val animatables = remember { mutableListOf<Animatable>() }
  LaunchedEffect(background, foreground) {
    while (true) {
      animatables.forEach { it.start() }
      delay(3000)
    }
  }
  SettingsCard {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
      AndroidView(
        modifier = Modifier
          .height(104.dp)
          .weight(1f),
        factory = { context ->
          ImageView(context).apply {
            setBackgroundResource(background)
            setImageResource(foreground)
            adjustViewBounds = true
            (drawable as? Animatable)?.let { animatables.add(it) }
          }
        }
      )
      Text(
        label,
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold
      )
    }
  }
}

@Composable
private fun TryHere() {
  var observedKey by remember { mutableStateOf("") }
  var text by remember { mutableStateOf("") }
  Text(
    if (observedKey.isEmpty()) stringResource(R.string.launcher_tryhere) else observedKey,
    style = MaterialTheme.typography.titleMedium
  )
  OutlinedTextField(
    value = text,
    onValueChange = { text = it },
    placeholder = { Text(stringResource(R.string.launcher_tryhere_hint)) },
    singleLine = true,
    modifier = Modifier
      .fillMaxWidth()
      .onPreviewKeyEvent { event ->
        observedKey = event.key.toString()
        false
      }
  )
}
