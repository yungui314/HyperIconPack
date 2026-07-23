package io.github.cl0ura.hypericonpack.systemtheme

import io.github.cl0ura.hypericonpack.root.RootAccess
import java.io.File

/**
 * Root installer for a previously validated HyperOS icon archive.
 *
 * All paths and shell fragments are constants.  The selected icon-pack name,
 * archive path and other UI text are never interpolated into a shell command.
 * Archive bytes are written through stdin to avoid granting a shell process
 * access to the app's private storage.
 *
 * Management markers live under /data/system/theme/.runtime/hypericonpack so
 * ThemeRuntimeManager's DRM restoreDefault path does not delete the app's
 * ownership state while it only rewrites the icons archive itself.
 */
internal object RootThemeIconInstaller {
    private const val THEME_DIRECTORY = "/data/system/theme"
    private const val RUNTIME_STATE_DIRECTORY = "$THEME_DIRECTORY/.runtime/hypericonpack"
    private const val TARGET = "$THEME_DIRECTORY/icons"
    private const val TEMPORARY = "$RUNTIME_STATE_DIRECTORY/icons.new"
    private const val BACKUP = "$RUNTIME_STATE_DIRECTORY/icons.backup"
    private const val NO_ORIGINAL_MARKER = "$RUNTIME_STATE_DIRECTORY/icons.no-original"
    private const val ACTIVE_HASH_MARKER = "$RUNTIME_STATE_DIRECTORY/icons.sha256"
    private const val HASH_TEMPORARY = "$RUNTIME_STATE_DIRECTORY/icons.sha256.new"
    private const val LEGACY_BACKUP = "$THEME_DIRECTORY/.hypericonpack-icons.backup"
    private const val LEGACY_NO_ORIGINAL_MARKER = "$THEME_DIRECTORY/.hypericonpack-icons.no-original"
    private const val LEGACY_ACTIVE_HASH_MARKER = "$THEME_DIRECTORY/.hypericonpack-icons.sha256"
    private const val DYNAMIC_TARGET = "$THEME_DIRECTORY/dynamicicons"
    private const val DYNAMIC_TEMPORARY = "$RUNTIME_STATE_DIRECTORY/dynamicicons.new"
    private const val DYNAMIC_NONE_MARKER = "$RUNTIME_STATE_DIRECTORY/dynamicicons.none"
    private const val DYNAMIC_BACKUP = "$RUNTIME_STATE_DIRECTORY/dynamicicons.backup"
    private const val DYNAMIC_NO_ORIGINAL_MARKER = "$RUNTIME_STATE_DIRECTORY/dynamicicons.no-original"
    private const val LEGACY_DYNAMIC_BACKUP = "$THEME_DIRECTORY/.hypericonpack-dynamicicons.backup"
    private const val LEGACY_DYNAMIC_NO_ORIGINAL = "$THEME_DIRECTORY/.hypericonpack-dynamicicons.no-original"
    private const val LEGACY_DYNAMIC_NONE_MARKER = "$THEME_DIRECTORY/.hypericonpack-dynamicicons.none"

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

    fun install(archive: File): Result = ThemeArchiveMutationGate.withLock {
        installLocked(archive)
    }

