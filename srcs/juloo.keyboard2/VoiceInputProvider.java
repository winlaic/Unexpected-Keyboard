package juloo.keyboard2;

interface VoiceInputProvider
{
  StreamingSession start_streaming(Listener listener) throws Exception;
  String recognize_once(byte[] wavData) throws Exception;

  interface Listener
  {
    void on_partial_text(String text, boolean isFinal);
    void on_error(Exception error);
  }

  interface StreamingSession
  {
    void send_audio(byte[] pcmData, int length);
    void finish();
    void cancel();
  }
}
