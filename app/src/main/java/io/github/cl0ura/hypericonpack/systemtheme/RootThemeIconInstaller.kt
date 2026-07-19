package io.github.cl0ura.hypericonpack.systemtheme

import io.github.cl0ura.hypericonpack.ui.RootAccess
import java.io.File

/**
 * Root installer for a previously validated HyperOS icon archive.
 *
 * All paths and shell fragments are constants.  The selected icon-pack name,
 * archive path and other UI text are never interpolated into a shell command.
 * Archive bytes are written through stdin to avoid granting a shell process
 * access to the app's private storage.
 */
internal object RootThemeIconInstaller {
    private const val THEME_DIRECTORY = "/data/system/theme"
    private const val TARGET = "$THEME_DIRECTORY/icons"
    private const val TEMPORARY = "$THEME_DIRECTORY/.hypericonpack-icons.new"
    private const val BACKUP = "$THEME_DIRECTORY/.hypericonpack-icons.backup"
    private const val NO_ORIGINAL_MARKER = "$THEME_DIRECTORY/.hypericonpack-icons.no-original"
    private const val ACTIVE_HASH_MARKER = "$THEME_DIRECTORY/.hypericonpack-icons.sha256"
    private const val HASH_TEMPORARY = "$THEME_DIRECTORY/.hypericonpack-icons.sha256.new"
    private const val DYNAMIC_TARGET = "$THEME_DIRECTORY/dynamicicons"
    private const val DYNAMIC_TEMPORARY = "$THEME_DIRECTORY/.hypericonpack-dynamicicons.new"
    private const val DYNAMIC_NONE_MARKER = "$THEME_DIRECTORY/.hypericonpack-dynamicicons.none"

    data class Result(
        val success: Boolean,
        val output: String,
    )

    /**
     * Checks the actual SELinux-constrained Root process, not merely uid=0.
     * A number of KernelSU builds intentionally run app-granted su in a domain
     * that cannot access theme_data_file.  Reporting this prevents a false
     * “system theme applied” result.
     */
    fun preflight(): Result {
        val root = RootAccess.check()
        if (!root.success) return Result(false, root.output)
        val probe = RootAccess.runFixed(PREFLIGHT_COMMAND)
        return Result(probe.success, probe.output)
    }

    fun install(archive: File): Result {
        if (!archive.isFile || archive.length() == 0L) {
            return Result(false, "未找到已验证的主题归档，请先执行“转换为 HyperOS 主题资源”。")
        }
        val preflight = preflight()
        if (!preflight.success) return preflight
        val dynamicArchive = HyperOsIconArchiveConverter.dynamicArchiveFor(archive)
        val dynamicStage = if (dynamicArchive.isFile && dynamicArchive.length() > 0L) {
            RootAccess.runFixedWithInput(
                command = STAGE_DYNAMIC_COMMAND,
                input = dynamicArchive.inputStream(),
                timeoutSeconds = 60L,
            )
        } else {
            RootAccess.runFixed(STAGE_NO_DYNAMIC_COMMAND, timeoutSeconds = 20L)
        }
        if (!dynamicStage.success || !dynamicStage.output.contains("HYPER_ICONPACK_DYNAMIC_STAGE_OK")) {
            return Result(false, dynamicStage.output)
        }
        val result = RootAccess.runFixedWithInput(
            command = INSTALL_COMMAND,
            input = archive.inputStream(),
            timeoutSeconds = 120L,
        )
        if (!result.success || !result.output.contains("HYPER_ICONPACK_INSTALL_OK")) {
            return Result(false, result.output)
        }
        return refreshAfterThemeMutation(result.output, action = "安装")
    }

    /**
     * Restores exactly the file this installer last activated.  A hash marker
     * protects a newer theme selected in Theme Manager from accidental
     * deletion: if the current icons archive no longer matches our marker, the
     * operation stops without changing anything.
     */
    fun restore(): Result {
        val preflight = preflight()
        if (!preflight.success) return preflight
        val result = RootAccess.runFixed(RESTORE_COMMAND, timeoutSeconds = 30L)
        if (!result.success || !result.output.contains("HYPER_ICONPACK_RESTORE_OK")) {
            return Result(false, result.output)
        }
        return refreshAfterThemeMutation(result.output, action = "恢复")
    }

    /**
     * HyperOS Theme Manager emits this dynamic broadcast after a theme
     * transaction. We are writing the same private `icons` archive directly,
     * so retain its final notification instead of killing the launcher. The
     * launcher-side Xposed runtime additionally observes the post-install
     * configuration revision and invokes MiuiConfiguration's native cache
     * invalidation API before re-sending this notification.
     */
    private fun refreshAfterThemeMutation(installOutput: String, action: String): Result {
        val refresh = RootAccess.runFixed(THEME_CHANGED_BROADCAST, timeoutSeconds = 20L)
        return if (refresh.success && refresh.output.contains("HYPER_ICONPACK_THEME_REFRESH_OK")) {
            Result(true, "$installOutput\nHYPER_ICONPACK_THEME_REFRESH_OK")
        } else {
            // The archive is already atomically active.  Preserve that success
            // rather than claiming an install failure merely because an OEM
            // removed/renamed a dynamic receiver on a future build.
            Result(true, "$installOutput\n主题已$action；主题刷新广播未确认，请返回桌面后稍候刷新。${refresh.output}")
        }
    }

