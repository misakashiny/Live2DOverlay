这个目录存放 APK 内置的 Live2D 离线运行库。

需要以下 4 个文件（缺省时由 build.gradle 的 prepareOfflineLive2DRuntime 任务尝试下载）：
- pixi.min.js                    (PixiJS 6.5.10)
- pixi-live2d-display.min.js     (pixi-live2d-display 0.4.0)
- live2dcubismcore.min.js        (Cubism 4 核心)
- live2d.min.js                  (Cubism 2 兼容层)

若编译环境无法访问外网，手动下载上述 4 个文件放入本目录即可。
只要文件存在且大于 1KB，Gradle 任务就不会覆盖。

运行时加载顺序（三级兜底）：
1. file:///android_asset/live2d/runtime/           APK 内置离线库（推荐，车机首选）
2. file:///sdcard/Live2DOverlay/live2d/runtime/    U 盘/文件管理器手动放置
3. https://cdn.jsdelivr.net/...                    CDN 兜底（需联网）
