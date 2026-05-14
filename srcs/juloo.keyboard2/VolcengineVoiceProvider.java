package juloo.keyboard2;

import android.content.SharedPreferences;
import android.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONArray;
import org.json.JSONObject;

final class VolcengineVoiceProvider implements VoiceInputProvider
{
  private static final String STREAMING_URL =
    "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel";
  private static final String OFFLINE_URL =
    "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash";
  private static final String OFFLINE_RESOURCE = "volc.bigasr.auc_turbo";
  private static final int SAMPLE_RATE = 16000;
  private static final int STREAMING_SEGMENT_BYTES = SAMPLE_RATE * 2 / 5;

  private static final int MESSAGE_TYPE_CLIENT_FULL_REQUEST = 0x1;
  private static final int MESSAGE_TYPE_CLIENT_AUDIO_ONLY_REQUEST = 0x2;
  private static final int MESSAGE_TYPE_SERVER_FULL_RESPONSE = 0x9;
  private static final int MESSAGE_TYPE_SERVER_ERROR_RESPONSE = 0xf;
  private static final int FLAG_POS_SEQUENCE = 0x1;
  private static final int FLAG_NEG_WITH_SEQUENCE = 0x3;
  private static final int SERIALIZATION_JSON = 0x1;
  private static final int COMPRESSION_GZIP = 0x1;

  private final SharedPreferences _prefs;
  private final OkHttpClient _http;

  VolcengineVoiceProvider(SharedPreferences prefs)
  {
    _prefs = prefs;
    _http = new OkHttpClient.Builder().build();
  }

  @Override
  public VoiceInputProvider.StreamingSession start_streaming(
      VoiceInputProvider.Listener listener) throws Exception
  {
    String requestId = UUID.randomUUID().toString();
    Request request = new Request.Builder()
      .url(STREAMING_URL)
      .header("X-Api-Key", VoiceInputConfig.get_api_key(_prefs))
      .header("X-Api-Resource-Id",
          VoiceInputConfig.get_streaming_resource_id(_prefs))
      .header("X-Api-Request-Id", requestId)
      .header("X-Api-Sequence", "-1")
      .build();
    StreamingSocket socket = new StreamingSocket(listener, requestId);
    _http.newWebSocket(request, socket);
    socket.await_open();
    return socket;
  }

  @Override
  public String recognize_once(byte[] wavData) throws Exception
  {
    HttpURLConnection conn = (HttpURLConnection)new URL(OFFLINE_URL).openConnection();
    conn.setRequestMethod("POST");
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(45000);
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setRequestProperty("X-Api-Key", VoiceInputConfig.get_api_key(_prefs));
    conn.setRequestProperty("X-Api-Resource-Id", OFFLINE_RESOURCE);
    conn.setRequestProperty("X-Api-Request-Id", UUID.randomUUID().toString());
    conn.setRequestProperty("X-Api-Sequence", "-1");

    JSONObject body = new JSONObject();
    body.put("user", new JSONObject().put("uid", get_or_create_uid()));
    body.put("audio", new JSONObject()
        .put("format", "wav")
        .put("data", Base64.encodeToString(wavData, Base64.NO_WRAP))
        .put("rate", SAMPLE_RATE)
        .put("bits", 16)
        .put("channel", 1));
    body.put("request", new JSONObject()
        .put("model_name", "bigmodel")
        .put("enable_itn", true)
        .put("enable_punc", true));
    byte[] payload = body.toString().getBytes("UTF-8");
    OutputStream out = conn.getOutputStream();
    out.write(payload);
    out.close();

    int httpCode = conn.getResponseCode();
    String apiStatus = conn.getHeaderField("X-Api-Status-Code");
    String response = Utils.read_all_utf8(httpCode >= 400
        ? conn.getErrorStream()
        : conn.getInputStream());
    if (httpCode != 200)
      throw new RuntimeException("HTTP " + httpCode + ": " + response);
    if (apiStatus != null && !apiStatus.equals("20000000"))
      throw new RuntimeException("API " + apiStatus + ": " + response);
    return parse_result_text(new JSONObject(response)).trim();
  }

  private String get_or_create_uid()
  {
    String uid = _prefs.getString("voice_input_uid", null);
    if (uid != null && !uid.equals(""))
      return uid;
    uid = "unexpected-keyboard-" + UUID.randomUUID().toString();
    _prefs.edit().putString("voice_input_uid", uid).apply();
    return uid;
  }

