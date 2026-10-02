# MikuCarLauncher Live2D 桌面显示 — 逆向分析报告

分析对象：`MikuCarLauncher车机桌面本体.apk`  
包名 `com.jlxc.mikucarlauncher`，versionName `0.13.38` (versionCode 182)  
compileSdk 34 / minSdk 21 / targetSdk 28，`debuggable=true`，无 native `.so`

---

## 一、核心结论（先看这段）

**它没有用安卓 AppWidget<u>（小部件）来实现 Live2D，也没有用悬浮窗。</u>**

<u>它用的是：</u>**<u>把整个 App 做成桌面（Launcher），然后在这个桌面的 View 树里，  
直接塞一个透明背景的 WebView，WebView 内部用 Pi</u>xiJS + pixi-live2d-display 渲染 Live2D。**

这点非常关键，因为它直接决定了「能不能用桌面小部件实现 Live2D」这个问题的答案 —— **基本上不能**。

### 证据

| 检查项                                                      | 结果                                                                                                             |
| -------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `AndroidManifest` 里有没有 `<receiver>` + `APPWIDGET_UPDATE` | **没有**。整个 App 没有注册任何 AppWidgetProvider                                                                         |
| 有没有 `res/xml/*_appwidget_info.xml`                       | **没有**（`res/` 下只有 drawable、mipmap、一个 accessibility service 配置）                                                 |
| 有没有 `lib/*.so`（原生 Live2D SDK）                            | **没有**。`lib/` 目录整个不存在                                                                                          |
| 有没有 `WindowManager.addView` 悬浮窗                          | **没有**。全 dex 搜 `TYPE_APPLICATION_OVERLAY` / `addView` 均无命中                                                     |
| 入口 Activity 的 intent-filter                              | `android.intent.category.HOME` + `LAUNCHER` → **它是一个 Launcher**                                                |
| `assets/live2d/` 内容                                      | `live2d_decor.html` + `runtime/`（pixi.min.js、pixi-live2d-display.min.js、live2dcubismcore.min.js、live2d.min.js） |

> 注意：它确实申请了 `BIND_APPWIDGET` 权限，也有 `RoundedAppWidgetHost` / `RoundedAppWidgetHostView` 类。  
> 但那是**反过来的用途** —— 它作为 Launcher 去**承载别人的小部件**（`AppWidgetHost.allocateAppWidgetId` /  
> `bindAppWidgetIdIfAllowed` / `setAppWidget`），不是把自己的 Live2D 做成小部件。

---

## 二、它到底怎么实现的

### 2.1 层级结构

```
MainActivity (intent-filter: HOME)        ← 它是桌面本体，常驻
 └─ LauncherCanvasView                     ← 桌面根容器（背景图/卡片/图标都画在这）
     ├─ 背景图 (bg_a4l.png / 夜间版)
     ├─ 桌面卡片 (DesktopCardLayout / Page / CardItem)
     ├─ 图标网格 (AppDrawerCacheManager)
     └─ Live2DDecorView                    ← 关键：Live2D 的宿主 View
         └─ WebView (背景透明)
             └─ canvas (PixiJS WebGL)
                 └─ Live2DModel (Cubism 2 / Cubism 4 模型)
```

`Live2DDecorView` 继承自普通 `ViewGroup`（从方法名 `onAttachedToWindow` / `onDetachedFromWindow` /  
`onTouchEvent` 可确认），在它的构造函数里创建 WebView 并 addView 进去。  
方法名 `recreateLive2DDecorView` 出现在 `MainActivity` 里，说明是 MainActivity 创建并持有它。

### 2.2 Live2DDecorView 的完整职责（从方法名还原）