    private fun installLocked(archive: File): Result {
        if (!archive.isFile || archive.length() == 0L) {
            return Result(false, "未找到已验证的主题归档，请先执行“转换为 HyperOS 主题资源”。")
        }
        val preflight = preflight()
        if (!preflight.success) return preflight
        val installArchive = runCatching {
            HyperOsIconArchiveConverter.ensureUseDynamicIconTransformConfig(
                iconArchive = archive,
                // Calendar MAML entries are embedded in the main icons ZIP.
                // Keeping this false prevents HyperOS' stock dynamicicons
                // Adaptive trees from overriding pack-shaped classic frames.
                enableDynamicIcons = false,
            )
        }.getOrElse { throwable ->
            return Result(false, throwable.message ?: "无法准备主题归档")
        }
        val dynamicStage = RootAccess.runFixed(STAGE_NO_DYNAMIC_COMMAND, timeoutSeconds = 20L)
        if (!dynamicStage.success || !dynamicStage.output.contains("HYPER_ICONPACK_DYNAMIC_STAGE_OK")) {
            return Result(false, dynamicStage.output)
        }
        val result = RootAccess.runFixedWithInput(
            command = INSTALL_COMMAND,
            input = installArchive.inputStream(),
            timeoutSeconds = 120L,
        )
        if (!result.success || !result.output.contains("HYPER_ICONPACK_INSTALL_OK")) {
            return Result(false, result.output)
        }
        return Result(true, result.output)
    }

    /**
     * Restores exactly the file this installer last activated.  A hash marker
     * protects a newer theme selected in Theme Manager from accidental
     * deletion: if the current icons archive no longer matches our marker, the
     * operation stops without changing anything.
     */
    fun restore(): Result = ThemeArchiveMutationGate.withLock {
        restoreLocked()
    }

    private fun restoreLocked(): Result {
        val preflight = preflight()
        if (!preflight.success) return preflight
        val result = RootAccess.runFixed(RESTORE_COMMAND, timeoutSeconds = 30L)
        if (!result.success || !result.output.contains("HYPER_ICONPACK_RESTORE_OK")) {
            return Result(false, result.output)
        }
        return Result(true, result.output)
    }

    /**
     * Reports whether the active /data/system/theme/icons archive is still
     * exactly the archive installed by this module.  The check deliberately
     * uses the Root-owned hash marker instead of trusting a stale UI flag.
     */
    fun status(): Result = ThemeArchiveMutationGate.withLock {
        statusLocked()
    }

    private fun statusLocked(): Result {
        val preflight = preflight()
        if (!preflight.success) return preflight
        val result = RootAccess.runFixed(STATUS_COMMAND, timeoutSeconds = 30L)
        return Result(result.success && result.output.contains("HYPER_ICONPACK_THEME_ACTIVE"), result.output)
    }

    fun activeArchiveSha256(status: Result): String? = ACTIVE_HASH_PATTERN
        .find(status.output)
        ?.groupValues
        ?.getOrNull(1)
        ?.lowercase()

    private val PREFLIGHT_COMMAND = """
        id
        if [ ! -d '$THEME_DIRECTORY' ]; then
          echo '未找到 HyperOS 主题目录：$THEME_DIRECTORY'
          exit 71
        fi
        mkdir -p '$RUNTIME_STATE_DIRECTORY'
        owner="${'$'}(stat -c %u '$THEME_DIRECTORY')"
        group="${'$'}(stat -c %g '$THEME_DIRECTORY')"
        chown "${'$'}owner:${'$'}group" '$THEME_DIRECTORY/.runtime' 2>/dev/null || true
        chmod 0755 '$THEME_DIRECTORY/.runtime' 2>/dev/null || true
        chcon u:object_r:theme_data_file:s0 '$THEME_DIRECTORY/.runtime' 2>/dev/null || true
        chown "${'$'}owner:${'$'}group" '$RUNTIME_STATE_DIRECTORY' 2>/dev/null || true
        chmod 0755 '$RUNTIME_STATE_DIRECTORY' 2>/dev/null || true
        chcon u:object_r:theme_data_file:s0 '$RUNTIME_STATE_DIRECTORY' 2>/dev/null || true
        touch '$RUNTIME_STATE_DIRECTORY/.write-probe'
        rm -f '$RUNTIME_STATE_DIRECTORY/.write-probe'
        echo 'HYPER_ICONPACK_ROOT_OK'
    """.trimIndent()