  private static byte[] gzip_compress(byte[] data) throws Exception
  {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    GZIPOutputStream gzip = new GZIPOutputStream(out);
    gzip.write(data);
    gzip.finish();
    gzip.close();
    return out.toByteArray();
  }

  private static byte[] gzip_decompress(byte[] data) throws Exception
  {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data));
    byte[] buffer = new byte[4096];
    int read;
    while ((read = gzip.read(buffer)) > 0)
      out.write(buffer, 0, read);
    gzip.close();
    return out.toByteArray();
  }

  private static byte[] build_header(int messageType, int flags)
  {
    return new byte[]{
      (byte)((1 << 4) | 1),
      (byte)((messageType << 4) | flags),
      (byte)((SERIALIZATION_JSON << 4) | COMPRESSION_GZIP),
      0
    };
  }

  private static byte[] build_stream_start_request(String uid, int seq)
      throws Exception
  {
    JSONObject payload = new JSONObject();
    payload.put("user", new JSONObject().put("uid", uid));
    payload.put("audio", new JSONObject()
        .put("format", "pcm")
        .put("codec", "raw")
        .put("rate", SAMPLE_RATE)
        .put("bits", 16)
        .put("channel", 1));
    payload.put("request", new JSONObject()
        .put("model_name", "bigmodel")
        .put("enable_itn", true)
        .put("enable_punc", true)
        .put("enable_ddc", true)
        .put("show_utterances", true)
        .put("enable_nonstream", false));
    byte[] compressed = gzip_compress(payload.toString().getBytes("UTF-8"));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(build_header(MESSAGE_TYPE_CLIENT_FULL_REQUEST, FLAG_POS_SEQUENCE));
    write_int_be(out, seq);
    write_uint_be(out, compressed.length);
    out.write(compressed);
    return out.toByteArray();
  }

  private static byte[] build_audio_request(int seq, byte[] segment, int length,
      boolean isLast) throws Exception
  {
    byte[] compressed = gzip_compress(copy_bytes(segment, length));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(build_header(MESSAGE_TYPE_CLIENT_AUDIO_ONLY_REQUEST,
        isLast ? FLAG_NEG_WITH_SEQUENCE : FLAG_POS_SEQUENCE));
    write_int_be(out, isLast ? -seq : seq);
    write_uint_be(out, compressed.length);
    out.write(compressed);
    return out.toByteArray();
  }

  private static byte[] copy_bytes(byte[] src, int length)
  {
    byte[] out = new byte[length];
    System.arraycopy(src, 0, out, 0, length);
    return out;
  }

  private static void write_int_be(ByteArrayOutputStream out, int value)
  {
    out.write((value >> 24) & 0xff);
    out.write((value >> 16) & 0xff);
    out.write((value >> 8) & 0xff);
    out.write(value & 0xff);
  }

  private static void write_uint_be(ByteArrayOutputStream out, int value)
  {
    write_int_be(out, value);
  }

  private static int read_int_be(byte[] src, int offset)
  {
    return ((src[offset] & 0xff) << 24)
      | ((src[offset + 1] & 0xff) << 16)
      | ((src[offset + 2] & 0xff) << 8)
      | (src[offset + 3] & 0xff);
  }

  private static String parse_result_text(JSONObject json)
  {
    if (json == null)
      return "";
    JSONObject result = json.optJSONObject("result");
    if (result != null)
    {
      String text = result.optString("text", "");
      if (!text.equals(""))
        return text;
      JSONArray utterances = result.optJSONArray("utterances");
      if (utterances != null && utterances.length() > 0)
      {
        JSONObject last = utterances.optJSONObject(utterances.length() - 1);
        if (last != null)
        {
          text = last.optString("text", "");
          if (!text.equals(""))
            return text;
        }
      }
    }
    return json.optString("text", "");
  }

  static final class StreamingSocket extends WebSocketListener
    implements VoiceInputProvider.StreamingSession
  {
    private final VoiceInputProvider.Listener _listener;
    private final String _uid;
    private final Object _lock = new Object();
    private WebSocket _socket;
    private boolean _opened = false;
    private boolean _closed = false;
    private Exception _openError = null;
    private final ByteArrayOutputStream _pending = new ByteArrayOutputStream();
    private int _seq = 1;

    StreamingSocket(VoiceInputProvider.Listener listener, String uid)
    {
      _listener = listener;
      _uid = uid;
    }

    void await_open() throws Exception
    {
      synchronized (_lock)
      {
        long deadline = System.currentTimeMillis() + 10000;
        while (!_opened && _openError == null && System.currentTimeMillis() < deadline)
          _lock.wait(1000);
        if (_openError != null)
          throw _openError;
        if (!_opened)
          throw new RuntimeException("Voice streaming connection timeout");
      }
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response)
    {
      synchronized (_lock)
      {
        _socket = webSocket;
        _opened = true;
        _lock.notifyAll();
      }
      try
      {
        webSocket.send(ByteString.of(build_stream_start_request(_uid, _seq++)));
      }
      catch (Exception e)
      {
        _listener.on_error(e);
      }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes)
    {
      try
      {
        ParsedResponse parsed = parse_response(bytes.toByteArray());
        if (parsed.code != 0)
          throw new RuntimeException("Streaming API error " + parsed.code
              + ": " + parsed.payload);
        String text = parse_result_text(parsed.payload).trim();
        if (!text.equals(""))
          _listener.on_partial_text(text, parsed.isLast);
      }
      catch (Exception e)
      {
        _listener.on_error(e);
      }
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response)
    {
      Exception e = (t instanceof Exception)
        ? (Exception)t
        : new RuntimeException(t);
      synchronized (_lock)
      {
        if (!_opened)
        {
          _openError = e;
          _lock.notifyAll();
          return;
        }
      }
      if (!_closed)
        _listener.on_error(e);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason)
    {
      _closed = true;
    }

    @Override
    public void send_audio(byte[] pcmData, int length)
    {
      if (length <= 0)
        return;
      synchronized (_lock)
      {
        if (_socket == null || _closed)
          return;
        _pending.write(pcmData, 0, length);
        while (_pending.size() >= STREAMING_SEGMENT_BYTES)
        {
          byte[] bytes = _pending.toByteArray();
          send_segment(bytes, STREAMING_SEGMENT_BYTES, false);
          _pending.reset();
          if (bytes.length > STREAMING_SEGMENT_BYTES)
            _pending.write(bytes, STREAMING_SEGMENT_BYTES,
                bytes.length - STREAMING_SEGMENT_BYTES);
        }
      }
    }

    @Override
    public void finish()
    {
      synchronized (_lock)
      {
        if (_socket == null || _closed)
          return;
        byte[] bytes = _pending.toByteArray();
        _pending.reset();
        send_segment(bytes, bytes.length, true);
      }
    }

    @Override
    public void cancel()
    {
      synchronized (_lock)
      {
        _closed = true;
        if (_socket != null)
          _socket.close(1000, "cancelled");
      }
    }

    private void send_segment(byte[] data, int length, boolean isLast)
    {
      try
      {
        _socket.send(ByteString.of(build_audio_request(_seq++, data, length, isLast)));
      }
      catch (Exception e)
      {
        _listener.on_error(e);
      }
    }

    private static ParsedResponse parse_response(byte[] msg) throws Exception
    {
      int headerSize = msg[0] & 0x0f;
      int messageType = (msg[1] >> 4) & 0x0f;
      int flags = msg[1] & 0x0f;
      int serialization = (msg[2] >> 4) & 0x0f;
      int compression = msg[2] & 0x0f;
      int offset = headerSize * 4;
      ParsedResponse parsed = new ParsedResponse();
      parsed.isLast = (flags & 0x02) != 0;
      if ((flags & 0x01) != 0)
        offset += 4;
      if (messageType == MESSAGE_TYPE_SERVER_FULL_RESPONSE)
      {
        parsed.payloadSize = read_int_be(msg, offset);
        offset += 4;
      }
      else if (messageType == MESSAGE_TYPE_SERVER_ERROR_RESPONSE)
      {
        parsed.code = read_int_be(msg, offset);
        offset += 4;
        parsed.payloadSize = read_int_be(msg, offset);
        offset += 4;
      }
      byte[] payload = copy_bytes(msg, msg.length - offset, offset);
      if (compression == COMPRESSION_GZIP && payload.length > 0)
        payload = gzip_decompress(payload);
      if (serialization == SERIALIZATION_JSON && payload.length > 0)
        parsed.payload = new JSONObject(new String(payload, "UTF-8"));
      return parsed;
    }

    private static byte[] copy_bytes(byte[] src, int length, int offset)
    {
      byte[] out = new byte[length];
      System.arraycopy(src, offset, out, 0, length);
      return out;
    }
  }

  static final class ParsedResponse
  {
    public int code = 0;
    public int payloadSize = 0;
    public boolean isLast = false;
    public JSONObject payload = new JSONObject();
  }
}
