package juloo.keyboard2;

import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.LinkedBlockingQueue;
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
  private static final int OPUS_BITRATE = 32000;
  private static final int OPUS_GRANULE_RATE = 48000;
  private static final int OPUS_CHANNELS = 1;

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

  @Override
  public VoiceInputProvider.OfflineSession start_offline_recognition()
      throws Exception
  {
    OfflineOggOpusSession session = new OfflineOggOpusSession(
        VoiceInputConfig.get_api_key(_prefs),
        get_or_create_uid(),
        VoiceInputConfig.get_offline_chunk_bytes(_prefs));
    session.start();
    return session;
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

  static final class OfflineOggOpusSession
    implements VoiceInputProvider.OfflineSession
  {
    private static final byte[] END = new byte[0];

    private final String _apiKey;
    private final String _uid;
    private final int _chunkBytes;
    private final LinkedBlockingQueue<byte[]> _queue =
      new LinkedBlockingQueue<byte[]>();
    private final Object _lock = new Object();
    private HttpURLConnection _conn;
    private OutputStream _out;
    private Thread _worker;
    private Exception _error;
    private boolean _closed = false;
    private boolean _finishQueued = false;

    OfflineOggOpusSession(String apiKey, String uid, int chunkBytes)
    {
      _apiKey = apiKey;
      _uid = uid;
      _chunkBytes = chunkBytes;
    }

    void start() throws Exception
    {
      _conn = (HttpURLConnection)new URL(OFFLINE_URL).openConnection();
      _conn.setRequestMethod("POST");
      _conn.setConnectTimeout(15000);
      _conn.setReadTimeout(45000);
      _conn.setDoOutput(true);
      _conn.setChunkedStreamingMode(4096);
      _conn.setRequestProperty("Content-Type", "application/json");
      _conn.setRequestProperty("X-Api-Key", _apiKey);
      _conn.setRequestProperty("X-Api-Resource-Id", OFFLINE_RESOURCE);
      _conn.setRequestProperty("X-Api-Request-Id", UUID.randomUUID().toString());
      _conn.setRequestProperty("X-Api-Sequence", "-1");
      _out = _conn.getOutputStream();
      write_utf8(_out, "{\"user\":{\"uid\":" + JSONObject.quote(_uid)
          + "},\"audio\":{\"format\":\"ogg\",\"data\":\"");
      StreamingBase64Writer base64 = new StreamingBase64Writer(_out);
      _worker = new Thread(() -> encode_and_upload(base64),
          "voice-input-offline-ogg");
      _worker.start();
    }

    @Override
    public void send_audio(byte[] pcmData, int length)
    {
      if (pcmData == null || length <= 0)
        return;
      synchronized (_lock)
      {
        if (_closed || _finishQueued)
          return;
      }
      int offset = 0;
      while (offset < length)
      {
        int copyLength = Math.min(_chunkBytes, length - offset);
        byte[] copy = new byte[copyLength];
        System.arraycopy(pcmData, offset, copy, 0, copyLength);
        synchronized (_lock)
        {
          if (_closed || _finishQueued)
            return;
          _queue.offer(copy);
        }
        offset += copyLength;
      }
    }

    @Override
    public String finish_and_get_result() throws Exception
    {
      synchronized (_lock)
      {
        if (!_closed && !_finishQueued)
        {
          _finishQueued = true;
          _queue.offer(END);
        }
      }
      Thread worker = _worker;
      if (worker != null)
        worker.join();
      if (_error != null)
        throw _error;

      int httpCode = _conn.getResponseCode();
      String apiStatus = _conn.getHeaderField("X-Api-Status-Code");
      String response = Utils.read_all_utf8(httpCode >= 400
          ? _conn.getErrorStream()
          : _conn.getInputStream());
      if (httpCode != 200)
        throw new RuntimeException("HTTP " + httpCode + ": " + response);
      if (apiStatus != null && !apiStatus.equals("20000000"))
        throw new RuntimeException("API " + apiStatus + ": " + response);
      return parse_result_text(new JSONObject(response)).trim();
    }

    @Override
    public void cancel()
    {
      synchronized (_lock)
      {
        if (_closed)
          return;
        _closed = true;
        _finishQueued = true;
        _queue.offer(END);
      }
      try
      {
        if (_out != null)
          _out.close();
      }
      catch (Exception _e) {}
      if (_conn != null)
        _conn.disconnect();
    }

    private void encode_and_upload(StreamingBase64Writer base64)
    {
      MediaCodec codec = null;
      try
      {
        OggOpusMuxer muxer = new OggOpusMuxer(base64,
            (int)UUID.randomUUID().getLeastSignificantBits());
        muxer.write_headers();

        MediaFormat format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, OPUS_CHANNELS);
        format.setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BITRATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, _chunkBytes);
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] pending = null;
        int pendingOffset = 0;
        long inputBytes = 0;
        boolean inputDone = false;
        boolean outputDone = false;
        while (!outputDone)
        {
          if (!inputDone)
          {
            int inputIndex = codec.dequeueInputBuffer(10000);
            if (inputIndex >= 0)
            {
              if (pending == null || pendingOffset >= pending.length)
              {
                pending = _queue.take();
                pendingOffset = 0;
              }
              ByteBuffer input = codec.getInputBuffer(inputIndex);
              input.clear();
              if (pending == END)
              {
                long pts = inputBytes * 1000000L / (SAMPLE_RATE * 2);
                codec.queueInputBuffer(inputIndex, 0, 0, pts,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                inputDone = true;
              }
              else
              {
                int length = Math.min(input.remaining(),
                    pending.length - pendingOffset);
                input.put(pending, pendingOffset, length);
                long pts = inputBytes * 1000000L / (SAMPLE_RATE * 2);
                codec.queueInputBuffer(inputIndex, 0, length, pts, 0);
                inputBytes += length;
                pendingOffset += length;
                if (pendingOffset >= pending.length)
                  pending = null;
              }
            }
          }

          while (true)
          {
            int outputIndex = codec.dequeueOutputBuffer(info, 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER)
              break;
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
              continue;
            if (outputIndex < 0)
              continue;
            if (info.size > 0
                && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0)
            {
              ByteBuffer output = codec.getOutputBuffer(outputIndex);
              byte[] packet = new byte[info.size];
              output.position(info.offset);
              output.limit(info.offset + info.size);
              output.get(packet);
              muxer.write_packet(packet);
            }
            boolean eos =
              (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(outputIndex, false);
            if (eos)
            {
              outputDone = true;
              break;
            }
          }
        }
        muxer.finish();
        base64.finish();
        write_utf8(_out, "\"},\"request\":{\"model_name\":\"bigmodel\","
            + "\"enable_itn\":true,\"enable_punc\":true,"
            + "\"show_utterances\":true}}");
        _out.close();
      }
      catch (Exception e)
      {
        synchronized (_lock)
        {
          if (!_closed)
            _error = e instanceof Exception ? (Exception)e : new RuntimeException(e);
        }
        try
        {
          if (_out != null)
            _out.close();
        }
        catch (Exception _e) {}
      }
      finally
      {
        if (codec != null)
        {
          try { codec.stop(); } catch (Exception _e) {}
          try { codec.release(); } catch (Exception _e) {}
        }
      }
    }

    private static void write_utf8(OutputStream out, String text)
        throws IOException
    {
      out.write(text.getBytes("UTF-8"));
    }
  }

  static final class StreamingBase64Writer
  {
    private final OutputStream _out;
    private final byte[] _pending = new byte[2];
    private int _pendingLength = 0;

    StreamingBase64Writer(OutputStream out)
    {
      _out = out;
    }

    void write(byte[] data) throws IOException
    {
      write(data, 0, data.length);
    }

    void write(byte[] data, int offset, int length) throws IOException
    {
      if (length <= 0)
        return;
      int pos = offset;
      int remaining = length;
      if (_pendingLength > 0)
      {
        byte[] full = new byte[3];
        System.arraycopy(_pending, 0, full, 0, _pendingLength);
        int needed = 3 - _pendingLength;
        if (remaining < needed)
        {
          System.arraycopy(data, pos, _pending, _pendingLength, remaining);
          _pendingLength += remaining;
          return;
        }
        System.arraycopy(data, pos, full, _pendingLength, needed);
        write_encoded(full, 0, full.length);
        pos += needed;
        remaining -= needed;
        _pendingLength = 0;
      }

      int bulkLength = remaining - (remaining % 3);
      if (bulkLength > 0)
      {
        write_encoded(data, pos, bulkLength);
        pos += bulkLength;
        remaining -= bulkLength;
      }
      if (remaining > 0)
      {
        System.arraycopy(data, pos, _pending, 0, remaining);
        _pendingLength = remaining;
      }
    }

    void finish() throws IOException
    {
      if (_pendingLength > 0)
      {
        write_encoded(_pending, 0, _pendingLength);
        _pendingLength = 0;
      }
    }

    void flush() throws IOException
    {
      _out.flush();
    }

    private void write_encoded(byte[] data, int offset, int length)
        throws IOException
    {
      _out.write(Base64.encode(data, offset, length, Base64.NO_WRAP));
    }
  }

  static final class OggOpusMuxer
  {
    private static final int[] CRC_LOOKUP = build_crc_lookup();

    private final StreamingBase64Writer _out;
    private final int _serial;
    private int _sequence = 0;
    private long _granule = 0;

    OggOpusMuxer(StreamingBase64Writer out, int serial)
    {
      _out = out;
      _serial = serial;
    }

    void write_headers() throws IOException
    {
      ByteArrayOutputStream head = new ByteArrayOutputStream();
      head.write("OpusHead".getBytes("US-ASCII"));
      head.write(1);
      head.write(OPUS_CHANNELS);
      write_short_le(head, 0);
      write_int_le(head, SAMPLE_RATE);
      write_short_le(head, 0);
      head.write(0);
      write_page(head.toByteArray(), 0x02, 0);

      ByteArrayOutputStream tags = new ByteArrayOutputStream();
      byte[] vendor = "Unexpected Keyboard".getBytes("UTF-8");
      tags.write("OpusTags".getBytes("US-ASCII"));
      write_int_le(tags, vendor.length);
      tags.write(vendor);
      write_int_le(tags, 0);
      write_page(tags.toByteArray(), 0, 0);
    }

    void write_packet(byte[] packet) throws IOException
    {
      _granule += opus_packet_samples(packet);
      write_page(packet, 0, _granule);
    }

    void finish() throws IOException
    {
      write_page(new byte[0], 0x04, _granule);
    }

    private void write_page(byte[] packet, int headerType, long granule)
        throws IOException
    {
      int segmentCount = packet.length / 255 + 1;
      ByteArrayOutputStream page = new ByteArrayOutputStream();
      page.write("OggS".getBytes("US-ASCII"));
      page.write(0);
      page.write(headerType);
      write_long_le(page, granule);
      write_int_le(page, _serial);
      write_int_le(page, _sequence++);
      write_int_le(page, 0);
      page.write(segmentCount);
      int remaining = packet.length;
      for (int i = 0; i < segmentCount; ++i)
      {
        int value = remaining >= 255 ? 255 : remaining;
        page.write(value);
        remaining -= value;
      }
      page.write(packet);
      byte[] bytes = page.toByteArray();
      int crc = ogg_crc(bytes);
      bytes[22] = (byte)(crc & 0xff);
      bytes[23] = (byte)((crc >> 8) & 0xff);
      bytes[24] = (byte)((crc >> 16) & 0xff);
      bytes[25] = (byte)((crc >> 24) & 0xff);
      _out.write(bytes);
      _out.flush();
    }

    private static int opus_packet_samples(byte[] packet)
    {
      if (packet.length == 0)
        return 0;
      int toc = packet[0] & 0xff;
      int config = toc >> 3;
      int frameSamples;
      if (config < 12)
        frameSamples = new int[]{480, 960, 1920, 2880}[config & 3];
      else if (config < 16)
        frameSamples = new int[]{480, 960}[config & 1];
      else
        frameSamples = new int[]{120, 240, 480, 960}[config & 3];

      int frameCount;
      switch (toc & 3)
      {
        case 0:
          frameCount = 1;
          break;
        case 1:
        case 2:
          frameCount = 2;
          break;
        default:
          frameCount = packet.length > 1 ? packet[1] & 0x3f : 0;
          break;
      }
      return frameSamples * frameCount;
    }

    private static int[] build_crc_lookup()
    {
      int[] lookup = new int[256];
      for (int i = 0; i < lookup.length; ++i)
      {
        int r = i << 24;
        for (int j = 0; j < 8; ++j)
          r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04c11db7 : r << 1;
        lookup[i] = r;
      }
      return lookup;
    }

    private static int ogg_crc(byte[] bytes)
    {
      int crc = 0;
      for (byte b : bytes)
        crc = (crc << 8) ^ CRC_LOOKUP[((crc >>> 24) & 0xff) ^ (b & 0xff)];
      return crc;
    }

    private static void write_short_le(OutputStream out, int value)
        throws IOException
    {
      out.write(value & 0xff);
      out.write((value >> 8) & 0xff);
    }

    private static void write_int_le(OutputStream out, int value)
        throws IOException
    {
      out.write(value & 0xff);
      out.write((value >> 8) & 0xff);
      out.write((value >> 16) & 0xff);
      out.write((value >> 24) & 0xff);
    }

    private static void write_long_le(OutputStream out, long value)
        throws IOException
    {
      for (int i = 0; i < 8; ++i)
        out.write((int)(value >> (8 * i)) & 0xff);
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