    private val INSTALL_COMMAND = """
        set -e
        theme_dir='$THEME_DIRECTORY'
        state_dir='$RUNTIME_STATE_DIRECTORY'
        target='$TARGET'
        temporary='$TEMPORARY'
        backup='$BACKUP'
        no_original='$NO_ORIGINAL_MARKER'
        active_hash='$ACTIVE_HASH_MARKER'
        hash_temporary='$HASH_TEMPORARY'
        legacy_backup='$LEGACY_BACKUP'
        legacy_no_original='$LEGACY_NO_ORIGINAL_MARKER'
        legacy_active_hash='$LEGACY_ACTIVE_HASH_MARKER'
        dynamic_target='$DYNAMIC_TARGET'
        dynamic_temporary='$DYNAMIC_TEMPORARY'
        dynamic_none='$DYNAMIC_NONE_MARKER'
        dynamic_backup='$DYNAMIC_BACKUP'
        dynamic_no_original='$DYNAMIC_NO_ORIGINAL_MARKER'
        legacy_dynamic_backup='$LEGACY_DYNAMIC_BACKUP'
        legacy_dynamic_no_original='$LEGACY_DYNAMIC_NO_ORIGINAL'
        legacy_dynamic_none='$LEGACY_DYNAMIC_NONE_MARKER'
        mkdir -p "${'$'}state_dir"
        owner="${'$'}(stat -c %u "${'$'}theme_dir")"
        group="${'$'}(stat -c %g "${'$'}theme_dir")"
        # HyperOS keeps /data/system/theme/.runtime as 0700 with a restricted
        # SELinux category.  system_server must traverse it and read our managed
        # marker; otherwise ThemeReceiver deletes hand-installed icons.
        runtime_root="${'$'}theme_dir/.runtime"
        chown "${'$'}owner:${'$'}group" "${'$'}runtime_root" 2>/dev/null || true
        chmod 0755 "${'$'}runtime_root" 2>/dev/null || true
        chcon u:object_r:theme_data_file:s0 "${'$'}runtime_root" 2>/dev/null || true
        chown "${'$'}owner:${'$'}group" "${'$'}state_dir" 2>/dev/null || true
        chmod 0755 "${'$'}state_dir" 2>/dev/null || true
        chcon u:object_r:theme_data_file:s0 "${'$'}state_dir" 2>/dev/null || true
        # Migrate legacy root-level markers into the DRM-safe runtime folder.
        if [ ! -e "${'$'}backup" ] && [ -e "${'$'}legacy_backup" ]; then
          mv -f "${'$'}legacy_backup" "${'$'}backup"
        fi
        if [ ! -e "${'$'}no_original" ] && [ -e "${'$'}legacy_no_original" ]; then
          mv -f "${'$'}legacy_no_original" "${'$'}no_original"
        fi
        if [ ! -e "${'$'}active_hash" ] && [ -e "${'$'}legacy_active_hash" ]; then
          mv -f "${'$'}legacy_active_hash" "${'$'}active_hash"
        fi
        if [ ! -e "${'$'}dynamic_backup" ] && [ -e "${'$'}legacy_dynamic_backup" ]; then
          mv -f "${'$'}legacy_dynamic_backup" "${'$'}dynamic_backup"
        fi
        if [ ! -e "${'$'}dynamic_no_original" ] && [ -e "${'$'}legacy_dynamic_no_original" ]; then
          mv -f "${'$'}legacy_dynamic_no_original" "${'$'}dynamic_no_original"
        fi
        if [ ! -e "${'$'}dynamic_none" ] && [ -e "${'$'}legacy_dynamic_none" ]; then
          mv -f "${'$'}legacy_dynamic_none" "${'$'}dynamic_none"
        fi
        rm -f "${'$'}legacy_backup" "${'$'}legacy_no_original" "${'$'}legacy_active_hash" \
          "${'$'}legacy_dynamic_backup" "${'$'}legacy_dynamic_no_original" "${'$'}legacy_dynamic_none" \
          "${'$'}theme_dir/.hypericonpack-icons.new" "${'$'}theme_dir/.hypericonpack-icons.sha256.new" \
          "${'$'}theme_dir/.hypericonpack-dynamicicons.new"
        cat > "${'$'}temporary"
        [ -s "${'$'}temporary" ]
        chown "${'$'}owner:${'$'}group" "${'$'}temporary"
        chmod 0644 "${'$'}temporary"
        label="${'$'}(ls -Zd "${'$'}temporary" | awk '{print ${'$'}1}')"
        if [ -n "${'$'}label" ] && [ "${'$'}label" != "?" ]; then
          chcon "${'$'}label" "${'$'}temporary" 2>/dev/null || true
        fi
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
        # Keep the managed marker only under .runtime/hypericonpack.  A root-level
        # marker is outside ThemeReceiver's whitelist and gets deleted on every
        # CHECK_TIME_UP, so dual-writing there is counterproductive.
        rm -f "${'$'}legacy_active_hash" 2>/dev/null || true
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
        legacy_backup='$LEGACY_BACKUP'
        legacy_no_original='$LEGACY_NO_ORIGINAL_MARKER'
        legacy_active_hash='$LEGACY_ACTIVE_HASH_MARKER'
        dynamic_target='$DYNAMIC_TARGET'
        dynamic_backup='$DYNAMIC_BACKUP'
        dynamic_no_original='$DYNAMIC_NO_ORIGINAL_MARKER'
        legacy_dynamic_backup='$LEGACY_DYNAMIC_BACKUP'
        legacy_dynamic_no_original='$LEGACY_DYNAMIC_NO_ORIGINAL'
        if [ ! -f "${'$'}active_hash" ] && [ -f "${'$'}legacy_active_hash" ]; then
          active_hash="${'$'}legacy_active_hash"
        fi
        if [ ! -f "${'$'}backup" ] && [ -f "${'$'}legacy_backup" ]; then
          backup="${'$'}legacy_backup"
        fi
        if [ ! -f "${'$'}no_original" ] && [ -f "${'$'}legacy_no_original" ]; then
          no_original="${'$'}legacy_no_original"
        fi
        if [ ! -f "${'$'}dynamic_backup" ] && [ -f "${'$'}legacy_dynamic_backup" ]; then
          dynamic_backup="${'$'}legacy_dynamic_backup"
        fi
        if [ ! -f "${'$'}dynamic_no_original" ] && [ -f "${'$'}legacy_dynamic_no_original" ]; then
          dynamic_no_original="${'$'}legacy_dynamic_no_original"
        fi
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
        rm -f "${'$'}active_hash" '$LEGACY_ACTIVE_HASH_MARKER' '$LEGACY_BACKUP' '$LEGACY_NO_ORIGINAL_MARKER'
        if [ -f "${'$'}dynamic_backup" ]; then
          mv -f "${'$'}dynamic_backup" "${'$'}dynamic_target"
        elif [ -f "${'$'}dynamic_no_original" ]; then
          rm -f "${'$'}dynamic_target" "${'$'}dynamic_no_original"
        fi
        rm -f '$DYNAMIC_TEMPORARY' '$DYNAMIC_NONE_MARKER' \
          '$LEGACY_DYNAMIC_BACKUP' '$LEGACY_DYNAMIC_NO_ORIGINAL' '$LEGACY_DYNAMIC_NONE_MARKER'
        rm -f "${'$'}legacy_active_hash" 2>/dev/null || true
        echo 'HYPER_ICONPACK_RESTORE_OK'
    """.trimIndent()

