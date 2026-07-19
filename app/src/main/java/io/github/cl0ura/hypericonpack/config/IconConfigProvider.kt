package io.github.cl0ura.hypericonpack.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.cl0ura.hypericonpack.systemtheme.ManagedIconProviderRuntime
import java.io.FileNotFoundException

/**
 * A deliberately read-only bridge for the hooked System Launcher process.
 * Settings are only written by [IconSettingsStore] in this application's UID.
 */
class IconConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        require(MATCHER.match(uri) == CONFIG) { "Unsupported query URI: $uri" }
        val context = context ?: throw IllegalStateException("Provider is not attached")
        val config = IconSettingsStore(context).read()
        return MatrixCursor(COLUMNS, 1).apply {
            addRow(
                arrayOf<Any?>(
                    config.packageName,
                    config.fallbackScaleMultiplier,
                    if (config.globalMonetIcons) 1 else 0,
                    if (config.monetCustomColors) 1 else 0,
                    config.monetBackgroundColor,
                    config.monetForegroundColor,
                    if (config.systemThemeActive) 1 else 0,
                    if (config.systemThemeAnimationBridge) 1 else 0,
                    config.revision,
                ),
            )
        }
    }

    override fun getType(uri: Uri): String {
        return when (MATCHER.match(uri)) {
            CONFIG -> "vnd.android.cursor.item/vnd.${IconConfigContract.AUTHORITY}.${IconConfigContract.PATH_CONFIG}"
            ICON -> "image/png"
            else -> throw IllegalArgumentException("Unsupported URI: $uri")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only provider")
        require(MATCHER.match(uri) == ICON) { "Unsupported file URI: $uri" }
        val packageName = uri.lastPathSegment?.takeIf(String::isNotBlank)
            ?: throw FileNotFoundException("Missing package name")
        val context = context ?: throw FileNotFoundException("Provider is not attached")
        val bytes = ManagedIconProviderRuntime.loadPackageIconPng(context, packageName)
            ?: throw FileNotFoundException("No managed icon for $packageName")
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                runCatching { output.write(bytes) }
            }
        }, "HyperIconPack-IconPipe").start()
        return pipe[0]
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("Read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = throw UnsupportedOperationException("Read-only")

    private companion object {
        const val CONFIG = 1
        const val ICON = 2
        val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(IconConfigContract.AUTHORITY, IconConfigContract.PATH_CONFIG, CONFIG)
            addURI(IconConfigContract.AUTHORITY, "${IconConfigContract.PATH_ICON}/*", ICON)
        }
        val COLUMNS = arrayOf(
            IconConfigContract.COLUMN_PACKAGE_NAME,
            IconConfigContract.COLUMN_FALLBACK_SCALE,
            IconConfigContract.COLUMN_GLOBAL_MONET_ICONS,
            IconConfigContract.COLUMN_MONET_CUSTOM_COLORS,
            IconConfigContract.COLUMN_MONET_BACKGROUND_COLOR,
            IconConfigContract.COLUMN_MONET_FOREGROUND_COLOR,
            IconConfigContract.COLUMN_SYSTEM_THEME_ACTIVE,
            IconConfigContract.COLUMN_SYSTEM_THEME_ANIMATION_BRIDGE,
            IconConfigContract.COLUMN_REVISION,
        )
    }
}
