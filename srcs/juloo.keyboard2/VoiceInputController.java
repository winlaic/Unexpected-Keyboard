package juloo.keyboard2;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.RectF;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build.VERSION;
import android.os.Handler;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

final class VoiceInputController
{
  enum ReleaseAction
  {
    COMMIT,
    CANCEL,
    SEND
  }

  enum FanSelection
  {
    NONE,
    CANCEL,
    SEND
  }

  static final class OverlayState
  {
    public boolean visible = false;
    public boolean finishing = false;
    public RectF anchor = new RectF();
    public FanSelection fan = FanSelection.NONE;
    public String transcript = "";
    public String status = "";
    public float level = 0.f;
    public long startedAtMs = 0L;
  }

  interface UiListener
  {
    void on_voice_ui_changed();
  }

  private static final int SAMPLE_RATE = 16000;

  private final Keyboard2 _ims;
  private final Handler _handler;
  private final VoiceInputProvider _provider;
  private final OverlayState _overlay = new OverlayState();
  private final Object _audioLock = new Object();

  private UiListener _uiListener;
  private AudioRecord _recorder;
  private Thread _recordThread;
  private volatile boolean _recording;
  private ByteArrayOutputStream _pcmBuffer;
  private VoiceInputProvider.StreamingSession _streamSession;
  private volatile boolean _finalizing;
  private volatile boolean _starting;
  private volatile boolean _acceptingStreamSession;
  private int _streamedPcmBytes = 0;
  private String _liveText = "";

  VoiceInputController(Keyboard2 ims, Handler handler)
  {
    _ims = ims;
    _handler = handler;
    _provider = new VolcengineVoiceProvider(Config.globalPrefs());
  }

  void set_ui_listener(UiListener listener)
  {
    _uiListener = listener;
  }

  boolean is_enabled()
  {
    SharedPreferences prefs = Config.globalPrefs();
    return VoiceInputConfig.is_enabled(prefs);
  }

  OverlayState get_overlay_state()
  {
    return _overlay;
  }

  boolean start(RectF anchor)
  {
    SharedPreferences prefs = Config.globalPrefs();
    Logs.debug("voice-input start requested");
    if (!VoiceInputConfig.is_enabled(prefs))
    {
      Logs.debug("voice-input disabled");
      return false;
    }
    if (!VoiceInputConfig.has_credentials(prefs))
    {
      Logs.debug("voice-input missing credentials");
      toast(R.string.voice_input_missing_credentials);
      open_settings();
      return false;
    }
    if (!has_record_audio_permission())
    {
      Logs.debug("voice-input missing record permission");
      toast(R.string.voice_input_need_permission);
      open_settings();
      return false;
    }
    if (_overlay.visible)
    {
      Logs.debug("voice-input already visible");
      return false;
    }
    _starting = true;
    _acceptingStreamSession = true;
    _streamedPcmBytes = 0;
    _liveText = "";
    _overlay.visible = true;
    _overlay.finishing = false;
    _overlay.anchor.set(anchor);
    _overlay.fan = FanSelection.NONE;
    _overlay.transcript = "";
    _overlay.status = _ims.getString(R.string.voice_input_connecting);
    _overlay.level = 0.f;
    _overlay.startedAtMs = System.currentTimeMillis();
    notify_ui();
    run_async("voice-input-start", () -> {
      try
      {
        Logs.debug("voice-input background start");
        start_recording();
        connect_streaming_session();
      }
      catch (Exception e)
      {
        Logs.exn("Voice input start failed", e);
        _handler.post(() -> fail());
      }
    });
    return true;
  }

  void update_fan(FanSelection fan)
  {
    if (!_overlay.visible)
      return;
    _overlay.fan = fan;
    switch (fan)
    {
      case CANCEL:
        _overlay.status = _ims.getString(R.string.voice_input_cancel);
        break;
      case SEND:
        _overlay.status = _ims.getString(R.string.voice_input_send);
        break;
      default:
        _overlay.status = _ims.getString(
            _finalizing ? R.string.voice_input_finishing : R.string.voice_input_commit);
        break;
    }
    notify_ui();
  }