    private val STATUS_COMMAND = """
        set -e
        target='$TARGET'
        active_hash='$ACTIVE_HASH_MARKER'
        legacy_active_hash='$LEGACY_ACTIVE_HASH_MARKER'
        if [ ! -f "${'$'}active_hash" ] && [ -f "${'$'}legacy_active_hash" ]; then
          active_hash="${'$'}legacy_active_hash"
        fi
        if [ ! -f "${'$'}target" ] || [ ! -f "${'$'}active_hash" ]; then
          echo 'HYPER_ICONPACK_THEME_INACTIVE'
          exit 0
        fi
        expected="${'$'}(cat "${'$'}active_hash")"
        actual="${'$'}(sha256sum "${'$'}target" | awk '{print ${'$'}1}')"
        if [ "${'$'}expected" = "${'$'}actual" ]; then
          echo "HYPER_ICONPACK_THEME_ACTIVE ${'$'}actual"
        else
          echo 'HYPER_ICONPACK_THEME_REPLACED'
        fi
    """.trimIndent()

    private val ACTIVE_HASH_PATTERN = Regex("HYPER_ICONPACK_THEME_ACTIVE\\s+([0-9a-fA-F]{64})")

    private val STAGE_DYNAMIC_COMMAND = """
        set -e
        mkdir -p '$RUNTIME_STATE_DIRECTORY'
        temporary='$DYNAMIC_TEMPORARY'
        none='$DYNAMIC_NONE_MARKER'
        rm -f "${'$'}none" '$LEGACY_DYNAMIC_NONE_MARKER'
        cat > "${'$'}temporary"
        [ -s "${'$'}temporary" ]
        owner="${'$'}(stat -c %u '$THEME_DIRECTORY')"
        group="${'$'}(stat -c %g '$THEME_DIRECTORY')"
        chown "${'$'}owner:${'$'}group" '$THEME_DIRECTORY/.runtime' 2>/dev/null || true
        chmod 0755 '$THEME_DIRECTORY/.runtime' 2>/dev/null || true
        chcon u:object_r:theme_data_file:s0 '$THEME_DIRECTORY/.runtime' 2>/dev/null || true
        chown "${'$'}owner:${'$'}group" '$RUNTIME_STATE_DIRECTORY' 2>/dev/null || true
        chmod 0755 '$RUNTIME_STATE_DIRECTORY' 2>/dev/null || true
        chcon u:object_r:theme_data_file:s0 '$RUNTIME_STATE_DIRECTORY' 2>/dev/null || true
        chown "${'$'}owner:${'$'}group" "${'$'}temporary"
        chmod 0644 "${'$'}temporary"
        echo 'HYPER_ICONPACK_DYNAMIC_STAGE_OK'
    """.trimIndent()

