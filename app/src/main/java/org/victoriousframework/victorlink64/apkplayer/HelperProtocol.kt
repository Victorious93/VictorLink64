package org.victoriousframework.victorlink64.apkplayer

/**
 * Wire contract with the 32-bit helper app (module `:box32`, class `HelperProtocol.java`).
 * `HelperProtocolSyncTest` fails if the two copies drift apart.
 */
object HelperProtocol {
    const val VERSION = 1

    const val ACTION_PING = "org.victoriousframework.victorlink64.box32.action.PING"
    const val ACTION_RUN_APK = "org.victoriousframework.victorlink64.box32.action.RUN_APK"

    const val EXTRA_DISPLAY_NAME = "display_name"

    const val RESULT_STATUS = "status"
    const val RESULT_MESSAGE = "message"
    const val RESULT_PROTOCOL = "protocol"
    const val RESULT_IS_64_BIT = "is64bit"
    const val RESULT_ABIS = "abis"
    const val RESULT_PID = "pid"
    const val RESULT_ENGINE = "engine"
    const val RESULT_PACKAGE = "package"

    const val STATUS_OK = "ok"
    const val STATUS_LAUNCHED = "launched"
    const val STATUS_ERROR = "error"
    const val STATUS_REJECTED = "rejected"

    const val META_PROTOCOL = "org.victoriousframework.victorlink64.box32.PROTOCOL"
}
