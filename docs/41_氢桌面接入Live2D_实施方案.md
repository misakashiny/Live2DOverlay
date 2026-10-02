# 手机改车机：给氢桌面接入 Live2D — 可行性分析与实施方案

目标：车机（手机改的）桌面已用**氢桌面**，想在桌面上加 Live2D 美化。
参考样本：MikuCarLauncher（自带 Live2D）。
分析对象：`氢桌面1.3.0.apk`（`com.mcar.auto` v1.3.0）

---

## 一、先给结论

**不要试图直接改氢桌面的 APK。** 它的核心代码被加密加固了（下面有详证），
你改不了它的 UI 布局。**正确做法是在氢桌面之上叠加一层独立的 Live2D 悬浮窗 App。**

这个方案同时满足三个硬条件：
1. 不用替换氢桌面，氢桌面的方控 / 导航投屏 / OBD / TPMS 等车机功能全部保留；
2. 不碰加固代码，不重打包，不冒签名失效的风险；
3. Live2D 的渲染、动作、位置调整全部由你自己的 App 控制。

---

## 二、为什么不能直接改氢桌面（技术取证）

### 2.1 它被加固了，代码不在 dex 里

| 检查项 | 结果 |
|---|---|
| `classes.dex` 解压后大小 | **仅 3960 字节**（正常 Launcher 至少几 MB） |
| 它包含的类 | 只有 5 个：`jq.vx9.L$1`、`jq.vx9.L`、`jq.vx9.QF`、`jq.vx9.QO`、`qYR.z0.KQTRY8` |
| 它的字符串 | `attachBaseContext`、`attach begin/end`、`loadLibrary`、`so loaded`、`secure`、`tryUse`、`FATAL thread=` → **典型的壳（shell）代码** |
| Manifest 的 `application:name` | `qYR.z0.KQTRY8` → **被替换成壳 Application** |
| Manifest 的 `appComponentFactory` | `jq.vx9.QF` → **被替换成壳工厂** |
| 真实包 `com.mcar.auto.*` 在哪 | 只在 Manifest 里出现（组件声明），**代码不在 dex** |
| `lib/arm64-v8a/libstub.so` | 2.28 MB，**无明文 dex、无明文字符串** → 加密的运行时 |
| `lib/arm64-v8a/liblight.so` | 380 KB，同样无明文 |

**结论**：`com.mcar.auto.*` 的真实字节码被加密塞进了 `libstub.so` / `liblight.so`，
由壳在 `attachBaseContext` 阶段解密后在内存中加载（`DexClassLoader` / `InMemoryDexClassLoader` 路线）。
这是**商业级加固**，不是普通的混淆。

### 2.2 它已经被二次修改过（说明是别人改过的版本）

- 壁纸配置 `res/eB.xml` 里写着：
  `android:settingsActivity="com.mydemo.ui.SettingsActivity"`
- 主包是 `com.mcar.auto`，但这里冒出一个 **`com.mydemo`** —— 这是**二次开发者的包名残留**。
  也就是说手里这份 APK 已经被人动过（加了自定义动态壁纸设置页）。

### 2.3 APK 结构已不完整

- **920 个 zip 条目 CRC-32 校验失败**（`aapt2` 能读，`zipfile` 报 `BadZipFile`），
  说明是**重打包后没有正确回填 CRC**。这类 APK 再改一次极易装不上或崩。
- 签名是自签名（`CN=person`，`SHA256withRSA` 2048 位，2013 生效），**不是官方原始签名**。

### 2.4 综合判断

| 想做的事 | 可行性 |
|---|---|
| 反编译改它的桌面布局加 Live2D | **不可行**。代码加密，改了也编不回 |
| 重打包后重新签名安装 | **高风险**。920 个 CRC 异常 + 加固校验，极可能启动即崩 |
| 在它之上叠加独立悬浮窗 | **可行，且是正解** |

---

## 三、关键发现：氢桌面自带动态壁纸能力

这是本次分析最有价值的一条线索：