    private val STAGE_NO_DYNAMIC_COMMAND = """
        set -e
        mkdir -p '$RUNTIME_STATE_DIRECTORY'
        rm -f '$DYNAMIC_TEMPORARY' '$LEGACY_DYNAMIC_NONE_MARKER'
        : > '$DYNAMIC_NONE_MARKER'
        owner="${'$'}(stat -c %u '$THEME_DIRECTORY')"
        group="${'$'}(stat -c %g '$THEME_DIRECTORY')"
        chown "${'$'}owner:${'$'}group" '$THEME_DIRECTORY/.runtime' 2>/dev/null || true
        chmod 0755 '$THEME_DIRECTORY/.runtime' 2>/dev/null || true
        chcon u:object_r:theme_data_file:s0 '$THEME_DIRECTORY/.runtime' 2>/dev/null || true
        chown "${'$'}owner:${'$'}group" '$RUNTIME_STATE_DIRECTORY' 2>/dev/null || true
        chmod 0755 '$RUNTIME_STATE_DIRECTORY' 2>/dev/null || true
        chcon u:object_r:theme_data_file:s0 '$RUNTIME_STATE_DIRECTORY' 2>/dev/null || true
        chown "${'$'}owner:${'$'}group" '$DYNAMIC_NONE_MARKER'
        chmod 0600 '$DYNAMIC_NONE_MARKER'
        echo 'HYPER_ICONPACK_DYNAMIC_STAGE_OK'
    """.trimIndent()
}
