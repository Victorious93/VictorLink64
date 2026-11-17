package com.limelight.preferences;

/*
 * Trimmed from moonlight-android's PreferenceConfiguration (GPL-3.0) by Victorious Framework
 * for VictorLink64. Only the fields the vendored decoder reads are kept; VictorLink64 fills
 * them from its own stream-settings screen instead of Moonlight's preference screens.
 */
public class PreferenceConfiguration {
    public enum FormatOption {
        AUTO,
        FORCE_AV1,
        FORCE_HEVC,
        FORCE_H264,
    };

    public static final int FRAME_PACING_MIN_LATENCY = 0;
    public static final int FRAME_PACING_BALANCED = 1;
    public static final int FRAME_PACING_CAP_FPS = 2;
    public static final int FRAME_PACING_MAX_SMOOTHNESS = 3;

    public int width = 1280;
    public int height = 720;
    public int fps = 60;
    /** Kbps */
    public int bitrate = 10000;
    public FormatOption videoFormat = FormatOption.AUTO;
    public int framePacing = FRAME_PACING_BALANCED;
    public boolean fullRange = false;
    public boolean enablePerfOverlay = false;
}