```
创建/重建
  <init>                      创建 WebView，配置透明 + 硬件加速
  recreateWebViewInternal     WebView 崩溃后内部重建（先 loadUrl("about:blank") 再重建）
  requestHostHardRecreate     请求 MainActivity 整体重建（发广播 LIVE2D_HARD_RECREATE）
  hardReloadLive2D            强制重载
  terminateRendererForHardRecovery  杀掉 WebView 渲染进程

URL 与参数
  buildViewerUrl              拼装 file:///android_asset/live2d/live2d_decor.html?model=...
  normalizeModelPath          路径归一化（http:// / content:// / file:// 三种）

JS ↔ Java 桥
  Live2DJsBridge              @JavascriptInterface 对象，暴露给页面的 window.MikuLive2DAndroid
    .heartbeat(type)          JS 侧每 2s 上报渲染心跳
    .reportError(msg)         JS 侧报错回调
  sendTransformToJs           Java → JS:
                              window.__mikuLive2DUpdate(x, y, scale)
  sendClipBottomToJs          Java → JS:
                              window.__mikuLive2DSetClipBottom(value)
  playNextMotion              Java → JS:
                              window.__mikuLive2DNextMotion()

看护（watchdog）—— 这是它最花心思的地方
  startWatchdog / stopWatchdog
  hasFreshRenderHeartbeat     判断心跳是否新鲜
  isLive2DHealthy             综合判断健康状态
  scheduleSafeWebViewRecovery 计划一次安全恢复（避免频繁重建）
  startAutomaticModelRecovery 自动恢复（TAG: "Miku-Live2D-AutoRestore"）
  handleLive2DLoadError       处理加载失败

位置与裁切
  handleAdjustTouch           拖动调位置
  saveTransform               保存 x/y/scale 到 SharedPreferences
  migrateOldFramePrefsIfNeeded 老版本配置迁移
  setClipBottomDesignY        设置底部裁切线（让模型"站"在卡片后面）
```

### 2.3 URL 参数协议（`buildViewerUrl` 实参还原）

```javascript
file:///android_asset/live2d/live2d_decor.html
  ?model=<模型路径>
  &scale=<缩放>
  &dw=2560&dh=720          // 设计基准分辨率，所有坐标按这个比例换算
  &clip=1
  &clipBottom=<设计稿Y坐标>
  &quality=<0.5~2.0 渲染倍率>
  &night=<0 或 1>
  &dim=<夜间压暗百分比 0~85>
  &reload=<重载计数，用来打破 WebView 缓存>
```

`live2d_decor.html` 里还额外支持 `cx` / `cy`（中心点）、`fps`（15~60）。  
但 `buildViewerUrl` 里没传 `cx`/`cy`，而是**加载后用 `__mikuLive2DUpdate()` 推送位置** —— 这样拖动时不用重新加载页面。

### 2.4 SharedPreferences 键名清单（推断出完整设置项）

```
live2d_enabled                        总开关
live2d_model_path                     模型 .model3.json 路径
live2d_source_tree_uri                SAF 目录授权 URI（模型文件夹）
live2d_model_label                    显示名
live2d_motion_count / expression_count 统计
live2d_scale / live2d_center_x / live2d_center_y    位置参数
live2d_clip_bottom_design_y           底部裁切线
live2d_render_quality                 渲染倍率
live2d_target_fps                     帧率
live2d_night_dim_alpha                夜间压暗
live2d_visible_page_ids_v1            在哪些桌面页显示（分页控制）
live2d_visible_page_indices_v2
live2d_enable_default_fallback_motion_expression   无动作模型时用内置兜底动作
live2d_reload_generation_v1           重载代数（用于通知各组件刷新）
live2d_hard_recreate_generation_v1
Store 名：miku_car_launcher_settings
```

### 2.5 模型导入方式

`Live2DModelImporter` 用 **SAF（Storage Access Framework）** 导入：  
`ACTION_OPEN_DOCUMENT_TREE` 选一个目录 → `copyDocumentTree` 递归拷贝到  
`/sdcard/MikuCarLauncher/live2d/selected_model/` → 找 `.model3.json` 或 `model.json`。

它还会**注入内置兜底动作**（`installDefaultMotionsIfPossible` / `installDefaultExpressionsIfPossible`），  
生成的文件名：

```
motions_default/miku_default_idle.motion3.json
motions_default/miku_default_blink.motion3.json
motions_default/miku_default_smile.motion3.json
motions_default/miku_default_nod.motion3.json
expressions_default/miku_default_smile.exp3.json
expressions_default/miku_default_wink.exp3.json
expressions_default/miku_default_surprise.exp3.json
```

`motionJson()` / `expressionJson()` 里能看到它在**运行时动态合成** `.motion3.json` 和 `.exp3.json`：

