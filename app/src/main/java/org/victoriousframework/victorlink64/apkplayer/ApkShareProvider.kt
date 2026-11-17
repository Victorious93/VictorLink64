package org.victoriousframework.victorlink64.apkplayer

import androidx.core.content.FileProvider

/** Separate FileProvider class so its manifest entry does not collide with the app's general one. */
class ApkShareProvider : FileProvider()