  void finish(ReleaseAction action)
  {
    if (!_overlay.visible && !_finalizing)
      return;
    hide_overlay();
    request_stop_recording();
    if (_finalizing)
      return;
    if (action == ReleaseAction.SEND
        && !VoiceInputConfig.auto_send_enabled(Config.globalPrefs()))
      action = ReleaseAction.COMMIT;
    if (action == ReleaseAction.CANCEL)
    {
      _acceptingStreamSession = false;
      _overlay.status = _ims.getString(R.string.voice_input_cancel);
      run_async("voice-input-cancel", () -> {
        try
        {
          stop_recording();
          VoiceInputProvider.StreamingSession session = _streamSession;
          if (session != null)
            session.cancel();
        }
        finally
        {
          _handler.post(() -> {
            clear_composing_text();
            reset_ui();
            toast(R.string.voice_input_cancelled);
          });
        }
      });
      return;
    }
    _finalizing = true;
    _acceptingStreamSession = false;
    _overlay.status = _ims.getString(R.string.voice_input_processing);
    final ReleaseAction releaseAction = action;
    run_async("voice-input-finalize", () -> {
      try
      {
        byte[] wavData = pcm_to_wav(stop_recording());
        String fallbackText = _liveText;
        VoiceInputProvider.StreamingSession session = wait_for_stream_session(1500);
        if (session != null)
        {
          session.finish();
          session.cancel();
        }
        finalize_recognition(wavData, fallbackText, releaseAction);
      }
      catch (Exception e)
      {
        Logs.exn("Voice input finalize failed", e);
        _handler.post(() -> {
          clear_composing_text();
          reset_ui();
          toast(R.string.voice_input_failed);
        });
      }
    });
  }

  void shutdown()
  {
    _acceptingStreamSession = false;
    request_stop_recording();
    run_async("voice-input-shutdown", () -> {
      stop_recording();
      VoiceInputProvider.StreamingSession session = _streamSession;
      if (session != null)
        session.cancel();
    });
  }

  private void finalize_recognition(byte[] wavData, String fallbackText,
      ReleaseAction action)
  {
    try
    {
      String finalText = fallbackText;
      if (wavData != null && wavData.length > 44)
      {
        String refined = _provider.recognize_once(wavData).trim();
        if (!refined.equals(""))
          finalText = refined;
      }
      final String committed = finalText;
      _handler.post(() -> commit_final_text(committed, action));
    }
    catch (Exception e)
    {
      Logs.exn("Voice input refine failed", e);
      _handler.post(() -> {
        if (!fallbackText.equals(""))
          commit_final_text(fallbackText, action);
        else
          fail();
      });
    }
  }

  private void commit_final_text(String text, ReleaseAction action)
  {
    try
    {
      InputConnection conn = _ims.getCurrentInputConnection();
      if (conn != null)
      {
        conn.beginBatchEdit();
        if (text.equals(""))
          conn.setComposingText("", 1);
        else
          conn.setComposingText(text, 1);
        conn.finishComposingText();
        conn.endBatchEdit();
      }
      if (action == ReleaseAction.SEND)
      {
        perform_send_action();
        toast(R.string.voice_input_sent);
      }
    }
    finally
    {
      reset_ui();
    }
  }

  private void perform_send_action()
  {
    InputConnection conn = _ims.getCurrentInputConnection();
    if (conn == null)
      return;
    int actionId = Config.globalConfig().editor_config.actionId;
    if (actionId != 0)
      conn.performEditorAction(actionId);
    else
      conn.commitText("\n", 1);
  }

  private void update_streaming_text(String text, boolean isFinal)
  {
    if (!_overlay.visible || _finalizing)
      return;
    _liveText = text;
    _overlay.transcript = text;
    if (_overlay.fan == FanSelection.NONE)
      _overlay.status = _ims.getString(R.string.voice_input_commit);
    notify_ui();
    InputConnection conn = _ims.getCurrentInputConnection();
    if (conn != null)
      conn.setComposingText(text, 1);
  }