```json
// motion3.json 模板
{"Version":3,"Meta":{"Duration":..,"Fps":30,"Loop":..,"FadeInTime":0.5,"FadeOutTime":0.5,
 "CurveCount":..,"TotalSegmentCount":..,"TotalPointCount":..,"Curves":[
   {"Target":"Parameter","Id":"ParamAngleX","FadeInTime":0.5,"FadeOutTime":0.5,"Segments":[..]}
 ]}}

// exp3.json 模板
{"Type":"Live2D Expression","FadeInTime":0.4,"FadeOutTime":0.6,
 "Parameters":[{"Id":"ParamEyeLOpen","Value":0.0,"Blend":"Multiply"}]}
```

用到的参数（与 HTML 里的待机逻辑完全一致）：  
`ParamAngleX/Y/Z`、`ParamBodyAngleX`、`ParamBreath`、`ParamEyeLOpen/ROpen`、  
`ParamMouthForm`、`ParamMouthOpenY`、`ParamEyeSmile`、`ParamHairFront/Back`、`ParamBustX/Y`。

### 2.6 运行库加载策略（三级兜底）

README 写得很明确，HTML 里的 `loadScriptAny()` 也照此实现：

```
1. file:///android_asset/live2d/runtime/     APK 内置（离线可用）
2. file:///sdcard/MikuCarLauncher/live2d/runtime/   用户手动放置
3. https://cdn.jsdelivr.net/... 或 unpkg.com   在线兜底
```

四个库的版本：

- `pixi.js@6.5.10`
- `pixi-live2d-display@0.4.0`
- `live2dcubismcore.min.js`（官方，Cubism 4）
- `live2d.min.js`（Cubism 2 兼容层）
- HTML 还额外兼容 `index.min.js`（`pixi-live2d-display` 的别名产物）

### 2.7 HTML 侧的关键工程细节（可直接复用的经验）

`live2d_decor.html` 里有几个非常值得抄的点：

1. **`pointer-events: none` + 透明背景**  
   `html/body/#stage/canvas` 全部 `background: transparent; pointer-events: none;`  
   → 触摸事件穿透到下层桌面，模型不会挡住图标点击。
2. **双层裁切防 Pixi mask 失效**  
   代码注释原话：「第一层：CSS 裁切 WebView 内的 stage。这个比 Pixi mask 更稳定，  
   可以避免 WebView / Canvas 硬件加速时 Pixi mask 偶发不生效。」  
   于是同时做 CSS `height` 裁切 + `PIXI.Graphics` mask，双保险。
3. **WebGL 上下文丢失检测**  
   `webglcontextlost` 事件 + ticker 里每 2s 轮询 `gl.isContextLost()`。  
   注释指出：普通 `setInterval` 在部分 ROM 上即使画面已白屏仍会继续触发，  
   **必须从 Pixi 渲染 ticker 内部上报心跳才可靠**。
4. **首帧前先异步加载动作**  
   注释：「Do not add the model to app.stage before this await completes.」  
   `autoUpdate: false` → 异步 `startInitialRandomMotionBeforeFirstFrame()` → 再  
   `app.stage.addChild(model)` + `autoUpdate = true`。  
   否则模型会先以 T-pose / 展开手臂的原始姿态闪一下。
5. **参数级待机动画（不用整体缩放/漂浮）**  
   用 `ParamAngleX = sin(t*0.65)*5.5 + sin(t*0.19)*2.0` 这类正弦叠加模拟  
   ViewerEX 的自然待机，而不是简单地把整个模型上下浮动。
6. **`addParamAny` vs `setParamAny`**  
   自动适配 `getParameterIndex` 存在与否的两种运行库 API，  
   且 `set` 用于角度、`add` 用于头发/身体等相对量。
7. **`ParamEyeLOpen` / `PARAM_EYE_L_OPEN` 双命名兼容**  
   同时兼容 Cubism 2 和 Cubism 4 的参数命名规范。

---

## 三、为什么「桌面小部件」做不了 Live2D

这是本次分析最实用的结论。安卓 AppWidget 有硬性限制：

