package juloo.keyboard2;

import android.content.SharedPreferences;

final class VoiceInputConfig
{
  static final String PREF_ENABLED = "voice_input_enabled";
  static final String PREF_API_KEY = "voice_input_api_key";
  static final String PREF_STREAMING_MODEL = "voice_input_streaming_model";
  static final String PREF_AUTO_SEND = "voice_input_auto_send";

  static final String MODEL_DOUBAO_V1 = "doubao_asr_v1";
  static final String MODEL_DOUBAO_V2 = "doubao_asr_v2";
  static final String RESOURCE_DOUBAO_V1 = "volc.bigasr.sauc.duration";
  static final String RESOURCE_DOUBAO_V2 = "volc.seedasr.sauc.duration";

  private VoiceInputConfig() {}

  static boolean is_enabled(SharedPreferences prefs)
  {
    return prefs.getBoolean(PREF_ENABLED, false);
  }

  static boolean has_credentials(SharedPreferences prefs)
  {
    return !get_api_key(prefs).equals("");
  }

  static String get_api_key(SharedPreferences prefs)
  {
    return prefs.getString(PREF_API_KEY, "").trim();
  }

  static String get_streaming_model(SharedPreferences prefs)
  {
    return prefs.getString(PREF_STREAMING_MODEL, MODEL_DOUBAO_V2);
  }

  static String get_streaming_resource_id(SharedPreferences prefs)
  {
    String model = get_streaming_model(prefs);
    if (MODEL_DOUBAO_V1.equals(model))
      return RESOURCE_DOUBAO_V1;
    return RESOURCE_DOUBAO_V2;
  }

  static boolean auto_send_enabled(SharedPreferences prefs)
  {
    return prefs.getBoolean(PREF_AUTO_SEND, true);
  }
}
