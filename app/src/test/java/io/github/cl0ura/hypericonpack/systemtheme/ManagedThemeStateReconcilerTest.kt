package io.github.cl0ura.hypericonpack.systemtheme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedThemeStateReconcilerTest {
    @Test
    fun `clears managed state when root reports theme files are gone`() {
        val shouldClear = ManagedThemeStateReconciler.shouldClearManagedThemeState(
            localSystemThemeActive = true,
            status = RootThemeIconInstaller.Result(false, "HYPER_ICONPACK_THEME_INACTIVE"),
        )

        assertTrue(shouldClear)
    }

    @Test
    fun `clears managed state when root reports icons were replaced externally`() {
        val shouldClear = ManagedThemeStateReconciler.shouldClearManagedThemeState(
            localSystemThemeActive = true,
            status = RootThemeIconInstaller.Result(false, "HYPER_ICONPACK_THEME_REPLACED"),
        )

        assertTrue(shouldClear)
    }

    @Test
    fun `keeps managed state when root cannot verify status`() {
        val shouldClear = ManagedThemeStateReconciler.shouldClearManagedThemeState(
            localSystemThemeActive = true,
            status = RootThemeIconInstaller.Result(false, "Root 进程没有 theme_data_file 的读写权限"),
        )

        assertFalse(shouldClear)
    }

    @Test
    fun `keeps managed state when active marker still matches icons archive`() {
        val shouldClear = ManagedThemeStateReconciler.shouldClearManagedThemeState(
            localSystemThemeActive = true,
            status = RootThemeIconInstaller.Result(
                true,
                "HYPER_ICONPACK_THEME_ACTIVE 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            ),
        )

        assertFalse(shouldClear)
    }

    @Test
    fun `does not clear anything that is already locally inactive`() {
        val shouldClear = ManagedThemeStateReconciler.shouldClearManagedThemeState(
            localSystemThemeActive = false,
            status = RootThemeIconInstaller.Result(false, "HYPER_ICONPACK_THEME_INACTIVE"),
        )

        assertFalse(shouldClear)
    }
}
