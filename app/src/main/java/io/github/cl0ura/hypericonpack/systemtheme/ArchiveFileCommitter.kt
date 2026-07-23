package io.github.cl0ura.hypericonpack.systemtheme

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Replaces a completed archive without deleting the last valid file first. */
internal object ArchiveFileCommitter {
    fun replace(temporary: File, destination: File, errorMessage: String) {
        require(temporary.isFile) { "待提交主题归档不存在：${temporary.absolutePath}" }
        require(temporary.parentFile == destination.parentFile) { "主题归档必须在同一目录内提交" }

        val source = temporary.toPath()
        val target = destination.toPath()
        try {
            try {
                Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (exception: Exception) {
            throw IllegalStateException(errorMessage, exception)
        }
    }
}
