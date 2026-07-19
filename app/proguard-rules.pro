# Xposed instantiates this class from assets/xposed_init, so it must survive shrinking.
-keep class io.github.cl0ura.hypericonpack.hook.HookEntry { *; }
