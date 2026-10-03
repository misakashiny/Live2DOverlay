# Soullink Emotion 引擎接入 · 决策与实施（v1.7.3）

> **状态**：已实施并**真机验证通过**，但为 **opt-in（默认关闭）**。
> **上游仓库**：[nanlingyin/soullink-emotion-sdk](https://github.com/nanlingyin/soullink-emotion-sdk)（Apache-2.0，`0.2.0-beta.1`）
> **关联文档**：[`31_AI驱动情绪系统_框架推演.md`](31_AI驱动情绪系统_框架推演.md)（自建设计稿）、
> [`32_角色动画与AI骨架_实施现状.md`](32_角色动画与AI骨架_实施现状.md)（自建骨架现状）

---

## 一、结论摘要

| 问题 | 结论 |
|---|---|
| 值不值得接？ | **值得**。它用零依赖、几秒、不要 API Key 的离线工具，自动产出了本项目**手写逆向整个 v1.2.0** 才得到的参数映射，且多给了 22 个 FACS 语义通道 |
| 能直接用它全部吗？ | **不能**。它的渲染器 `live2d-pixi` 要求 PIXI 7 + `pixi-live2d-display@0.5.0-beta`，本项目是 PIXI 6.5.10 + 0.4.0。**只取 `engine` 一个包**（零运行时依赖） |
| 怎么接？ | engine 打成 **IIFE** 放 assets，`beforeModelUpdate` 里 `runtime.update()` → `snapshot.live2dParams` → `setParam`。**渲染链路一行不改** |
| 能两套并存吗？ | **不能**。参数审计证明两套会写**同一批 17 个参数**。启用 Soullink 时内置实现必须**主动让位** |
| 现在是什么状态？ | 已实施为**可回退开关**，默认关；开启后真机跑通，60fps，无崩溃 |

---

## 二、为什么值得接：生成器实测结果

用 `@soullink-emotion/profile-generator` 对 miku 模型跑**纯启发式**（`useConfiguredOpenAI: false`，**不需要任何 API Key**）：

```
provider        : heuristic
schemaVersion   : 2
modelId         : miku_58dd29f8        ← 内容哈希，换模型自动变
mappedFACS      : 22                   ← 22 个 FACS 语义通道
idleConfig      : 11 项
neutralParams   : 23 个参数
parameterSmoothing: 23 个参数
capabilities    : 10/12 项 true
耗时            : 几秒
```

### 22 个 FACS 通道全部映射正确，其中 3 处**只能靠语义理解**

| FACS 通道 | 映射到 | 备注 |
|---|---|---|
| `headX/Y/Z` | `ParamAngleX/Y/Z` scale=30 | Cubism 标准角度范围 |
| `bodyX/Y/Z` | `ParamBodyAngleX/Y/Z` scale=12 | |
| `eyeOpen` | `ParamEyeROpen+ParamEyeLOpen` | 与 model3.json 的 `EyeBlink` group 吻合 |
| `eyeBlinkL/R` | 同上 mode=**subtract** | 含左右镜像 |
| `eyeSquint` | 同上 scale=**0.22** | 眯眼 = 小幅闭眼 |
| `gazeX/Y` | `ParamEyeBallX/Y` | |
| `mouthSmile`/`mouthFrown` | `ParamMouthForm` / 同参 **subtract** | 一参两用、方向相反 |
| **`mouthPucker`** | **`Paramguzui`** | ★ 非标准名，`guzui` = 鼓嘴拼音，从 cdi3 显示名推出 |
| **`browDown`** | `ParamBrowLForm+ParamBrowRForm` scale=**-0.85** | ★ 它知道 Form 是「皱眉程度」，下压要**负系数** |
| **`blush`** | **`Param130`** | ★ 非标准名，从 cdi3 显示名「脸红」推出 —— **正是本项目手写逆向的成果** |

> `Param130` 那条尤其说明问题：它**自动做到了本项目 v1.2.0 手工逆向的事**。

---

## 三、★ 参数所有权冲突（B3 审计结果）

**这是决定「必须 opt-in」的关键证据。**

| 集合 | 参数 |
|---|---|
| 本项目 `model-profile.json` 会写 | **20 个** |
| Soullink profile 会写 | **30 个** |
| **两边都写（冲突面）** | **17 个** |

### 冲突明细

**① 8 个离散动作参数 —— 语义重复，必须二选一**

| 参数 | 本项目的实现 | Soullink 的实现 |
|---|---|---|
| `Param130` 脸红 | `actions[]` set 1 / 2600ms | `privateEmotionMap.blush`（VAD shy 窗口） |
| `Param132` 前倾 | `actions[]` set 1 / 1800ms | `leanForward`（curious/affectionate） |
| `Param125` 圈圈 | `actions[]` set 1 / 2400ms | `spin`（excited） |
| `Param133` 大葱 | `actions[]` set 1 / 2600ms | `scallion`（排他组 `miku_prop`） |
| `Param134` 唱歌 | `actions[]` set 1 / 2600ms | `sing` |
| `Param135` 比心 | `actions[]` set 1 / 2600ms | `heart` |
| `Param131`+`136` QQ人 | `actions[]` set 两个参数 | `qqMode`（`targets` 数组） |

**② 9 个连续参数 —— 真冲突，逐帧互相覆盖**

`ParamAngleX/Y/Z`、`ParamBodyAngleX`、`ParamBreath`、`ParamEyeLOpen`、`ParamEyeROpen`、
`ParamMouthForm`、`ParamMouthOpenY`

> 本项目的 `animations[]` 关键帧写它们；Soullink 的 `parameterMap` + `neutralParams` 也写它们。
> **同时开必然打架** —— 就是 [`00_交接总纲.md`](00_交接总纲.md) §五「位置所有权必须唯一」那类坑。

### 结论

> **不能两套并存。** 启用 Soullink 时，本项目的内置 idle 与动作库必须**整体让位**。

---

## 四、★ Bug-019：参数存在性审计发现的静默失效

做冲突审计时顺手对 `cdi3.json` 的 **141 个参数**做了存在性核对，发现**本项目写了 2 个不存在的参数**：

| 参数 | 出现位置 | 后果 |
|---|---|---|
| `ParamEyeSmile` | `animations[].smile` + 页面 `triggerDefaultAction("smile")` | ❌ **模型没有这个参数**，正确名是 `ParamEyeLSmile` / `ParamEyeRSmile` |
| `ParamBustY` | `animations[].idle_breathe` | ❌ 模型**没有任何 Bust 参数** |

**为什么一直没被发现**：Live2D 的 `setParam` 对不存在的参数**不报错、只是什么都不做**。
所以「微笑」动作的**嘴部一直生效、眼部一直没生效** —— 半静默失效。

**已修**：
- 页面 `triggerDefaultAction("smile")` → 改用 `ParamEyeLSmile` + `ParamEyeRSmile`
- `model-profile.json` 的 `smile` 动画 → 拆成两条轨道
- `model-profile.json` 的 `idle_breathe` → 移除无效的 `ParamBustY` 轨道

> **教训**（已并入 `00_交接总纲` §五）：**新增参数前必须对 `cdi3.json` 做存在性核对**。
> 审计脚本：`reference/soullink/audit-params.mjs`

---

## 五、接入设计

### 5.1 为什么是 opt-in

见 §三：两套会抢同一批参数。所以设计成**可回退开关**，默认关。
关闭时行为与 v1.7.2 **完全一致**（真机已回归验证）。

### 5.2 数据流

```
assets/live2d/soullink.profile.json   ← 离线用 profile-generator 生成 + 人工补 privateEmotionMap
        │
        │  Kotlin 读文件（不用 fetch！见 5.3）
        ▼
OverlayService.pushSoullinkToPage()
        │  evaluateJavascript
        ▼
live2d_decor.html
   __mikuLive2DStartSoullink(profile)
        ├─ 按需 <script> 加载 runtime/soullink-engine.iife.js   ← 未启用时不加载
        ├─ new SoullinkRuntime({ profile, motionStyle: natural + seed })
        └─ ★ 让位：ANIMATIONS = [] / activeLoops 清空
        ▼
   beforeModelUpdate → tickSoullink()
        └─ runtime.update(nowSec, dt) → snapshot.live2dParams → setParam(k, v)
```

### 5.3 ★ 为什么 profile 由 Android 注入，而不是页面 fetch

engine 的 `loadModelProfile()` **内部用 `fetch()`** —— 在 `file://` 下报 `unknown scheme`
（真机已验证）。这**与本项目 v1.7.0 踩过的是同一个坑**。

→ 复用既有的「Kotlin 读文件 + `evaluateJavascript` 注入」方案。

### 5.4 让位点（三处，缺一不可）

| 位置 | 改动 | 原因 |
|---|---|---|
| `updateDefaultIdle()` | `if (soullinkReady) return;` | 停止写 9 个连续参数 |
| `reactToTap()` | `if (soullinkReady) return false;` | 停止播内置动作（8 个离散参数已由 `privateEmotionMap` 接管） |
| `__mikuLive2DStartSoullink()` | `ANIMATIONS = []` / `activeLoops` 清空 | 停掉关键帧动画，否则与引擎抢参数 |

> **注意**：只让位**参数**。模型位置（`model.x/y`）仍由本项目管 —— **引擎不碰位置**。

---

## 六、实施清单

### 新增

| 文件 | 说明 |
|---|---|
| `assets/live2d/runtime/soullink-engine.iife.js` | engine 打包产物，**133 KB / gzip 40 KB** |
| `assets/live2d/soullink.profile.json` | miku 的 Soullink 档案（9.5 KB，官方校验 `errors:[]`） |
| `reference/soullink/*.mjs` | 生成 / 补丁 / 对照 / 审计脚本（可复现） |

### 修改

| 文件 | 改动 |
|---|---|
| `live2d_decor.html` | 新增 Soullink 模块（加载器 / 启动 / 帧推进 / 让位 / 消息接口） |
| `OverlayConfig.kt` | 新增 `soullinkEnabled`（默认 **false**）+ URL 参数 `&soullink=` |
| `OverlayService.kt` | 新增 `pushSoullinkToPage()`（含 `parameterMap` 结构校验） |
| `MainActivity.kt` | 新增开关监听（改动触发页面重载） |
| `activity_main.xml` | 新增 `switch_soullink` + 说明文案 |
| `model-profile.json` | **Bug-019 修复**（`smile` / `idle_breathe`） |

### 页面暴露给 Android 的新接口

| 接口 | 用途 |
|---|---|
| `__mikuLive2DStartSoullink(profile)` | 注入档案并启动引擎 |
| `__mikuLive2DSoullinkMessage(text)` | 向引擎投递消息（后续情绪引擎用） |
| `__mikuLive2DSoullinkStatus()` | 返回 `{enabled, ready, frames}` |

---

## 七、真机验证证据（v1.7.3 / 小米平板 Android 13）

### 回归：`soullink=0`（默认）

```
url=...&soullink=0
[AI][I] 人设已推送 | id=miku name=初音未来 traits=4 bias=4
[AI][I] Soullink 未启用，跳过（内置 idle + 关键帧动画接管）
[JS:MOTION][I] 模型档案已应用 | id=miku/7项
[JS:MOTION][I] 动画帧写入器已安装 | hook=beforeModelUpdate 库大小=7
[JS:RENDER][I] 加载完成 | ...
```
→ **行为与 v1.7.2 完全一致**，2 个窗口正常 ✅

### 开启：`soullink=1`

```
url=...&soullink=1
[AI][I] Soullink 档案已注入 | bytes=9605 parameterMap=22
[JS:AI][I] Soullink 引擎已启动 | modelId=miku_58dd29f8 parameterMap=22
        privateEmotion=7 idleConfig=11 内置动画已清空=0
[JS:AI][I] Soullink 帧写入器已安装 | hook=beforeModelUpdate
[JS:AI][D] Soullink 运行中 | 帧=1    参数=23 情绪=neutral
[JS:AI][D] Soullink 运行中 | 帧=601  参数=23 情绪=soft-happy   V=0.02 A=0.02
[JS:AI][D] Soullink 运行中 | 帧=901  参数=23 情绪=soft-uneasy  V=-0.01 A=0.01
[JS:AI][D] Soullink 运行中 | 帧=1801 参数=23 情绪=soft-calm
[JS:AI][D] Soullink 运行中 | 帧=2401 参数=23 情绪=soft-low
[JS:AI][D] Soullink 运行中 | 帧=3001 参数=23 情绪=soft-happy
```

| 验收项 | 结果 |
|---|---|
| 引擎启动 | ✅ `parameterMap=22 privateEmotion=7 idleConfig=11` |
| **让位生效** | ✅ `内置动画已清空=0` |
| 每帧输出参数 | ✅ **23 个** |
| **情绪自主演化** | ✅ `neutral → soft-happy → soft-uneasy → soft-calm → soft-low → soft-happy` |
| 帧率 | ✅ 300 帧 / 5.2 秒 ≈ **60fps**（1801→3001 帧用 20 秒） |
| 渲染正常 | ✅ 命中区 `L=157 T=50 R=555 B=1060`，捕获层 `(340,100) 918x2297` 吻合 |
| JS 错误 | ✅ 无 |
| 崩溃 | ✅ `logcat -b crash` 空 + **dropbox 当天无新记录** |

> **「情绪自主演化」是本轮最有价值的观察**：无任何输入时，VAD 也在小幅漂移
> （IdleEngine + 自然衰减）—— 这正是「活着的角色」与「等指令的木偶」的区别。

截图：`evidence/v1.7.3_soullink运行.png`

---

## 八、已知限制与风险

| # | 项 | 说明 |
|---|---|---|
| 1 | **上游是 `0.2.0-beta.1`** | API 可能变。建议锁版本并 vendor 源码 |
| 2 | **无预构建产物** | 上游仓库**没有 `dist/`**，必须自己 `npm ci && npm run packages:build`，再用 esbuild 打 IIFE |
| 3 | **PIXI 7 门槛** | 想用它自带渲染器需升 PIXI 6→7，会牵动整个渲染层。**本项目不用它的渲染器** |
| 4 | **`nativeAnimations` 为 0** | miku 的 `model3.json` 没有 `Expressions`/`Motions` 段，8 个 exp3 是孤儿文件。**不是生成器的问题**；可通过补 model3.json 的 `Expressions` 段解决（会改模型文件） |
| 5 | **体积 +133 KB** | gzip 40 KB。只在启用时加载，关闭时零开销 |
| 6 | **模型/Cubism Core 不在 Apache-2.0 内** | 上游 README 明确说明 |
| 7 | **`privateEmotionMap` 是人工补的** | 7 条，来源是 8 个 exp3 的实际内容。启发式没从孤儿 exp3 里识别标签 |
| 8 | **无法热切换** | 引擎启用/停用需要重载页面（脚本加载 + 让位逻辑都在页面侧） |

---

## 九、后续路线

### 9.1 Soullink 取代了本项目的哪些自建层

| 本项目 | Soullink 对应 |
|---|---|
| [`31`](31_AI驱动情绪系统_框架推演.md) 的 6 维离散情绪 + `emotionPhysics` | **VAD 三轴连续情绪** + `EmotionStateController` |
| v1.7.2 关键帧 `animations[]` | `MotionMixer` + `IdleEngine` + `VADGestureController` |
| `actions[]`（Param13x 离散） | `privateEmotionMap` |
| persona `traits` | `motionStylePresets`（natural/lively/calm/shy）+ `personality` |
| 手写 `model-profile.json` | `profile-generator` 自动生成 |
| 「随机小动作」 | `IdleActionScheduler`（带防重复与方向避重） |

### 9.2 建议的推进顺序

| 阶段 | 内容 |
|---|---|
| **已完成** | profile 生成 + privateEmotionMap 补齐 + IIFE 接入 + 真机验证 |
| **下一步 1** | **长时间挂机观察**：让它跑几小时，看是否有内存增长 / 情绪漂移过头 / 渲染异常 |
| **下一步 2** | 接 `planner-openai` 或 `classifier-embedding`，用 `__mikuLive2DSoullinkMessage()` 投递真实消息，验证「消息 → 情绪 → 表情」闭环 |
| **下一步 3** | 若决定长期采用：把 persona 的 `emotionBias`/`emotionPhysics` 映射到 Soullink 的 `personality`/`motionStyle`，并考虑让 `model-profile.json` 的 `animations` 退役 |
| **可选** | 补 `miku.model3.json` 的 `Expressions` 段（引用 8 个 exp3），让 `nativeAnimations` 非 0 |

---

## 十、复现步骤

```bash
# 1) 克隆 + 安装 + 构建（Node ≥ 20.19 / ≥ 22.12）
git clone --depth 1 https://github.com/nanlingyin/soullink-emotion-sdk.git
cd soullink-emotion-sdk && npm ci && npm run packages:build

# 2) 生成 profile（改 gen-miku.mjs 里的 modelsRoot/modelDir）
node gen-miku.mjs          # → <modelsRoot>/<modelDir>/soullink.profile.json
node patch-miku.mjs        # 注入人工补的 privateEmotionMap
node compare.mjs           # 与 model-profile.json 对照
node conflict.mjs          # 参数所有权冲突检查
node audit-params.mjs      # 参数存在性审计（对 cdi2.json）

# 3) 打 IIFE
npx esbuild packages/engine/src/index.ts --bundle --format=iife \
  --global-name=SoullinkEmotion --target=es2020 --minify \
  --outfile=soullink-engine.iife.js
```

脚本已归档到 [`reference/soullink/`](../reference/soullink/)。