```xml
<service android:name="com.mcar.auto.service.LiveWallpaperService"
         android:permission="android.permission.BIND_WALLPAPER"
         android:exported="true">
    <intent-filter>
        <action android:name="android.service.wallpaper.WallpaperService"/>
    </intent-filter>
    <meta-data android:name="android.service.wallpaper"
               android:resource="@xml/wallpaperservice"/>
</service>
```

它注册了**标准动态壁纸服务**（配置指向 `res/eB.xml`，其中 `settingsActivity` 就是那个
`com.mydemo.ui.SettingsActivity`）。同时它还有 `WallpaperActivity` / `WallpaperVideoActivity`
`WallpaperVideoActivity`，说明它自己的美化就是走**壁纸**这条路。

**这意味着两条备选路线：**

- **路线 W（壁纸）**：做一个独立的动态壁纸 App，替换当前壁纸 → Live2D 就出现在桌面背景层。
  优点是层级天然正确（永远在桌面最底层、图标下方）；缺点是需要用户在系统设置里主动切换壁纸。
- **路线 O（悬浮窗）**：叠加一个透明悬浮窗 → Live2D 浮在桌面之上。
  优点是不用动壁纸、可以穿透触摸、随时可关闭；缺点是层级在图标之上（可以调）。

> **推荐组合**：先做路线 O 快速见效；如果壁纸感更好再补路线 W。
> 两者共用同一套 WebView + Live2D 渲染代码，只是宿主容器不同。

---

## 四、推荐方案：叠加式 Live2D 悬浮窗

### 4.1 整体架构

```
┌──────────────────────────────────────────────┐
│  系统桌面 = 氢桌面 (com.mcar.auto)             │
│    · 背景层 / 卡片 / 图标                      │
│    · 方控、导航投屏、OBD、TPMS（全部保留）       │
└──────────────────────────────────────────────┘
        ▲ 之上叠加
┌──────────────────────────────────────────────┐
│  Live2D 悬浮窗 App（你自己写的，独立进程）      │
│    WindowManager.addView(                    │
│      TYPE_APPLICATION_OVERLAY,               │
│      PixelFormat.TRANSLUCENT,                │
│      FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE │
│    )                                         │
│      └─ Live2DFloatView (FrameLayout)        │
│           └─ WebView (背景透明)               │
│                └─ live2d_decor.html           │
│                     └─ PixiJS + Live2D       │
└──────────────────────────────────────────────┘
```

### 4.2 车机特有的注意事项

车机与手机差异很大，这几条必须处理：

| 项 | 说明 |
|---|---|
| **悬浮窗权限** | `SYSTEM_ALERT_WINDOW` 必须引导用户手动开。车机 ROM 常把它藏在「应用权限 → 高级」里，要做跳转兜底 |
| **后台保活** | 车机熄火重启频繁，必须 `RECEIVE_BOOT_COMPLETED` 自启 + 前台服务兜底，否则重启后 Live2D 就没了 |
| **屏幕常亮/低功耗** | 车机长时间亮屏，建议加「夜间压暗」（MikuCarLauncher 的 `dim` 参数就是干这个）+ 跟随系统深色模式 |
| **触摸穿透** | 车机主要靠方控和触摸屏操作。**必须加 `FLAG_NOT_TOUCHABLE`**，否则悬浮窗会吃掉整个屏幕的触摸，氢桌面直接废掉 |
| **分辨率差异** | 车机横屏常见 1280×720 / 1920×720 / 2560×720，MikuCarLauncher 的 `dw/dh` 设计基准机制要照抄（我实测它默认 `dw=2560&dh=720`） |
| **性能** | 车机 SoC 普遍弱（如 8 核 A53 + Mali-G52）。**质量倍率默认给 1.0，帧率给 30**，别一上来就 60 |
| **横竖屏** | 车机固定横屏。`screenOrientation="landscape"`，且不要响应旋转 |

### 4.3 权限清单

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
```

### 4.4 核心技术要点（全部从 MikuCarLauncher 实测还原）

**URL 协议**（照抄它的 `buildViewerUrl`）：

```
file:///android_asset/live2d/live2d_decor.html
  ?model=<模型路径>&scale=<缩放>
  &dw=2560&dh=720
  &clip=1&clipBottom=<设计稿Y坐标>
  &quality=1.0&fps=30&night=0&dim=35&reload=<计数>
