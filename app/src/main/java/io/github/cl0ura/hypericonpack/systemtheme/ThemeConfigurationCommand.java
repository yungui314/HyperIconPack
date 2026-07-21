package io.github.cl0ura.hypericonpack.systemtheme;

import java.lang.reflect.Method;

/**
 * Root-only entry that mirrors Theme Manager's icon-module refresh core:
 *
 * <pre>
 * IconCustomizer.clearCustomizedIcons(null)
 * ThemeResourcesSystem.resetIcons() when available
 * MiuiConfiguration.sendThemeConfigurationChangeMsg(THEME_FLAG_ICON)
 * </pre>
 *
 * Launched through {@code app_process}.  Broadcasts and app-widget updates are
 * issued by the surrounding Root shell command so this class stays free of a
 * process Context.
 */
public final class ThemeConfigurationCommand {
    private static final long THEME_FLAG_ICON = 0x8L;

    private ThemeConfigurationCommand() {
    }

    public static void main(String[] args) throws Exception {
        Class<?> iconCustomizer = Class.forName("miui.content.res.IconCustomizer");
        Method clearCustomizedIcons = iconCustomizer.getMethod("clearCustomizedIcons", String.class);
        clearCustomizedIcons.invoke(null, new Object[] { null });

        try {
            Class<?> themeResources = Class.forName("miui.content.res.ThemeResources");
            Method getSystem = themeResources.getMethod("getSystem");
            Object system = getSystem.invoke(null);
            if (system != null) {
                Method resetIcons = system.getClass().getMethod("resetIcons");
                resetIcons.invoke(system);
            }
        } catch (Throwable ignored) {
            try {
                Class<?> themeResourcesSystem = Class.forName("miui.content.res.ThemeResourcesSystem");
                Method getSystem = themeResourcesSystem.getMethod("getSystem");
                Object system = getSystem.invoke(null);
                if (system != null) {
                    Method resetIcons = system.getClass().getMethod("resetIcons");
                    resetIcons.invoke(system);
                }
            } catch (Throwable ignoredAgain) {
                // Configuration change alone still refreshes most consumers.
            }
        }

        Class<?> miuiConfiguration = Class.forName("android.content.res.MiuiConfiguration");
        Method sendThemeConfigurationChangeMsg =
                miuiConfiguration.getMethod("sendThemeConfigurationChangeMsg", long.class);
        sendThemeConfigurationChangeMsg.invoke(null, THEME_FLAG_ICON);

        System.out.println("HYPER_ICONPACK_THEME_CONFIGURATION_UPDATED");
    }
}
