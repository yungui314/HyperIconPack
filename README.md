# Hyper Icon Pack

面向 Xiaomi HyperOS 的 Root 主题资源转换器。它将 Nova、ADW、Lawnchair 等图标包公开使用的 `appfilter.xml` 转换为 HyperOS 实际读取的私有 `icons` ZIP，再由系统自己的 `IconCustomizer` 提供应用图标。

当前版本不再实时解析或加载第三方图标包。普通图标始终只来自一次
转换后安装的静态主题归档；Xposed 只保留 HyperOS 无法自行覆盖的三个
显示桥接：

```text
com.miui.home
  启动/返回动画期间的形状桥接

com.android.settings / com.miui.securitycenter
  PackageManager 图标读取桥接

com.android.systemui
  应用通知 smallIcon 的静态主题桥接
```

因此普通图标只走一条可重启、可检查、可恢复的系统主题资源路径：

```text
appfilter.xml
  → Hyper Icon Pack 转换器
  → /data/system/theme/icons
  → HyperOS IconCustomizer
  → 桌面 / 文件夹 / 系统中采用该主题 API 的应用图标
```

## 全应用转换范围

转换器使用 `QUERY_ALL_PACKAGES` 读取当前用户的完整安装包列表，包含：

- 第三方应用；
- 系统应用；
- 禁用应用；
- 没有 `MAIN/LAUNCHER` 桌面入口的包；
- Activity-alias 与厂商快捷入口。

每个已安装包都会生成 HyperOS 包级资源：

```text
res/drawable-xxhdpi/<实际包名>.png
```

包名大小写会原样保留。例如以下文件名是不同且有效的 HyperOS 查询目标：

```text
com.MobileTicket.png
com.miHoYo.Yuanshen.png
com.miHoYo.cloudgames.ys.png
```

对桌面启动 Activity，转换器还会保留精确 Component 文件名，兼容标准 Launcher 解析：

```text
res/drawable-xxhdpi/com.example.MainActivity.png
res/drawable-xxhdpi/com.example#other.package.EntryActivity.png
```

选择每个包的图标时，优先级为：

```text
同包 appfilter 明确映射
→ 原始应用图标 + iconback / iconmask / iconupon / scale fallback
→ 跳过无法读取图标资源的异常包
```

这让没有被图标包专门适配的系统应用也得到与图标包一致的边框和形状。未适配内容比例可选 75%、85%、95%、105%；Pure Icon Pack 建议先从 85% 开始。

## 操作步骤

1. 在 LSPosed 中启用模块，并确认以下推荐作用域已勾选：

   ```text
   系统桌面 / com.miui.home
   系统界面 / com.android.systemui
   设置 / com.android.settings
   手机管家 / com.miui.securitycenter

   （若本机存在：com.xiaomi.misettings）
   ```

2. 打开应用，选择图标包和未适配内容比例。

3. 点击“转换所有已安装应用为主题资源”。界面会显示：

   ```text
   解析图标包
   → 转换明确映射
   → 生成全部应用包级资源
   → 校验主题归档
   ```

4. 点击“检查系统主题 Root 访问”，确认显示 `HYPER_ICONPACK_THEME_WRITE_READY`。

5. 点击“应用已转换的全应用系统主题”。安装器会先备份现有 `icons`，再将新 ZIP 原子替换到：

   ```text
   /data/system/theme/icons
   ```

6. 完整重启设备。这样能让 SystemUI、系统设置和桌面从干净进程读取新主题资源；若 KernelSU 不允许杀掉 `com.android.systemui`，应用内“重启系统界面”会失败，此时可使用“重启设备”。

## 启动与返回动画

HyperOS 3 的 `FloatingIconView2` 不把普通 `AdaptiveIconDrawable` 当作可用的原生过渡对象；它要求 Xiaomi 的：

```text
com.miui.home.common.drawable.LayerAdaptiveIconDrawable
```

模块只在动画开始前，将已显示的圆形主题图标构造成这个 vendor Drawable。桌面、文件夹和图标缓存本身不会被 Hook 修改。若无法可靠检测圆形 alpha 轮廓，桥接会安全退回普通 Outline，不会影响应用启动。

## 系统设置与通知栏的边界

本模块生成的是应用图标主题资源，而不是完整 MIUI 主题包。主题商店的完整主题还可能包含 Framework、Settings、MAML、状态栏信号图标和字体组件；第三方 `appfilter.xml` 不含这些资源。

因此：

- Settings / 手机管家中通过 `PackageManager` 获取的**应用图标**会回退读取本归档；
- 状态栏仅替换有明确 `StatusBarNotification` 来源的**应用通知图标**，仍由 SystemUI 负责尺寸、深浅色和对比度处理；
- Wi‑Fi、信号、电池等不是应用图标，不能从 appfilter 推导，也不会被替换；
- 所有桥接仅解码 `/data/system/theme/icons` 的包级 PNG，不会在 SystemUI、设置或桌面进程重新读取图标包 APK。

## 安全与恢复

Root 安装器只执行内置固定命令，不接受任意 shell 文本。它会记录已安装归档的 SHA-256；恢复前若发现主题商店或其他工具已经更换 `icons`，会拒绝覆盖新的主题。