```

**双向通信桥**：

```kotlin
// JS → Java：注册 window.MikuLive2DAndroid
webView.addJavascriptInterface(object {
    @JavascriptInterface fun heartbeat(type: String) { /* 刷新 lastHeartbeat */ }
    @JavascriptInterface fun reportError(msg: String) { /* 触发重建 */ }
}, "MikuLive2DAndroid")

// Java → JS
webView.evaluateJavascript("window.__mikuLive2DUpdate($x,$y,$scale)", null)
webView.evaluateJavascript("window.__mikuLive2DSetClipBottom($v)", null)
```

**三个保命细节（务必照抄）**：

1. **心跳看护**：JS 每 2s 从 **Pixi 渲染 ticker 内部**上报心跳；Android 侧超过 6s 没收到就重建 WebView。
   重建顺序必须是：`loadUrl("about:blank")` → `destroy()` → new WebView。
   直接 destroy 老 WebView 会偶发崩溃。
   > 原版注释明确指出：普通 `setInterval` 在部分 ROM 上即使画面已白屏仍会继续触发，**必须从渲染 ticker 内部上报才可靠**。

2. **WebGL 上下文丢失**：JS 侧监听 `webglcontextlost` + ticker 内轮询 `gl.isContextLost()`，
   任一命中就上报 Android 触发重建。车机 GPU 驱动普遍不稳，这条尤其重要。

3. **首帧前先加载动作**：`Live2DModel.from(url, { autoUpdate: false })` →
   等第一个随机 motion 加载完 → 再 `app.stage.addChild(model)` + `autoUpdate = true`。
   否则模型会先以原始 T-pose 闪一下（原版注释：「the conspicuous spread-arms pose」）。

**透明与穿透（HTML 侧）**：

```css
html, body, #stage, canvas {
  background: transparent;
  pointer-events: none;   /* 触摸穿透到下层氢桌面 */
  -webkit-user-select: none;
}
```

**双层裁切**（让模型「站」在卡片后面而不是飘在图标上）：

```javascript
// 第一层：CSS 裁切 WebView 内的 stage（比 Pixi mask 更稳）
stage.style.height = clipBottom + 'px';
stage.style.overflow = 'hidden';
// 第二层：Pixi mask 只作用于模型本身
live2DMask = new PIXI.Graphics();
model.mask = live2DMask;
live2DMask.drawRect(0, 0, window.innerWidth, clipBottom);
```

原版注释解释了为什么要两层：「避免 WebView / Canvas 硬件加速时 Pixi mask 偶发不生效」。

### 4.5 运行库与模型

**四个 JS 库**（全部离线内置到 `assets/live2d/runtime/`）：

| 文件 | 版本 | 作用 |
|---|---|---|
| `pixi.min.js` | 6.5.10 | WebGL 渲染器 |
| `pixi-live2d-display.min.js` | 0.4.0 | Live2D 与 Pixi 的桥 |
| `live2dcubismcore.min.js` | 官方 | Cubism 4 运行时 |
| `live2d.min.js` | — | Cubism 2 兼容层 |

加载顺序：`android_asset` → `/sdcard/<你的App>/live2d/runtime/` → CDN 兜底。

**模型导入**：用 SAF（`ACTION_OPEN_DOCUMENT_TREE`）让用户选模型文件夹，递归拷贝到
`/sdcard/<你的App>/live2d/models/`，然后找 `.model3.json`（Cubism 4）或 `model.json`（Cubism 2）。

**兜底动作**（模型没有自带动作时用，MikuCarLauncher 是运行时动态合成的）：

```
motions_default/miku_default_idle.motion3.json
motions_default/miku_default_blink.motion3.json
motions_default/miku_default_smile.motion3.json
motions_default/miku_default_nod.motion3.json
expressions_default/miku_default_smile.exp3.json
expressions_default/miku_default_wink.exp3.json
expressions_default/miku_default_surprise.exp3.json
```

用到的参数（照抄即可）：
`ParamAngleX/Y/Z`、`ParamBodyAngleX`、`ParamBreath`、`ParamEyeLOpen/ROpen`、
`ParamMouthForm`、`ParamMouthOpenY`、`ParamEyeSmile`、`ParamHairFront/Back`、`ParamBustX/Y`。
每个参数都要同时试 Cubism 4 命名和 Cubism 2 命名（如 `ParamEyeLOpen` / `PARAM_EYE_L_OPEN`）。

### 4.6 配置项设计（参考还原）

```
live2d_enabled            总开关
live2d_model_path         模型路径
live2d_model_label        显示名
live2d_scale              缩放
live2d_center_x / _y      中心点（设计稿坐标）
live2d_clip_bottom_design_y  底部裁切线
live2d_render_quality     质量倍率 0.5~2.0（车机建议 1.0）
live2d_target_fps         帧率 15~60（车机建议 30）
live2d_night_dim_alpha    夜间压暗 0~85
live2d_visible_page_*     显示在哪些桌面页
```

---

## 五、实施路线

### 阶段 1：跑通最小闭环（优先）

1. 新建 Android 工程（Kotlin，minSdk 21，`landscape`）
2. 建 `Live2DFloatView`：`FrameLayout` + 透明 `WebView`
3. 复制 `live2d_decor.html` + 4 个 runtime JS 到 `assets/live2d/`
4. 放一个测试模型到 `/sdcard/`，硬编码路径先跑通
5. `WindowManager.addView`，参数：
   ```kotlin
   TYPE_APPLICATION_OVERLAY
   FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_NO_LIMITS
   PixelFormat.TRANSLUCENT
   gravity = Gravity.BOTTOM or Gravity.END
   ```
6. 验证：模型显示、动画播放、**触摸能穿透到氢桌面**

### 阶段 2：稳定性

- 加心跳看护（6s 超时重建）
- 加 WebGL 上下文丢失重建
- 加开机自启（`BOOT_COMPLETED` + 前台服务）
- 车机实测：熄火重启后 Live2D 是否自动恢复

### 阶段 3：可调节

- 做设置页：模型导入（SAF）、位置调节（拖动）、缩放、质量、帧率、夜间压暗
- 位置/参数存 `SharedPreferences`，重启保留

### 阶段 4（可选）：备选壁纸方案

如果发现悬浮窗层级不满意（比如想让模型在图标下方），再补一个 `WallpaperService` 版本，
复用阶段 1 的 WebView 容器代码，只是宿主从 `WindowManager` 换成 `WallpaperService.Engine#getSurfaceHolder`。

