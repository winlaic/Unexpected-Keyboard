package juloo.keyboard2;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.AdapterView;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

public class VoiceInputSettingsActivity extends Activity
{
  private static final int REQ_RECORD_AUDIO = 1001;

  private CheckBox _enabled;
  private EditText _apiKey;
  private Spinner _model;
  private SeekBar _triggerDelay;
  private TextView _triggerDelayValue;
  private TextView _permissionStatus;
  private Button _permissionButton;

  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    setTitle(R.string.voice_input_title);
    setContentView(R.layout.voice_input_settings);

    _enabled = (CheckBox)findViewById(R.id.voice_input_enabled);
    _apiKey = (EditText)findViewById(R.id.voice_input_api_key);
    _model = (Spinner)findViewById(R.id.voice_input_model);
    _triggerDelay = (SeekBar)findViewById(R.id.voice_input_trigger_delay);
    _triggerDelayValue = (TextView)findViewById(R.id.voice_input_trigger_delay_value);
    _permissionStatus = (TextView)findViewById(R.id.voice_input_permission_status);
    _permissionButton = (Button)findViewById(R.id.voice_input_permission_button);

    ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
        android.R.layout.simple_spinner_item,
        new String[]{
          getString(R.string.voice_input_model_v1),
          getString(R.string.voice_input_model_v2)
        });
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    _model.setAdapter(adapter);

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    _enabled.setChecked(VoiceInputConfig.is_enabled(prefs));
    _apiKey.setText(VoiceInputConfig.get_api_key(prefs));
    _model.setSelection(VoiceInputConfig.MODEL_DOUBAO_V1.equals(
          VoiceInputConfig.get_streaming_model(prefs)) ? 0 : 1);
    _triggerDelay.setMax((VoiceInputConfig.MAX_TRIGGER_DELAY_MS
          - VoiceInputConfig.MIN_TRIGGER_DELAY_MS)
        / VoiceInputConfig.TRIGGER_DELAY_STEP_MS);
    setTriggerDelay(VoiceInputConfig.get_trigger_delay_ms(prefs));

    _permissionButton.setOnClickListener(v -> requestRecordAudioPermission());
    bindAutoSave();

    updatePermissionStatus();
  }

  @Override
  protected void onStop()
  {
    DirectBootAwarePreferences.copy_preferences_to_protected_storage(this,
        PreferenceManager.getDefaultSharedPreferences(this));
    super.onStop();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions,
      int[] grantResults)
  {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == REQ_RECORD_AUDIO)
      updatePermissionStatus();
  }

  private void bindAutoSave()
  {
    _enabled.setOnCheckedChangeListener((button, checked) ->
        saveBoolean(VoiceInputConfig.PREF_ENABLED, checked));
    _model.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
      {
        saveString(VoiceInputConfig.PREF_STREAMING_MODEL,
            position == 0
              ? VoiceInputConfig.MODEL_DOUBAO_V1
              : VoiceInputConfig.MODEL_DOUBAO_V2);
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent) {}
    });
    _triggerDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
      {
        int delay = triggerDelayFromProgress(progress);
        updateTriggerDelayValue(delay);
        if (fromUser)
          saveInt(VoiceInputConfig.PREF_TRIGGER_DELAY_MS, delay);
      }

      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {}

      @Override
      public void onStopTrackingTouch(SeekBar seekBar)
      {
        saveInt(VoiceInputConfig.PREF_TRIGGER_DELAY_MS,
            triggerDelayFromProgress(seekBar.getProgress()));
      }
    });
    _apiKey.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {}

      @Override
      public void afterTextChanged(Editable s)
      {
        saveString(VoiceInputConfig.PREF_API_KEY, s.toString().trim());
      }
    });
  }

  private void saveBoolean(String key, boolean value)
  {
    PreferenceManager.getDefaultSharedPreferences(this).edit()
      .putBoolean(key, value)
      .apply();
  }

  private void saveString(String key, String value)
  {
    PreferenceManager.getDefaultSharedPreferences(this).edit()
      .putString(key, value)
      .apply();
  }

  private void saveInt(String key, int value)
  {
    PreferenceManager.getDefaultSharedPreferences(this).edit()
      .putInt(key, value)
      .apply();
  }

  private void setTriggerDelay(int delay)
  {
    int progress = (delay - VoiceInputConfig.MIN_TRIGGER_DELAY_MS)
      / VoiceInputConfig.TRIGGER_DELAY_STEP_MS;
    _triggerDelay.setProgress(progress);
    updateTriggerDelayValue(delay);
  }

  private int triggerDelayFromProgress(int progress)
  {
    return VoiceInputConfig.MIN_TRIGGER_DELAY_MS
      + progress * VoiceInputConfig.TRIGGER_DELAY_STEP_MS;
  }

  private void updateTriggerDelayValue(int delay)
  {
    _triggerDelayValue.setText(getString(R.string.voice_input_trigger_delay_value,
          delay));
  }

  private void requestRecordAudioPermission()
  {
    if (hasRecordAudioPermission())
    {
      updatePermissionStatus();
      return;
    }
    if (VERSION.SDK_INT >= 23)
      requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO },
          REQ_RECORD_AUDIO);
  }

  private void updatePermissionStatus()
  {
    boolean granted = hasRecordAudioPermission();
    _permissionStatus.setText(getString(granted
          ? R.string.voice_input_permission_granted
          : R.string.voice_input_permission_missing));
    _permissionButton.setVisibility(granted ? View.GONE : View.VISIBLE);
  }

  private boolean hasRecordAudioPermission()
  {
    return VERSION.SDK_INT < 23
      || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
          == PackageManager.PERMISSION_GRANTED;
  }
}