| 限制                  | 说明                                                                                                                        |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| **只能放 RemoteViews** | 支持 View 白名单：`TextView` `ImageView` `FrameLayout` `LinearLayout` `Button` 等。**没有 WebView，没有 SurfaceView，没有 GLSurfaceView** |
| **不能自绘**            | 无法注册自定义 View / 无法拿到 Canvas 持续绘制                                                                                           |
| **不能跑动画循环**         | 更新靠 `AppWidgetManager.updateAppWidget()` 推送，最快也就几百毫秒级，且系统会限制频率                                                            |
| **不能嵌 WebView**     | 即使把 Live2D 导出成序列帧，也只能做成 `ImageView` 手翻书，帧率与清晰度都不可接受                                                                       |
| **无触摸事件细粒度**        | 只能给整个小部件设 `PendingIntent`，拿不到精确点击坐标做「点身体触发动效」                                                                             |

**结论：AppWidget 的天花板是「定时换一张图」。Live2D 需要 60fps 的 GL 渲染循环 + 逐帧参数插值，  
两者根本不兼容。**

MikuCarLauncher 之所以看起来「像小部件」，是因为它**整个就是桌面**，所以可以在自己的  
View 树里随意塞 WebView。

---

## 四、你可以怎么做（三条路线对比）

### 路线 A：做一个完整桌面（Launcher）— 与 MikuCarLauncher 同构

| 项  | 内容                                                                           |
| -- | ---------------------------------------------------------------------------- |
| 难度 | ★★★★☆                                                                        |
| 效果 | ★★★★★ 完全自由，可全屏、可穿透、可分页                                                       |
| 做法 | `MainActivity` 加 `category.HOME`；根布局里放背景层 + 卡片层 + `Live2DDecorView`(WebView) |
| 代价 | 用户要**替换系统桌面**；要处理图标/抽屉/壁纸/最近任务等一大堆 Launcher 逻辑                               |
| 适用 | 车机、平板、备用机                                                                    |

### 路线 B：做一个透明悬浮窗 App（推荐，成本最低）

| 项  | 内容                                                                                                                                                                                                                                                            |
| -- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 难度 | ★★☆☆☆                                                                                                                                                                                                                                                         |
| 效果 | ★★★★☆ 不换桌面，叠加在任何界面之上                                                                                                                                                                                                                                          |
| 做法 | `SYSTEM_ALERT_WINDOW` 权限 → `WindowManager.addView()` 加一个 `TYPE_APPLICATION_OVERLAY` 窗口，属性：`FLAG_NOT_FOCUSABLE \| FLAG_NOT_TOUCHABLE`（做穿透）或 `FLAG_NOT_FOCUSABLE`（可拖动）、`PixelFormat.TRANSLUCENT`、`gravity=BOTTOM\|END`。窗口里放 WebView，其余照抄 MikuCarLauncher 的 HTML |
| 代价 | 需要悬浮窗权限（用户手动授权一次）；`FLAG_NOT_TOUCHABLE` 会整个窗口不吃触摸，要做「点击模型反应」就得自己算坐标或改用 `FLAG_NOT_FOCUSABLE` + 自定义命中区域                                                                                                                                                          |
| 适用 | **手机为主，想立刻看到效果**                                                                                                                                                                                                                                              |

### 路线 C：真做一个 AppWidget — 只能做「伪 Live2D」

| 项  | 内容                                                                                                          |
| -- | ----------------------------------------------------------------------------------------------------------- |
| 难度 | ★★☆☆☆                                                                                                       |
| 效果 | ★★☆☆☆ 能加进桌面，但不是真 Live2D                                                                                     |
| 做法 | AppWidgetProvider + RemoteViews(`ImageView`) → 后台 Service 用 Live2D SDK 渲染到 Bitmap → 定时 `updateAppWidget` 推帧 |
| 代价 | 帧率 <5fps 才不烧电、清晰度受限、无法交互。**不推荐**                                                                            |
| 适用 | 只想要「桌面上有个人在那儿轻轻呼吸」的极简需求                                                                                     |

---

## 五、路线 B 的最小可行实现（可直接抄的骨架）

### 5.1 依赖

```
assets/live2d/live2d_decor.html          ← 直接复用 MikuCarLauncher 这份（已含全部兜底逻辑）
assets/live2d/runtime/pixi.min.js
assets/live2d/runtime/pixi-live2d-display.min.js
assets/live2d/runtime/live2dcubismcore.min.js
assets/live2d/runtime/live2d.min.js
```

模型放 `/sdcard/<YourApp>/live2d/models/<name>/<name>.model3.json`。