> 注意：`WallpaperService` 的 Surface 是 **`SurfaceView` 而非 `ViewGroup`**，
> 不能直接 addView 一个 WebView 进去。两条路：
> (a) 用 `SurfaceHolder` + 自己把 WebView 绘制到 Canvas（复杂）；
> (b) 退一步用 `TextureView` 承载 WebView（API 24+ 可用，但壁纸服务里支持有限）。
> **因此悬浮窗方案在实现成本上明显更优**，壁纸方案建议作为后期优化项。

---

## 六、不要做的事

| 想法 | 为什么不要做 |
|---|---|
| 反编译氢桌面改布局 | 代码加密在 `libstub.so`，改不了 |
| 重打包氢桌面加 Live2D | 920 个 CRC 异常 + 加固校验，装不上或启动崩；且需要用户重装，车机功能可能失效 |
| 用 AppWidget 实现 Live2D | AppWidget 只能放 RemoteViews，**没有 WebView / 不能自绘 / 不能跑渲染循环**，天花板是「定时换张图」 |
| 覆盖氢桌面的 `LiveWallpaperService` | 那是它的组件，需要同签名才能覆盖；且会破坏它自己的壁纸功能 |

---

## 七、一句话总结

> 氢桌面核心代码已加固加密（`classes.dex` 仅 3960 字节，真实代码在 `libstub.so` 里），
> **改不动也不必改**。正确路线是写一个**独立的透明悬浮窗 App**，
> 用 `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCHABLE` 叠加在氢桌面之上，
> 内部用 **WebView + PixiJS + pixi-live2d-display** 渲染 Live2D。
> 渲染代码可直接复用 MikuCarLauncher 的 `live2d_decor.html`（含全部容错逻辑）。
