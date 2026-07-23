# Xposed instantiates this class from the module entry list, so it must survive shrinking.
-keep class io.github.cl0ura.hypericonpack.hook.HookEntry { *; }

# Root app_process loads this entry by class name after an official theme apply.
-keep class io.github.cl0ura.hypericonpack.systemtheme.ThemeConfigurationCommand {
    public static void main(java.lang.String[]);
}

# Keep package-visible conversion helpers only if reflected (currently not).
# Compose / Kotlin metadata is handled by the Android Gradle Plugin defaults.

# libxposed service binder stubs
-dontwarn io.github.libxposed.**
