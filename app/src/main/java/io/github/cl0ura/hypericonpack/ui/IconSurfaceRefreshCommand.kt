package io.github.cl0ura.hypericonpack.ui

/**
 * Official HyperOS icon refresh path used by Theme Manager.
 *
 * Writes are already on disk under /data/system/theme/icons.  This command only
 * asks the framework to drop icon caches, publish THEME_FLAG_ICON = 0x8, and
 * wake registered theme/appwidget consumers.  It intentionally does not restart
 * Launcher or SystemUI.
 */
internal object IconSurfaceRefreshCommand {
    val command: String = """
        set -e
        apk_path="${'$'}(pm path io.github.cl0ura.hypericonpack | head -n 1 | sed 's/^package://')"
        [ -n "${'$'}apk_path" ] || {
          echo 'HYPER_ICONPACK_THEME_CONFIGURATION_MISSING_APK'
          exit 76
        }
        export CLASSPATH="${'$'}apk_path"
        output="${'$'}(app_process /system/bin io.github.cl0ura.hypericonpack.systemtheme.ThemeConfigurationCommand 2>&1)"
        printf '%s\n' "${'$'}output"
        printf '%s\n' "${'$'}output" | grep -F 'HYPER_ICONPACK_THEME_CONFIGURATION_UPDATED' >/dev/null

        am broadcast --user 0 -a miui.intent.action.ACTION_THEME_CHANGED \
          --ei theme_flag 8 \
          >/dev/null 2>&1 || true

        ids="${'$'}(dumpsys appwidget 2>/dev/null | awk '
          /SimpleUsageStatsWidget|NormalUsageStatsWidget/ {
            line=${'$'}0
            while (match(line, /AppWidgetId\{[0-9]+/)) {
              id=substr(line, RSTART + 12, RLENGTH - 12)
              print id
              line=substr(line, RSTART + RLENGTH)
            }
          }
        ' | sort -u | tr '\n' ' ')"
        if [ -n "${'$'}ids" ]; then
          # shellcheck disable=SC2086
          cmd appwidget update ${'$'}ids >/dev/null 2>&1 || true
          echo "HYPER_ICONPACK_APPWIDGET_UPDATED ${'$'}ids"
        else
          echo 'HYPER_ICONPACK_APPWIDGET_NONE'
        fi
    """.trimIndent()
}