  private void fail()
  {
    _starting = false;
    _acceptingStreamSession = false;
    hide_overlay();
    request_stop_recording();
    run_async("voice-input-fail", () -> {
      stop_recording();
      VoiceInputProvider.StreamingSession session = _streamSession;
      if (session != null)
        session.cancel();
      _handler.post(() -> {
        clear_composing_text();
        reset_ui();
        toast(R.string.voice_input_failed);
      });
    });
  }

  private void reset_ui()
  {
    _starting = false;
    _acceptingStreamSession = false;
    _finalizing = false;
    _streamSession = null;
    _streamedPcmBytes = 0;
    _overlay.visible = false;
    _overlay.finishing = false;
    _overlay.fan = FanSelection.NONE;
    _overlay.transcript = "";
    _overlay.status = "";
    _overlay.level = 0.f;
    _liveText = "";
    notify_ui();
  }

  private void hide_overlay()
  {
    _overlay.visible = false;
    _overlay.finishing = true;
    _overlay.fan = FanSelection.NONE;
    _overlay.status = "";
    notify_ui();
  }

  private void request_stop_recording()
  {
    _recording = false;
  }

  private void clear_composing_text()
  {
    InputConnection conn = _ims.getCurrentInputConnection();
    if (conn == null)
      return;
    conn.beginBatchEdit();
    conn.setComposingText("", 1);
    conn.finishComposingText();
    conn.endBatchEdit();
  }

