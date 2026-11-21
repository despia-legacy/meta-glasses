package android.media;

/** Test double only. Production source is separately compiled against the real Android SDK. */
public final class MediaFormat {
    public static MediaFormat createVideoFormat(String mime, int width, int height) { return new MediaFormat(); }
    public void setInteger(String key, int number) { }
}
