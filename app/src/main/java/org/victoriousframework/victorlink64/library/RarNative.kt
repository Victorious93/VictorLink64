package org.victoriousframework.victorlink64.library

/**
 * RAR reading. RAR needs a decoder whose licence is GPL-compatible: junrar/unRAR source carries the unRAR licence
 * (not GPL-compatible), so it is not used. Until a libarchive (BSD) build is bundled this returns null and the
 * importer explains that RAR files need re-saving as .zip or .7z. Victorious Framework.
 */
object RarNative {
    fun backendOrNull(): RarBackend? = null
}
