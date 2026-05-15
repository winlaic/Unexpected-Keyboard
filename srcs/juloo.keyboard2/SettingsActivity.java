package juloo.keyboard2;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;

public class SettingsActivity extends PreferenceActivity
{
  private static final String EXTRA_SCREEN_KEY = "juloo.keyboard2.SCREEN_KEY";

  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    // The preferences can't be read when in direct-boot mode. Avoid crashing
    // and don't allow changing the settings.
    // Run the config migration on this prefs as it might be different from the
    // one used by the keyboard, which have been migrated.
    try
    {
      Config.migrate(getPreferenceManager().getSharedPreferences());
    }
    catch (Exception _e) { fallbackEncrypted(); return; }
    addPreferencesFromResource(R.xml.settings);

    boolean foldableDevice = FoldStateTracker.isFoldableDevice(this);
    findPreference("margin_bottom_portrait_unfolded").setEnabled(foldableDevice);
    findPreference("margin_bottom_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("horizontal_margin_portrait_unfolded").setEnabled(foldableDevice);
    findPreference("horizontal_margin_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("keyboard_height_unfolded").setEnabled(foldableDevice);
    findPreference("keyboard_height_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("voice_input_settings").setOnPreferenceClickListener(pref -> {
      startActivity(new Intent(this, VoiceInputSettingsActivity.class));
      return true;
    });
    openNestedScreensInActivity(getPreferenceScreen());
    String screenKey = getIntent().getStringExtra(EXTRA_SCREEN_KEY);
    if (screenKey != null)
      showPreferenceScreen(screenKey);
  }

  void fallbackEncrypted()
  {
    // Can't communicate with the user here.
    finish();
  }

  protected void onStop()
  {
    DirectBootAwarePreferences
      .copy_preferences_to_protected_storage(this,
          getPreferenceManager().getSharedPreferences());
    super.onStop();
  }

  private void openNestedScreensInActivity(PreferenceGroup group)
  {
    for (int i = 0; i < group.getPreferenceCount(); i++)
    {
      Preference preference = group.getPreference(i);
      if (preference instanceof PreferenceScreen)
      {
        PreferenceScreen screen = (PreferenceScreen)preference;
        if (screen.getPreferenceCount() > 0 && screen.getKey() != null)
        {
          Intent intent = new Intent(this, SettingsActivity.class);
          intent.putExtra(EXTRA_SCREEN_KEY, screen.getKey());
          screen.setIntent(intent);
        }
      }
      if (preference instanceof PreferenceGroup)
        openNestedScreensInActivity((PreferenceGroup)preference);
    }
  }

  private void showPreferenceScreen(String key)
  {
    Preference preference = findPreference(key);
    if (!(preference instanceof PreferenceScreen))
    {
      finish();
      return;
    }
    setTitle(preference.getTitle());
    setPreferenceScreen((PreferenceScreen)preference);
  }
}
