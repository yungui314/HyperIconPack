package io.github.cl0ura.hypericonpack.systemtheme

/**
 * Serializes full conversion and incremental package archive rewrites.
 * Both paths eventually install into /data/system/theme; concurrent writers
 * can leave a half-applied cache path or race Root install order.
 */
internal object ThemeArchiveMutationGate {
    private val lock = Any()

    fun <T> withLock(block: () -> T): T = synchronized(lock, block)
}
