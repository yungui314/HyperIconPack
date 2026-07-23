package io.github.cl0ura.hypericonpack.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconSurfaceRefreshCommandTest {
    @Test
    fun `refresh command uses official theme configuration entry`() {
        val command = IconSurfaceRefreshCommand.command

        assertTrue(command.contains("ThemeConfigurationCommand"))
        assertTrue(command.contains("app_process"))
        assertTrue(command.contains("HYPER_ICONPACK_THEME_CONFIGURATION_UPDATED"))
        assertTrue(command.contains("miui.intent.action.ACTION_THEME_CHANGED"))
        assertFalse(command.contains("cmd appwidget"))
        assertFalse(command.contains("dumpsys appwidget"))
        assertFalse(command.contains("APPWIDGET_UPDATE"))
        assertFalse(command.contains("SimpleUsageStatsWidget"))
        assertFalse(command.contains("NormalUsageStatsWidget"))
    }

    @Test
    fun `refresh command does not restart icon consumers`() {
        val command = IconSurfaceRefreshCommand.command

        assertFalse(command.contains("kill -TERM"))
        assertFalse(command.contains("pidof com.miui.home"))
        assertFalse(command.contains("pidof com.android.systemui"))
        assertFalse(command.contains("HYPER_ICONPACK_SURFACES_RESTARTED"))
        assertFalse(command.contains("am force-stop com.miui.home"))
        assertFalse(command.contains("pkill"))
        assertFalse(command.contains("svc power reboot"))
        assertFalse(command.contains("restart"))
    }
}