  private void start_recording() throws Exception
  {
    int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    if (minBuffer <= 0)
      throw new RuntimeException("AudioRecord buffer size unavailable");
    int bufferSize = Math.max(minBuffer, SAMPLE_RATE * 2);
    _recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, bufferSize);
    if (_recorder.getState() != AudioRecord.STATE_INITIALIZED)
    {
      _recorder.release();
      _recorder = null;
      throw new RuntimeException("AudioRecord initialization failed");
    }
    synchronized (_audioLock)
    {
      _pcmBuffer = new ByteArrayOutputStream();
    }
    _recording = true;
    _recorder.startRecording();
    _recordThread = new Thread(() -> record_loop(bufferSize), "voice-input-record");
    _recordThread.start();
  }

  private void record_loop(int bufferSize)
  {
    byte[] buffer = new byte[bufferSize];
    while (_recording && _recorder != null)
    {
      int read = _recorder.read(buffer, 0, buffer.length);
      if (read <= 0)
        continue;
      synchronized (_audioLock)
      {
        if (_pcmBuffer != null)
          _pcmBuffer.write(buffer, 0, read);
      }
      VoiceInputProvider.StreamingSession session = _streamSession;
      if (session != null)
      {
        session.send_audio(buffer, read);
        _streamedPcmBytes += read;
      }
      update_level(buffer, read);
    }
  }

  private void update_level(byte[] buffer, int read)
  {
    long sum = 0;
    for (int i = 0; i + 1 < read; i += 2)
    {
      int sample = (buffer[i] & 0xff) | (buffer[i + 1] << 8);
      sum += Math.abs(sample);
    }
    final float level = Math.min(1.f, sum / (float)Math.max(1, read / 2) / 12000.f);
    _handler.post(() -> {
      if (_overlay.visible)
      {
        _overlay.level = level;
        notify_ui();
      }
    });
  }

  private byte[] stop_recording()
  {
    _recording = false;
    AudioRecord recorder = _recorder;
    _recorder = null;
    if (recorder != null)
    {
      try { recorder.stop(); } catch (Exception _e) {}
      recorder.release();
    }
    Thread thread = _recordThread;
    _recordThread = null;
    if (thread != null && thread != Thread.currentThread())
    {
      try { thread.join(300); } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    byte[] pcmData;
    synchronized (_audioLock)
    {
      pcmData = _pcmBuffer != null ? _pcmBuffer.toByteArray() : null;
      _pcmBuffer = null;
    }
    return pcmData;
  }

  private void connect_streaming_session()
  {
    try
    {
      VoiceInputProvider.StreamingSession session =
        _provider.start_streaming(new VoiceInputProvider.Listener() {
          @Override
          public void on_partial_text(String text, boolean isFinal)
          {
            _handler.post(() -> update_streaming_text(text, isFinal));
          }

          @Override
          public void on_error(Exception error)
          {
            Logs.exn("Voice input streaming failed", error);
            _handler.post(() -> {
              if (_overlay.visible)
                fail();
            });
          }
        });
      synchronized (_audioLock)
      {
        if (!_acceptingStreamSession)
        {
          session.cancel();
          return;
        }
        _streamSession = session;
        if (_pcmBuffer != null)
        {
          byte[] captured = _pcmBuffer.toByteArray();
          if (captured.length > _streamedPcmBytes)
          {
            session.send_audio(captured, captured.length);
            _streamedPcmBytes = captured.length;
          }
        }
      }
      _handler.post(() -> {
        if (_overlay.visible && !_finalizing)
        {
          _starting = false;
          _overlay.status = _ims.getString(R.string.voice_input_commit);
          notify_ui();
        }
      });
    }
    catch (Exception e)
    {
      Logs.exn("Voice input connect failed", e);
      _handler.post(() -> fail());
    }
  }

  private byte[] pcm_to_wav(byte[] pcmData)
  {
    if (pcmData == null)
      return null;
    try
    {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      DataOutputStream data = new DataOutputStream(out);
      int dataLength = pcmData.length;
      int byteRate = SAMPLE_RATE * 2;
      data.writeBytes("RIFF");
      write_int_le(data, 36 + dataLength);
      data.writeBytes("WAVE");
      data.writeBytes("fmt ");
      write_int_le(data, 16);
      write_short_le(data, (short)1);
      write_short_le(data, (short)1);
      write_int_le(data, SAMPLE_RATE);
      write_int_le(data, byteRate);
      write_short_le(data, (short)2);
      write_short_le(data, (short)16);
      data.writeBytes("data");
      write_int_le(data, dataLength);
      data.write(pcmData);
      data.flush();
      return out.toByteArray();
    }
    catch (Exception e)
    {
      Logs.exn("WAV conversion failed", e);
      return null;
    }
  }

  private void write_int_le(DataOutputStream out, int value) throws Exception
  {
    out.writeByte(value & 0xff);
    out.writeByte((value >> 8) & 0xff);
    out.writeByte((value >> 16) & 0xff);
    out.writeByte((value >> 24) & 0xff);
  }

  private void write_short_le(DataOutputStream out, short value) throws Exception
  {
    out.writeByte(value & 0xff);
    out.writeByte((value >> 8) & 0xff);
  }

  private boolean has_record_audio_permission()
  {
    return VERSION.SDK_INT < 23
      || _ims.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
          == PackageManager.PERMISSION_GRANTED;
  }

  private void open_settings()
  {
    Intent intent = new Intent(_ims, VoiceInputSettingsActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    _ims.startActivity(intent);
  }

  private void toast(int stringId)
  {
    Toast.makeText(_ims, stringId, Toast.LENGTH_SHORT).show();
  }

  private void notify_ui()
  {
    UiListener listener = _uiListener;
    if (listener != null)
      listener.on_voice_ui_changed();
  }

  private void run_async(String name, Runnable runnable)
  {
    new Thread(runnable, name).start();
  }

  private VoiceInputProvider.StreamingSession wait_for_stream_session(long timeoutMs)
  {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline)
    {
      VoiceInputProvider.StreamingSession session = _streamSession;
      if (session != null)
        return session;
      if (!_starting)
        return null;
      try { Thread.sleep(20); } catch (InterruptedException _e) { return null; }
    }
    return _streamSession;
  }
}
