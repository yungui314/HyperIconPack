# Hyper Icon Pack

<p align="center">
  <img src="docs/app-icon.svg" width="112" alt="Hyper Icon Pack" />
</p>

<p align="center">
  将标准 Android 图标包转换为 Xiaomi HyperOS 可读取的系统主题图标资源。
</p>

<p align="center">
  <a href="https://github.com/yungui314/HyperIconPack/releases"><img alt="Release" src="https://img.shields.io/github/v/release/yungui314/HyperIconPack?include_prereleases&style=flat-square"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/yungui314/HyperIconPack?style=flat-square"></a>
  <img alt="Android" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="Root" src="https://img.shields.io/badge/Root-required-E95420?style=flat-square">
  <img alt="LSPosed" src="https://img.shields.io/badge/LSPosed-required-5B6ACD?style=flat-square">
</p>

## 项目简介

Hyper Icon Pack 读取 Nova、ADW、Lawnchair 等通用图标包使用的 `appfilter.xml`，生成 HyperOS 私有 `icons` 主题归档，并通过 Root 安装到系统主题目录。普通图标最终由系统自己的 `IconCustomizer` 读取；Xposed 仅用于补齐桌面动画、设置、手机管家和 SystemUI 等系统接口的显示差异。

```text
appfilter.xml / 本机应用图标
        ↓
Hyper Icon Pack 转换器
        ↓
/data/system/theme/icons
        ↓
HyperOS 桌面、文件夹、设置与系统界面
```

主要能力：

- 转换标准 `appfilter.xml` 图标包，并为已安装应用生成包级资源。
- 对图标包未覆盖的应用套用其 `iconback`、`iconmask`、`iconupon` 与缩放规则。
- 可选全局 Monet 转换和自定义 Monet 配色。
- 转换结果保存为存档，之后切换同一存档无需重新生成。
- Root 原子安装、完整性校验、自动备份和一键恢复系统原图标。
- 新安装应用可增量补充进当前主题归档。
- 支持 HyperOS 动态日历资源；动态时钟仍在研究中。

## 效果展示

<table>
  <tr>
    <th>Pure Icon Pack · 原始配色</th>
    <th>Pure Icon Pack · Monet</th>
    <th>莫奈线条 · Monet</th>
  </tr>
  <tr>
    <td><img src="docs/screenshots/pure-icon-pack-original.jpg" alt="Pure Icon Pack 的原始配色转换效果" width="280"></td>
    <td><img src="docs/screenshots/pure-icon-pack-monet.jpg" alt="Pure Icon Pack 的 Monet 转换效果" width="280"></td>
    <td><img src="docs/screenshots/monet-line-icon-pack.jpg" alt="莫奈线条图标包的 Monet 转换效果" width="280"></td>
  </tr>
</table>

## 已测试环境

目前主要在以下设备上开发和验证：

| 项目 | 测试环境 |
| --- | --- |
| 设备 | Xiaomi 14 |
| 系统 | Xiaomi HyperOS `3.0.303.0` |
| 系统桌面 | `RELEASE-6.01.05.2407-06081949` |
| Root / Hook | Root + LSPosed |

> [!WARNING]
> 本项目最初为个人自用工具，目前没有在更多机型、HyperOS 版本或系统桌面版本上完成系统性测试，因此不保证其他环境可用。建议在操作前准备可恢复方案，并自行承担修改系统主题资源的风险。欢迎提交不同设备的测试反馈，也欢迎有能力的开发者贡献代码。

## 使用方法

1. 安装 APK，在 LSPosed 中启用模块。
2. 勾选推荐作用域：
   - 系统桌面 `com.miui.home`
   - 系统界面 `com.android.systemui`
   - 设置 `com.android.settings`
   - 手机管家 `com.miui.securitycenter`
   - 小组件中心 `com.miui.personalassistant`
   - 若设备存在，可额外勾选 `com.xiaomi.misettings`
3. 打开“设置 > 制作图标包”，选择图标来源、适配比例和 Monet 设置。
4. 点击“转换并保存”，等待图标存档生成。
5. 在“图标存档”中选择刚生成的存档并应用。
6. 按页面提示重启桌面或设备，使系统各进程重新读取主题资源。

转换会覆盖完整 `appfilter.xml` 映射，并为当前系统已安装的第三方应用、系统应用、禁用应用、无桌面入口应用和 Activity Alias 生成必要的包级资源。未适配内容比例建议从 `85%` 开始，再按图标包风格调整。

## 实现边界

- 本项目转换的是应用图标，不是包含字体、状态栏信号、电池、Framework 和 MAML 的完整主题商店主题。
- SystemUI 只能替换有明确应用来源的通知图标；Wi-Fi、信号、电池等系统状态图标无法从 `appfilter.xml` 推导。
- 动态日历会生成 HyperOS `dynamicicons` 资源；图标包若没有可分离的动态时钟图层，无法可靠生成动态时钟。
- Monet 转换需要从复杂位图中提取前景层级，渐变、半透明和极细线条图标仍可能与源图存在视觉差异。

## 反馈与贡献

提交问题时建议附上：

- 设备型号、HyperOS 完整版本和系统桌面版本；
- 图标包名称与版本；
- 是否开启 Monet、自定义配色及适配比例；
- 应用内“日志”页面中的应用日志和 Xposed 日志；
- 能复现问题的截图和操作步骤。

Issue、测试结果和 Pull Request 都欢迎。请尽量保持修改范围清晰，并说明真机验证环境。

## 许可证

本项目采用 [MIT License](LICENSE)。

## 感谢

- [Global Icon Pack](https://github.com/RichardLuo0/global-icon-pack-android)
- [Miuix](https://github.com/compose-miuix-ui/miuix)
- [Lawnchair](https://github.com/LawnchairLauncher/lawnchair)
- [Neo Launcher](https://github.com/NeoApplications/Neo-Launcher)
- [Kvaesitso](https://github.com/MM2-0/Kvaesitso)
- [Trebuchet](https://github.com/LineageOS/android_packages_apps_Trebuchet)

本项目部分方法和实现参考了以上开源项目。