    /**
     * Reports whether the active /data/system/theme/icons archive is still
     * exactly the archive installed by this module.  The check deliberately
     * uses the Root-owned hash marker instead of trusting a stale UI flag.
     */
    fun status(): Result {
        val preflight = preflight()
        if (!preflight.success) return preflight
        val result = RootAccess.runFixed(STATUS_COMMAND, timeoutSeconds = 30L)
        return Result(result.success && result.output.contains("HYPER_ICONPACK_THEME_ACTIVE"), result.output)
    }

    private val PREFLIGHT_COMMAND = """
        id
        if [ ! -d '$THEME_DIRECTORY' ]; then
          echo '未找到 HyperOS 主题目录：$THEME_DIRECTORY'
          exit 71
        fi
        if [ ! -r '$THEME_DIRECTORY' ] || [ ! -w '$THEME_DIRECTORY' ]; then
          echo 'Root 进程没有 theme_data_file 的读写权限（通常是 SELinux / KernelSU 域限制）。'
          ls -ldZ '$THEME_DIRECTORY' 2>&1
          exit 73
        fi
        echo 'HYPER_ICONPACK_THEME_WRITE_READY'
    """.trimIndent()

    private val INSTALL_COMMAND = """
        set -e
        theme_dir='$THEME_DIRECTORY'
        target='$TARGET'
        temporary='$TEMPORARY'
        backup='$BACKUP'
        no_original='$NO_ORIGINAL_MARKER'
        active_hash='$ACTIVE_HASH_MARKER'
        hash_temporary='$HASH_TEMPORARY'
        dynamic_target='$DYNAMIC_TARGET'
        dynamic_temporary='$DYNAMIC_TEMPORARY'
        dynamic_none='$DYNAMIC_NONE_MARKER'
        dynamic_backup="${'$'}theme_dir/.hypericonpack-dynamicicons.backup"
        dynamic_no_original="${'$'}theme_dir/.hypericonpack-dynamicicons.no-original"
        cat > "${'$'}temporary"
        [ -s "${'$'}temporary" ]
        owner="${'$'}(stat -c %u "${'$'}theme_dir")"
        group="${'$'}(stat -c %g "${'$'}theme_dir")"
        chown "${'$'}owner:${'$'}group" "${'$'}temporary"
        chmod 0644 "${'$'}temporary"
        label="${'$'}(ls -Zd "${'$'}temporary" | awk '{print ${'$'}1}')"
        case "${'$'}label" in
          u:object_r:theme_data_file:*) ;;
          *) echo "临时文件 SELinux 标签错误：${'$'}label"; exit 75 ;;
        esac
        if [ ! -e "${'$'}backup" ] && [ ! -e "${'$'}no_original" ]; then
          if [ -e "${'$'}target" ]; then
            cp -p "${'$'}target" "${'$'}backup"
          else
            : > "${'$'}no_original"
            chown "${'$'}owner:${'$'}group" "${'$'}no_original"
            chmod 0600 "${'$'}no_original"
          fi
        fi
        if [ ! -e "${'$'}dynamic_backup" ] && [ ! -e "${'$'}dynamic_no_original" ]; then
          if [ -e "${'$'}dynamic_target" ]; then
            cp -p "${'$'}dynamic_target" "${'$'}dynamic_backup"
          else
            : > "${'$'}dynamic_no_original"
            chown "${'$'}owner:${'$'}group" "${'$'}dynamic_no_original"
            chmod 0600 "${'$'}dynamic_no_original"
          fi
        fi
        digest="${'$'}(sha256sum "${'$'}temporary" | awk '{print ${'$'}1}')"
        [ -n "${'$'}digest" ]
        printf '%s\n' "${'$'}digest" > "${'$'}hash_temporary"
        chown "${'$'}owner:${'$'}group" "${'$'}hash_temporary"
        # The archive itself is intentionally world-readable because the
        # framework, Launcher and SystemUI need to open it.  Keep the marker
        # in the same read domain: KernelSU's u:r:ksu:s0 can read `icons` but
        # cannot read a 0600 theme_data_file owned by system_theme.  A 0644
        # marker contains only a SHA-256 digest and lets status/restore make a
        # truthful decision instead of falsely reporting the active theme as
        # lost.
        chmod 0644 "${'$'}hash_temporary"
        mv -f "${'$'}hash_temporary" "${'$'}active_hash"
        mv -f "${'$'}temporary" "${'$'}target"
        chown "${'$'}owner:${'$'}group" "${'$'}target"
        chmod 0644 "${'$'}target"
        if [ -s "${'$'}dynamic_temporary" ]; then
          mv -f "${'$'}dynamic_temporary" "${'$'}dynamic_target"
          chown "${'$'}owner:${'$'}group" "${'$'}dynamic_target"
          chmod 0644 "${'$'}dynamic_target"
        elif [ -f "${'$'}dynamic_none" ]; then
          if [ -f "${'$'}dynamic_backup" ]; then
            cp -p "${'$'}dynamic_backup" "${'$'}dynamic_target"
          elif [ -f "${'$'}dynamic_no_original" ]; then
            rm -f "${'$'}dynamic_target"
          fi
        fi
        rm -f "${'$'}dynamic_none" "${'$'}dynamic_temporary"
        ls -lZ "${'$'}target"
        echo 'HYPER_ICONPACK_INSTALL_OK'
    """.trimIndent()

