# SR 音效轮盘 Android 版

SR Voiceover Roulette 的 Android 移植版，支持 Android 8.0 及以上版本。可通过应用内轮盘、跨应用悬浮轮盘或实验性的固定触发点播放音效。支持导入本地音频、创建播放集，以及调整音量、音调和播放速度。

跨应用使用时，请在系统设置中授予悬浮窗权限。

## 构建

用 Android Studio 打开仓库根目录，安装 Android SDK Platform 36 和 Build-Tools 35.0.0，使用 JDK 17 和 Gradle 8.13 构建。命令行可执行：

```sh
gradle :app:assembleDebug
```

构建生成的 APK 会复制到 `DL/`。

## 许可

项目代码采用 [MIT 许可证](LICENSE)。内置演示音效的版权归各自权利人。

## 作者

原作者：迟昼（B 站账号：缪缪缪尔赛思）

源码：[ChiZhou6/SR-Voiceover-Roulette](https://github.com/ChiZhou6/SR-Voiceover-Roulette)

移植手机版本作者：于羽悠布德（B站）。
