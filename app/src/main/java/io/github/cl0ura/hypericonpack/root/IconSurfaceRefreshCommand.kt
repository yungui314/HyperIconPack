package io.github.cl0ura.hypericonpack.root

/**
 * Official HyperOS icon refresh path used by Theme Manager.
 *
 * Writes are already on disk under /data/system/theme/icons.  This command only
 * asks the framework to drop icon caches, publish THEME_FLAG_ICON = 0x8, and
 * wake registered theme consumers.  It intentionally does not restart
 * Launcher or SystemUI.
 */
internal object IconSurfaceRefreshCommand {
    val command: String = """
        set -e
        IFS= read -r apk_path
        case "${'$'}apk_path" in
          /data/app/*/base.apk) ;;
          *)
            echo 'HYPER_ICONPACK_THEME_CONFIGURATION_INVALID_APK'
            exit 76
            ;;
        esac
        [ -f "${'$'}apk_path" ] || {
          echo 'HYPER_ICONPACK_THEME_CONFIGURATION_MISSING_APK'
          exit 76
        }
        export CLASSPATH="${'$'}apk_path"
        output="${'$'}(app_process /system/bin io.github.cl0ura.hypericonpack.systemtheme.ThemeConfigurationCommand 2>&1)"
        printf '%s\n' "${'$'}output"
        printf '%s\n' "${'$'}output" | grep -F 'HYPER_ICONPACK_THEME_CONFIGURATION_UPDATED' >/dev/null

        am broadcast --user 0 -a miui.intent.action.ACTION_THEME_CHANGED \
          >/dev/null 2>&1 || true
    """.trimIndent()
}
