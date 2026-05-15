package juloo.keyboard2.prefs

import android.content.Context
import android.content.SharedPreferences
import juloo.keyboard2.CustomLayoutEditDialog
import juloo.keyboard2.KeyboardData
import juloo.keyboard2.KeyValue
import juloo.keyboard2.R
import juloo.keyboard2.Utils

object ComposePreferenceBridge {
  data class LayoutChoice(val name: String, val label: String)
  data class ExtraKeyItem(
    val key: String,
    val prefKey: String,
    val title: String,
    val summary: String?,
    val defaultChecked: Boolean
  )

  fun loadLayouts(prefs: SharedPreferences): MutableList<LayoutsPreference.Layout> {
    val layouts = ListGroupPreference.load_from_preferences(
      LayoutsPreference.KEY,
      prefs,
      LayoutsPreference.DEFAULT,
      LayoutsPreference.SERIALIZER
    )
    return ArrayList(layouts ?: LayoutsPreference.DEFAULT)
  }

  fun saveLayouts(editor: SharedPreferences.Editor, layouts: List<LayoutsPreference.Layout>) {
    LayoutsPreference.save_to_preferences(editor, layouts)
  }

  fun layoutChoices(context: Context): List<LayoutChoice> {
    val names = LayoutsPreference.get_layout_names(context.resources)
    val labels = context.resources.getStringArray(R.array.pref_layout_entries)
    return names.mapIndexed { index, name ->
      LayoutChoice(name, labels.getOrElse(index) { name })
    }
  }

  fun layoutLabel(context: Context, layout: LayoutsPreference.Layout): String {
    return when (layout) {
      is LayoutsPreference.NamedLayout -> {
        val names = LayoutsPreference.get_layout_names(context.resources)
        val labels = context.resources.getStringArray(R.array.pref_layout_entries)
        val index = names.indexOf(layout.name)
        labels.getOrElse(index) { layout.name }
      }
      is LayoutsPreference.CustomLayout -> {
        val parsed = layout.parsed
        if (parsed != null && !parsed.name.isNullOrEmpty()) parsed.name
        else context.getString(R.string.pref_layout_e_custom)
      }
      else -> context.getString(R.string.pref_layout_e_system)
    }
  }

  fun layoutForChoice(name: String): LayoutsPreference.Layout =
    when (name) {
      "system" -> LayoutsPreference.SystemLayout()
      else -> LayoutsPreference.NamedLayout(name)
    }

  fun readInitialCustomLayout(context: Context): String {
    return try {
      Utils.read_all_utf8(context.resources.openRawResource(R.raw.latn_qwerty_us))
    } catch (_: Exception) {
      ""
    }
  }

  fun customLayoutXml(layout: LayoutsPreference.Layout?): String =
    if (layout is LayoutsPreference.CustomLayout) layout.xml else ""

  fun validateCustomLayout(text: String): String? {
    return try {
      KeyboardData.load_string_exn(text)
      null
    } catch (e: Exception) {
      e.message
    }
  }

  fun customLayoutFromXml(text: String): LayoutsPreference.CustomLayout =
    LayoutsPreference.CustomLayout.parse(text)

  fun loadCustomExtraKeys(prefs: SharedPreferences): MutableList<String> {
    val items = ListGroupPreference.load_from_preferences(
      CustomExtraKeysPreference.KEY,
      prefs,
      null,
      CustomExtraKeysPreference.SERIALIZER
    )
    return ArrayList(items ?: emptyList())
  }

  fun saveCustomExtraKeys(editor: SharedPreferences.Editor, keys: List<String>) {
    ListGroupPreference.save_to_preferences(
      CustomExtraKeysPreference.KEY,
      editor,
      keys,
      CustomExtraKeysPreference.SERIALIZER
    )
  }

  fun extraKeyItems(context: Context): List<ExtraKeyItem> =
    ExtraKeysPreference.extra_keys.map { keyName ->
      val kv = KeyValue.getKeyByName(keyName)
      val title = ExtraKeysPreference.key_title(keyName, kv)
      val summary = ExtraKeysPreference.key_description(context.resources, keyName)
      ExtraKeyItem(
        key = keyName,
        prefKey = ExtraKeysPreference.pref_key_of_key_name(keyName),
        title = title,
        summary = summary,
        defaultChecked = ExtraKeysPreference.default_checked(keyName)
      )
    }

  fun showCustomLayoutDialog(
    context: Context,
    initialText: String,
    allowRemove: Boolean,
    onSelected: (LayoutsPreference.CustomLayout?) -> Unit
  ) {
    CustomLayoutEditDialog.show(
      context,
      initialText,
      allowRemove,
      object : CustomLayoutEditDialog.Callback {
        override fun select(text: String?) {
          onSelected(if (text == null) null else customLayoutFromXml(text))
        }

        override fun validate(text: String): String? = validateCustomLayout(text)
      }
    )
  }
}
