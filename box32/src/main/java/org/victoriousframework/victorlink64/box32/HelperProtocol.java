package org.victoriousframework.victorlink64.box32;

/**
 * Wire contract between VictorLink64 and the 32-bit helper. The same constants live in the main app at
 * org.victoriousframework.victorlink64.apkplayer.HelperProtocol (Kotlin); a unit test keeps them equal.
 */
public final class HelperProtocol {
    private HelperProtocol() {}

    public static final int VERSION = 1;

    public static final String ACTION_PING = "org.victoriousframework.victorlink64.box32.action.PING";
    public static final String ACTION_RUN_APK = "org.victoriousframework.victorlink64.box32.action.RUN_APK";

    public static final String EXTRA_DISPLAY_NAME = "display_name";

    public static final String RESULT_STATUS = "status";
    public static final String RESULT_MESSAGE = "message";
    public static final String RESULT_PROTOCOL = "protocol";
    public static final String RESULT_IS_64_BIT = "is64bit";
    public static final String RESULT_ABIS = "abis";
    public static final String RESULT_PID = "pid";
    public static final String RESULT_ENGINE = "engine";
    public static final String RESULT_PACKAGE = "package";

    public static final String STATUS_OK = "ok";
    public static final String STATUS_LAUNCHED = "launched";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_REJECTED = "rejected";
}
