package juloo.keyboard2;

import android.content.SharedPreferences;

final class VoiceInputConfig
{
  static final String PREF_ENABLED = "voice_input_enabled";
  static final String PREF_API_KEY = "voice_input_api_key";
  static final String PREF_STREAMING_MODEL = "voice_input_streaming_model";
  static final String PREF_AUTO_SEND = "voice_input_auto_send";
  static final String PREF_TRIGGER_DELAY_MS = "voice_input_trigger_delay_ms";
  static final String PREF_OFFLINE_CHUNK_BYTES = "voice_input_offline_chunk_bytes";
  static final int DEFAULT_TRIGGER_DELAY_MS = 500;
  static final int MIN_TRIGGER_DELAY_MS = 200;
  static final int MAX_TRIGGER_DELAY_MS = 1500;
  static final int TRIGGER_DELAY_STEP_MS = 50;
  static final int DEFAULT_OFFLINE_CHUNK_BYTES = 1536;
  static final int MIN_OFFLINE_CHUNK_BYTES = 768;
  static final int MAX_OFFLINE_CHUNK_BYTES = 6144;

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

  static int get_trigger_delay_ms(SharedPreferences prefs)
  {
    int delay = prefs.getInt(PREF_TRIGGER_DELAY_MS, DEFAULT_TRIGGER_DELAY_MS);
    if (delay < MIN_TRIGGER_DELAY_MS)
      return MIN_TRIGGER_DELAY_MS;
    if (delay > MAX_TRIGGER_DELAY_MS)
      return MAX_TRIGGER_DELAY_MS;
    return delay;
  }

  static int get_offline_chunk_bytes(SharedPreferences prefs)
  {
    int bytes = prefs.getInt(PREF_OFFLINE_CHUNK_BYTES,
        DEFAULT_OFFLINE_CHUNK_BYTES);
    if (bytes < MIN_OFFLINE_CHUNK_BYTES)
      bytes = MIN_OFFLINE_CHUNK_BYTES;
    if (bytes > MAX_OFFLINE_CHUNK_BYTES)
      bytes = MAX_OFFLINE_CHUNK_BYTES;
    bytes -= bytes % 2;
    return bytes > 0 ? bytes : DEFAULT_OFFLINE_CHUNK_BYTES;
  }
}