    private val RESTORE_COMMAND = """
        set -e
        target='$TARGET'
        backup='$BACKUP'
        no_original='$NO_ORIGINAL_MARKER'
        active_hash='$ACTIVE_HASH_MARKER'
        dynamic_target='$DYNAMIC_TARGET'
        dynamic_backup='$THEME_DIRECTORY/.hypericonpack-dynamicicons.backup'
        dynamic_no_original='$THEME_DIRECTORY/.hypericonpack-dynamicicons.no-original'
        if [ ! -f "${'$'}active_hash" ] || [ ! -f "${'$'}target" ]; then
          echo '未找到由 Hyper Icon Pack 管理的活动系统图标主题。'
          exit 81
        fi
        expected="${'$'}(cat "${'$'}active_hash")"
        actual="${'$'}(sha256sum "${'$'}target" | awk '{print ${'$'}1}')"
        if [ "${'$'}expected" != "${'$'}actual" ]; then
          echo '当前 icons 已被主题商店或其他工具更改；为保护新主题，拒绝覆盖。'
          exit 82
        fi
        if [ -f "${'$'}backup" ]; then
          mv -f "${'$'}backup" "${'$'}target"
        elif [ -f "${'$'}no_original" ]; then
          rm -f "${'$'}target" "${'$'}no_original"
        else
          echo '缺少原始 icons 备份状态，拒绝恢复。'
          exit 83
        fi
        rm -f "${'$'}active_hash"
        if [ -f "${'$'}dynamic_backup" ]; then
          mv -f "${'$'}dynamic_backup" "${'$'}dynamic_target"
        elif [ -f "${'$'}dynamic_no_original" ]; then
          rm -f "${'$'}dynamic_target" "${'$'}dynamic_no_original"
        fi
        rm -f '$DYNAMIC_TEMPORARY' '$DYNAMIC_NONE_MARKER'
        echo 'HYPER_ICONPACK_RESTORE_OK'
    """.trimIndent()

    private val STATUS_COMMAND = """
        set -e
        target='$TARGET'
        active_hash='$ACTIVE_HASH_MARKER'
        if [ ! -f "${'$'}target" ] || [ ! -f "${'$'}active_hash" ]; then
          echo 'HYPER_ICONPACK_THEME_INACTIVE'
          exit 0
        fi
        expected="${'$'}(cat "${'$'}active_hash")"
        actual="${'$'}(sha256sum "${'$'}target" | awk '{print ${'$'}1}')"
        if [ "${'$'}expected" = "${'$'}actual" ]; then
          echo 'HYPER_ICONPACK_THEME_ACTIVE'
        else
          echo 'HYPER_ICONPACK_THEME_REPLACED'
        fi
    """.trimIndent()

    private val THEME_CHANGED_BROADCAST = """
        set -e
        am broadcast --user 0 --receiver-foreground -a miui.intent.action.ACTION_THEME_CHANGED
        echo 'HYPER_ICONPACK_THEME_REFRESH_OK'
    """.trimIndent()

    private val STAGE_DYNAMIC_COMMAND = """
        set -e
        temporary='$DYNAMIC_TEMPORARY'
        none='$DYNAMIC_NONE_MARKER'
        rm -f "${'$'}none"
        cat > "${'$'}temporary"
        [ -s "${'$'}temporary" ]
        owner="${'$'}(stat -c %u '$THEME_DIRECTORY')"
        group="${'$'}(stat -c %g '$THEME_DIRECTORY')"
        chown "${'$'}owner:${'$'}group" "${'$'}temporary"
        chmod 0644 "${'$'}temporary"
        echo 'HYPER_ICONPACK_DYNAMIC_STAGE_OK'
    """.trimIndent()

    private val STAGE_NO_DYNAMIC_COMMAND = """
        set -e
        rm -f '$DYNAMIC_TEMPORARY'
        : > '$DYNAMIC_NONE_MARKER'
        owner="${'$'}(stat -c %u '$THEME_DIRECTORY')"
        group="${'$'}(stat -c %g '$THEME_DIRECTORY')"
        chown "${'$'}owner:${'$'}group" '$DYNAMIC_NONE_MARKER'
        chmod 0600 '$DYNAMIC_NONE_MARKER'
        echo 'HYPER_ICONPACK_DYNAMIC_STAGE_OK'
    """.trimIndent()
}
