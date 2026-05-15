package juloo.keyboard2.dict

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import juloo.cdict.Cdict
import juloo.keyboard2.AppScaffold
import juloo.keyboard2.DeviceLocales
import juloo.keyboard2.KeyboardAppTheme
import juloo.keyboard2.Logs
import juloo.keyboard2.R
import juloo.keyboard2.SettingsCard
import juloo.keyboard2.Utils
import juloo.keyboard2.configureComposeSystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.zip.GZIPInputStream

class ComposeDictionariesActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureComposeSystemBars()
    setContent {
      KeyboardAppTheme {
        DictionariesScreen(onBack = { finish() })
      }
    }
  }
}

private data class DictionaryItem(
  val name: String,
  val label: String,
  val sizeMb: String
)

@Composable
private fun DictionariesScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val dictionaries = remember { Dictionaries.instance(context) }
  val items = remember {
    val locales = DeviceLocales.load(context)
    val supported = SupportedDictionaries(context.resources)
    locales.installed.mapNotNull { loc ->
      val index = loc.dictionary?.let { supported.find(it) } ?: -1
      if (index >= 0) {
        DictionaryItem(
          name = supported.dict_name(index),
          label = supported.display_name(index),
          sizeMb = NumberFormat.getInstance().format(supported.size(index) / 1048576f) + "MB"
        )
      } else {
        null
      }
    }
  }
  var installed by remember { mutableStateOf(dictionaries.get_installed().toSet()) }
  var pending by remember { mutableStateOf(setOf<String>()) }
  val scope = rememberCoroutineScope()
  AppScaffold(
    title = stringResource(R.string.launcher_button_dictionaries),
    canGoBack = true,
    onBack = onBack
  ) { modifier ->
    if (items.isEmpty()) {
      Text(
        text = stringResource(R.string.dictionaries_activity_not_enabled),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(24.dp)
      )
    } else {
      LazyColumn(modifier = modifier.fillMaxSize().padding(vertical = 12.dp)) {
        items(items) { item ->
          SettingsCard {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(item.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
              Text(item.sizeMb, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Spacer(Modifier.padding(horizontal = 4.dp))
              if (pending.contains(item.name)) {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp))
              } else {
                val isInstalled = installed.contains(item.name)
                TextButton(onClick = {
                  pending = pending + item.name
                  scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                      if (isInstalled) {
                        dictionaries.uninstall(item.name)
                        true
                      } else {
                        installDictionaryFromInternet(dictionaries, item.name)
                      }
                    }
                    installed = dictionaries.get_installed().toSet()
                    pending = pending - item.name
                    if (!isInstalled) {
                      Toast.makeText(
                        context,
                        if (ok) R.string.dictionaries_download_success else R.string.dictionaries_download_failed,
                        Toast.LENGTH_SHORT
                      ).show()
                    }
                  }
                }) {
                  Text(if (isInstalled) stringResource(R.string.pref_layouts_remove_custom) else stringResource(R.string.dictionaries_from_internet))
                }
              }
            }
          }
        }
      }
    }
  }
}

private fun installDictionaryFromInternet(dictionaries: Dictionaries, name: String): Boolean {
  return try {
    val connection = DictionaryListView.url_of_dictionary(name).openConnection()
    connection.setRequestProperty("Accept-Encoding", "identity")
    val data = Utils.read_all_bytes(GZIPInputStream(connection.getInputStream()))
    Cdict.of_bytes(data)
    dictionaries.install(name, data)
    true
  } catch (e: Exception) {
    Logs.exn("", e)
    false
  }
}
