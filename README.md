# Q 音助手

适配新版 QQ 音乐 Android 客户端的 Xposed 模块。由 [xiangfa-test](https://github.com/xiangfa-test/QMHelper) 继续维护，感谢 [zjns](https://github.com/zjns/QMHelper) 开发原版。

## 安装

1. 从 [GitHub Releases](https://github.com/xiangfa-test/QMHelper/releases/latest) 或 [Xposed 模块仓库](https://modules.lsposed.org/module/io.github.xiangfa_test.qmhelper) 下载并安装 APK。
2. 在支持 libxposed API 102 的 LSPosed 中启用 Q 音助手，勾选 QQ 音乐（`com.tencent.qqmusic`）作为作用域。
3. 重启 QQ 音乐，从「我的 → 更多 → Q 音助手」打开模块设置。

模块包名为 `io.github.xiangfa_test.qmhelper`。如果手机已装旧包 `me.kofua.qmhelper`，请在 LSPosed 中关闭旧模块并启用新模块，避免两份模块同时注入 QQ 音乐。

本版本以 QQ 音乐 Android 21.0.5.5（7755）为适配目标。宿主升级后，部分功能可能需要重新适配。

## 功能

- 首页顶部标签、搜索、播放页、评论区和「我的」页净化
- 按项隐藏「更多」页条目及顶部快捷入口
- 强制关闭「锁屏图片设置 → 使用QQ音乐锁屏」，QQ音乐不再接管锁屏图片
- 解密已下载音乐并自动补回专辑封面（FLAC / MP3）
- 普通每日签到和会员星动福利站自动签到
- 模块配置的 XML 导入与导出

## Telegram 频道

- 原作者频道 [@bb_show](https://t.me/bb_show)
- 维护者频道 [@xiangfa_channel](https://t.me/xiangfa_channel) 和 [@onelazyfish](https://t.me/onelazyfish)

## 许可证

[GNU General Public License v3.0](LICENSE)