### 5.2 悬浮窗 View（Kotlin）

```kotlin
class Live2DFloatingView(context: Context) : FrameLayout(context) {
    private val webView: WebView = WebView(context)

    init {
        setBackgroundColor(Color.TRANSPARENT)
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.addJavascriptInterface(JsBridge(), "MikuLive2DAndroid")
        addView(webView, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun load(modelPath: String, w: Int, h: Int, bottomClipPx: Int) {
        val url = "file:///android_asset/live2d/live2d_decor.html" +
                "?model=${Uri.encode(modelPath)}" +
                "&dw=2560&dh=720&clip=1" +
                "&clipBottom=$bottomClipPx" +
                "&quality=1.0&fps=60&night=0&reload=0"
        webView.loadUrl(url)
    }

    fun move(x: Float, y: Float, scale: Float) {
        webView.evaluateJavascript(
            "window.__mikuLive2DUpdate && window.__mikuLive2DUpdate($x,$y,$scale);", null)
    }

    inner class JsBridge {
        @JavascriptInterface fun heartbeat(type: String?) { lastHeartbeat = SystemClock.elapsedRealtime() }
        @JavascriptInterface fun reportError(msg: String?) { /* 触发重建 */ }
    }
    private var lastHeartbeat = 0L
}
```


```

### 5.3 挂到 WindowManager

```kotlin
val wm = getSystemService(WindowManager::class.java)
val view = Live2DFloatingView(this).apply { load(modelPath, 2560, 720, 546) }

val lp = WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
    PixelFormat.TRANSLUCENT
).apply {
    gravity = Gravity.BOTTOM or Gravity.END
    // 只想美化、不想挡操作 → 加 FLAG_NOT_TOUCHABLE
    // 想让模型可点 → 不加 FLAG_NOT_TOUCHABLE，改在 onTouchEvent 里做坐标命中
}
wm.addView(view, lp)
```

权限：

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
```

`SYSTEM_ALERT_WINDOW` 需要 `Settings.canDrawOverlays()` 检查 + `ACTION_MANAGE_OVERLAY_PERMISSION` 引导。
**Android 14+ 建议起一个前台服务持有这个 View，否则后台可能被回收。**

### 5.4 必须抄的三个「保命」细节

1. **心跳看护**：JS 每 2s 从 Pixi ticker 内上报；Android 侧超时（如 6s）就重建 WebView。
   重建时先 `loadUrl("about:blank")` 再 `destroy()` 再 new 一个新的 —— 直接 destroy 老 WebView 会偶发崩。
2. **WebGL 上下文丢失**：JS 侧监听 `webglcontextlost` 并上报，Android 收到就重建。
3. **首帧前先加载动作**：`autoUpdate:false` → 等第一个 motion 加载完 → 再 `addChild` + `autoUpdate:true`。
   不做这步会看到模型闪一下原始 T-pose。

---

## 六、附：氢桌面 1.3.0 对比（第一份 APK）

`com.mcar.auto` v1.3.0，车机 Launcher，**也没有任何 AppWidget 注册**（`BIND_APPWIDGET` 同样是用于承载别人的小部件）。
它的「桌面美化」走的是另一条路：

- **原生 View 自绘**：`liblight.so`（380KB）+ `libstub.so`（2.3MB）→ 它是 **Unity/自绘引擎** 路线
- **有 Live Wallpaper Service**：`com.mcar.auto.service.LiveWallpaperService`（`BIND_WALLPAPER` 权限）
  → 动效是通过**动态壁纸**实现的，不是小部件
- 有大量车机专用权限（TPMS、方控、OBD、导航投屏）

**也就是说：两份 APK 都没有用小部件做美化。** 一份用「Launcher + WebView」，一份用「Launcher + 动态壁纸」。

---

## 七、一句话总结

> 想在安卓桌面上显示 Live2D，**不要走 AppWidget**。
> 要么做 Launcher（View 树里塞透明 WebView），
> 要么做悬浮窗（WindowManager + TYPE_APPLICATION_OVERLAY + 透明 WebView），
> 要么做动态壁纸（WallpaperService）。
> 渲染一律用 **WebView + PixiJS + pixi-live2d-display**，这是当前成本最低、兼容性最好的方案。
