# Soullink 对话编排 · 调研与 LLM 接入方案（v1.7.10 调研）

> **调研对象**：`D:\AI Dsh\_ref\soullink-emotion-sdk`（nanlingyin/soullink-emotion-sdk，Apache-2.0，全仓库 `0.2.0-beta.1`）
> **方法**：只读精读 `docs/jev-conversation.md` + `packages/{sdk,runtime-core,planner-openai,classifier-embedding,api-client}`
> **目的**：回答「如何把 LLM 对话接进来驱动角色表演」
>
> ⚠️ 本文所有结论都带**文件路径与行号**，便于复核。行号基于 `0.2.0-beta.1`，上游升级后需重新核对。

---

## 一、三个先说的结论（它们改变了方案判断）

### ① LLM **不产出** `SpeechPerformancePlan`

全仓库检索 `SpeechPerformancePlanner|startSpeechPerformance|planSpeechPerformance|sampleSpeechPerformance`，
命中**全部落在 `packages/engine` 内部**：

```
packages/engine/src/public.ts:16,22,23,24        ← 只有 engine 导出
packages/engine/src/runtime/SoullinkRuntime.ts:384,780
packages/engine/src/speech/SpeechPerformancePlanner.ts:724,871,911,958
```

`runtime-core`、`planner-openai`、`api-client`、demo（`src/*.js`、`scripts/*.mjs`）**一次都没用到**。

> **`SpeechPerformancePlan` 是 engine 的纯本地规则 planner（不联网、不调 LLM）。**
> **LLM 那条线产出的是 `SoullinkParameterBeat[]`（参数关键帧）。**

### ② `runtime-core` **完全没有** SpeechPerformance 接线

`createSoullinkSession` 只调 `runtime.startSpeechMotion(...)`（`createSoullinkSession.ts:714`），
**从不调用 `startSpeechPerformance`**。

> ★ **所以本项目「engine IIFE + `SpeechPerformancePlanner` + `startSpeechPerformance`」的做法，
> 是仓库里唯一存在的正确接法** —— 不是绕路，是正路。

### ③ 两条表演通道**互斥**

```
startSpeechMotion()      开头调 this.clearSpeechPerformance()   SoullinkRuntime.ts:365
startSpeechPerformance() 里面调 this.speechParameters.reset()    :415
```

**同一时刻只能选一条，不能叠加。** 若要「普通对话走 A、显式指令走 B」，路由要自己做。

---

## 二、三条「对话 → 表演」链路

### 链路 ① SDK 官方路线（LLM → 参数关键帧，**不含** SpeechPerformancePlan）

```
用户消息
  → session.sendMessage(message)                      createSoullinkSession.ts:168-223
      ├ classifier.classify(msg) → ClassifyResult{intent}          :190-192
      │   失败降级 → runtime.sendMessage(msg, now)（engine 本地规则）:198
      ├ textModel.planReaction(ReactionPlanInput) → SoullinkExternalPlan  :239-245
      ├ runtime.triggerPlan(plan, now)                              :250
      └ speak({text, emotion, vad, intent, parameterPlan, ...})      :257-265 / :630-741
          ├ tts.synthesize(text) → TtsResult{url?,bytes?,durationSec?}   :668
          └ motionPlanner.planSpeakingMotion(input) → SpeakingMotionResult :673-696
              → pendingSpeechMotion 择优（motion 优先，回退 request）    :697-702
              → runtime.startSpeechMotion(beats, start, durationSec)     :714
                → ParameterPlanSequencer → RuntimeSnapshot.live2dParams
```

**关键中间结构**

| 结构 | 位置 | 关键字段 |
|---|---|---|
| `EmotionIntent` | `engine/src/reaction/EmotionIntent.ts:3-12` | `emotion, variant?, naturalVAD?, intensity, contextTags[], sourceMessage?` |
| `SoullinkExternalPlan` | `engine/src/reaction/SoullinkPlan.ts:22-30` | `intent, replyDraft?, vadTarget?, vadDelta?, actionPlan?, parameterPlan?, provider?` |
| `SoullinkParameterBeat` | `SoullinkPlan.ts:15-20` | `time, duration, label?, parameters: Record<ParamId, number>`（**绝对目标值**） |
| `SpeakingMotionResult` | `runtime-core/src/types.ts:146-152` | `parameterPlan?, provider?, fallbackReason?` |

### 链路 ② SpeechPerformancePlan 路线（**本项目当前用法**，纯本地）

```
LLM / 规则分类器（任意来源）
  │ 只提供语义层：emotion, intensity, confidence, vad, durationMs,
  │               semanticCues[], deliveryHints[]
  ▼ SpeechPerformancePlanner.plan(input)              SpeechPerformancePlanner.ts:920-928
  │   → planSpeechPerformance(input)                          :724-851
  │     · 情绪白名单归一 · VAD 归一 · allowlist 过滤 cues/hints :729-730
  │     · createSpeechPerformanceSeed → seededRandom            :735-742
  │     · 手势模板打分排序 → 挑手势 + 曲线 + 通道幅度
  ▼ SpeechPerformancePlan {version,seed,emotion,vad,intensity,confidence,
  │                        semanticCues,deliveryHints,currentPosture,
  │                        durationMs,motionBudget,expression,gestures}  :412-430
  ▼ runtime.startSpeechPerformance(plan, now, mode)   SoullinkRuntime.ts:384-422
  │   mode: "replace" | "append" | "interrupt"
  │   返回 boolean；lifecycleToken 回退 → false 丢弃
  ▼ 每帧 getSpeechPerformanceSample → sampleSpeechPerformance(plan, elapsedMs)
      → {speechGesture, expressionAccent, activeBeatIds} 混入 MotionMixer
```

**LLM 只能注入 8 个字段**，具体「哪只手势、哪条曲线、多大通道幅度」全由 engine 本地决定。
**硬性白名单**（超出被静默丢弃，`SpeechPerformancePlanner.ts:479-480`）：

```ts
semanticCues  = ["agreement","reassurance","question","reflection","uncertainty",
                 "affection","emphasis","contrast","surprise","tension","sadness"]
deliveryHints = ["acknowledge","reassure","ask","reflect","hesitate",
                 "confide","emphasize","celebrate","warn"]
```

`SpeechPerformancePlanInput` 还有：`audioPeaks`（真实 RMS 包络对齐重音）、`currentPosture`（避免手势从极端位置起步）、
`history`、`turnOrdinal/segmentOrdinal/revision`（决定 seed）、`capabilities`。

### 链路 ③ `docs/jev-conversation.md` 描述的 JEV 实验（**demo，不走 engine**）

这是**仓库自带 demo 应用**，不是 SDK 功能：

```
用户消息 → POST /api/conversation/reply          scripts/ai-vite-plugin.mjs:87-102
Rivo gemini-3-flash-preview 生成回复（近 20 条历史） scripts/conversation-service.mjs:316-339
  → Fish TTS 与 JEV 并行                              :341-358 / :360+
      JEV 排除 physics/受保护外观/手部开关/ParamMouthOpenY
  → 服务端 retimeReplay 重排关键帧
  → 浏览器 src/conversation-controller.js:148-219
      → src/parameter-replay.js sampleReplay / smoothParameterPose
      → 直接写参数（完全不经过 SoullinkRuntime，也不用 SpeechPerformancePlan）
```

env 前缀：`RIVO_* / FISH_* / JEV_*`（`scripts/ai-provider-config.mjs:30-47`）。

**三条链路关系**：①③ 是「LLM 产出关键帧」，② 是「本地规则产出表演计划」。①② 属 SDK，③ 属 demo。
**本项目已在用 ② —— 依赖最少、最稳。**

---

## 三、各包能否进 WebView（逐个实测）

| 包 | Node-only API | 运行时 deps | ESM-only | **能否 esbuild IIFE 进 WebView** |
|---|---|---|---|---|
| `sdk` | 无 | 8 个兄弟包 | 是 | **能但无意义** —— 只导出 `soullinkEmotionSdkVersion` 字符串 |
| `runtime-core` | **无**（源码零命中） | **无**（peer engine） | 是 | **能**，但必须把 engine 一起 bundle（value 导入 `SoullinkRuntime`/`getVADPreset`） |
| `planner-openai` | **无** | **无**（peer engine） | 是 | **技术上能，安全上不该**（要 apiKey） |
| `classifier-embedding` | 主入口**无**；`./node` 有 | 无（peer engine） | 是 | **主入口能**，别引 `./node`；但有 key + 40KB 语料 + 首启嵌 1400 条的问题 |
| `api-client` | **无** | 无（对 engine/runtime-core 全是 `import type`） | 是 | ★ **能且最推荐** —— 官方「浏览器调可信后端」路径，不含密钥 |

**证据要点**

- `sdk/index.js:3` 全文唯一导出 `export const soullinkEmotionSdkVersion = "0.2.0-beta.1"`；
  README 自述「does not bundle the libraries together」（`sdk/README.md:10-12`）
- `runtime-core`：`tsup.config.ts:3-13` `platform:"neutral"`、`external:["@soullink-emotion/engine"]`；
  环境代码全是特性检测 —— `clocks.ts:19-38`（rAF 否则 setTimeout）、
  `browserAudioSink.ts:3-10`（`typeof Audio !== "undefined"` 否则 `play()` 直接 resolve 降级）
- `planner-openai`：只用 `globalThis.fetch`（`OpenAICompatibleClient.ts:59`）、`AbortController`、`setTimeout`、`console.warn`；
  但 `SpeakingMotionApiClient.ts:81-89` 的 `withoutServerCredentials()` 主动 `delete body.openAI/apiKey/openaiApiKey`
- `classifier-embedding`：Node API 只在 `node.ts:1-2` → `FileEmbeddingVectorCache.ts:1-3`
  （`node:crypto` / `node:fs/promises` / `node:path`）；`package.json:29-38` exports 分 `.` 与 `./node`
- `api-client`：`index.ts:28-29`、`runtimeAdapters.ts:1-17`、`types.ts:1-14` **全是 `import type`** → esbuild 全擦除，**可单独打包**
- 参考：`engine` 无 Node API（`platform:"neutral"`，本项目已成功打包为佐证）；
  `live2d-pixi` 需 DOM + **PIXI v7**；`profile-generator` 只能用 Node

**⚠️ 实操坑**：仓库根目录**没有 `node_modules`，也没有任何 `dist/`**（已核实）。
esbuild 解析 `@soullink-emotion/engine` 会失败 → 需 `npm install` 整个 workspace，
或用 esbuild alias / tsconfig `paths` 指向 `packages/engine/src/index.ts`。

---

## 四、LLM 接入方案（**本项目采用方案 A**）

### 关键约束：浏览器不得持密钥

官方**三处明确禁止**（这是硬约束，不是建议）：

| 警告 | 出处 |
|---|---|
| Browser clients should use `createSpeakingMotionApiClient` to call a trusted backend instead of holding an LLM key | `planner-openai/README.md:22-24` |
| 浏览器应用请使用 `createSpeakingMotionApiClient()` 调用可信后端，不要把 `openAI` 配置传到浏览器 | `docs/integration-tutorial.md:527` |
| 不要把 LLM、Embedding 或 TTS 的长期 API Key 放进浏览器 bundle | `packages/README.md:1059` |

### 方案 A（本项目采用）：LLM 只出语义，engine 本地编译

```
┌─ Kotlin 侧 ────────────────────────────────┐
│ 持有 API Key（prefs / 本地配置，勿入库）    │
│ HTTP → OpenAI-compatible /chat/completions  │
│ 拿回 {emotion,intensity,confidence,vad,     │
│       durationMs,semanticCues,deliveryHints}│
└────────────────┬───────────────────────────┘
                 │ 新 action（JSON）→ evaluateJavascript
                 ▼
┌─ WebView 页面 ─────────────────────────────┐
│ const planner = new SpeechPerformancePlanner({historyLimit:8})  ← 复用同一实例！│
│ const plan = planner.plan({ ...语义, capabilities, seed })      │
│ runtime.startSpeechPerformance(plan, now)                        │
└────────────────────────────────────────────┘
```

**要点**
- ★ **复用一个 `SpeechPerformancePlanner` 实例** —— 它内部维护 `history` 做 `cooldownGroup` 去重与方向交替
  （`recordPlan`, `SpeechPerformancePlanner.ts:930-946`），多轮对话才不重复同一动作
- `seed` 用 `createSpeechPerformanceSeed({turnOrdinal, segmentOrdinal, emotion, durationMs, revision})`
  —— 而非纯随机，同一轮可复现、不同轮有变化
- `capabilities` 用 `deriveSpeechPerformanceCapabilities(profile, overrides)`（`:536-551`）
  从 `ModelProfile.parameterMap` 推导可用通道，保证不发模型没有的通道
- `currentPosture` 填当前实际姿态（`Partial<Record<SpeechGestureChannel, number>>`）
- `durationMs` **由 Kotlin 按回复长度估算**，不要依赖上游那个 `max(0.8, min(30, 字数*0.16))`
  （`createSoullinkSession.ts:876-879`，且 `createTtsAdapter` 恰好不返回 `durationSec`，`runtimeAdapters.ts:127`）
- `startSpeechPerformance` 返回 `false` = `lifecycleToken` 回退被丢弃（`:397-403`）；
  用 `"append"` 模式可排队下一句

**不需要打包 `sdk`/`runtime-core`/`planner-openai`/`classifier-embedding`/`api-client` 中的任何一个。**

### 方案 B（备选）：LLM 直接出参数关键帧

```ts
const motionPlanner = new SoullinkSpeakingMotionPlanner(
  { apiKey, baseURL, model },                          // 只放你自己的服务端
  { mode: "fixed-parallel", fixedFrameCount: 4, frameIntervalSec: 0.9, twoStage: true });
const result = await motionPlanner.plan({ speechText, userMessage, durationSec, availableParameters, ... });
runtime.startSpeechMotion(result.parameterPlan, now, durationSec);   // 与方案 A 互斥
```

| | 方案 A | 方案 B |
|---|---|---|
| 依赖 | 只有 engine（已有） | planner-openai + key |
| 延迟 | 零（本地规则） | 1~2 次 LLM 往返（`twoStage:true` 打两次） |
| 可控性 | 模板库内，风格统一 | 可执行「挥挥手」等显式指令 |
| 跨模型安全 | `capabilities` 自动裁剪 | 需后端二次校验参数 ID/范围 |
| 能否进 WebView | ✅ | ❌（key 问题） |

### 不接 LLM 也能用（降级链完整）

| 能力 | 本地实现 | 位置 |
|---|---|---|
| 消息 → 情绪 | `MessageReactionClassifier` | `createSoullinkSession.ts:198` |
| 说话表演计划 | `SpeechPerformancePlanner` | `SpeechPerformancePlanner.ts:724,911` |
| 情绪 → VAD 预设 | `getVADPreset` | `createSoullinkSession.ts:578,809` |
| LLM planner 降级 | `SoullinkLLMPlanner.fallback()`：本地分类器 + 中文兜底回复 + 本地 actionPlan | `SoullinkLLMPlanner.ts:98-100,316-447` |
| 动作 planner 降级 | `{parameterPlan:[], provider:"vad-facs"}` → runtime 继续走 VAD/FACS | `SoullinkSpeakingMotionPlanner.ts:274-303` |

### `session` 是什么

`createSoullinkSession(options)`（`createSoullinkSession.ts:50`）是**无头编排器**，
内部 `new SoullinkRuntime(...)`（`:64-68`）。管理：`runtimeSnapshot / planning / apiError / lastReply /
voiceStatus / autoVoiceEnabled / proactiveDraft / conversation[] / speakingMotionParameters`（`:72-80`）
+ 4 组 requestId 抢占计数 + 反思空闲计时 + 播放 settle promise。

编排四条循环：**反应**（`sendMessage`）、**主动**（`queueProactiveDraft`）、
**反思**（空闲 5 秒后，`:37`）、**语音**（`speak` 状态机 `:630-741`）。

对外快照 `SessionSnapshot{runtime,planning,apiError,lastReply,voiceStatus,autoVoiceEnabled,proactiveDraft,conversation}`
（`types.ts:253-262`）。**会话历史只存内存，刷新即丢**（`reset()` `:770-784`）。

---

## 五、风险与坑

### 5.1 深导入

`@soullink-emotion/engine/internal` 由 `internal.ts:1-8` 与 `public.ts:8-9` 明确定义为
「**NOT part of the curated public contract, may change without a semver-major bump**」。

**好消息**：本项目在用的 API **全部在 curated public 面内** ——
核对 `public.ts:12-51` 的 values 列表：`SoullinkRuntime(:14)`、`SpeechPerformancePlanner(:16)`、
`planSpeechPerformance(:22)`、`sampleSpeechPerformance(:23)`、`deriveSpeechPerformanceCapabilities(:25)`、
`createSpeechPerformanceSeed(:26)`、`getVADPreset(:31)`。types 在 `:54-116`。
`setPrivateVADParameters` 是 `SoullinkRuntime` 的方法（`:265`），不是深导入。

> ⚠️ **未验证**：本项目 `soullink-engine.iife.js` 的实际构建入口。
> 若从 `src/internal.ts` 或具体文件打的，暴露面可能含不稳定符号。
> **应核对 esbuild entry 是否为 `packages/engine/src/index.ts`**（`index.ts:1` 只有 `export * from "./public";`）。

### 5.2 版本

全仓库 `0.2.0-beta.1`；根 `engines.node: ^20.19.0 || >=22.12.0`；peer `^0.2.0-beta.1`。
**beta 阶段无稳定 API 承诺。**

### 5.3 文档明确「不要这样做」

**安全类**：见 §四开头三处。

**参数语义类**

| 警告 | 出处 |
|---|---|
| JEV 不应覆盖嘴部开合（LipSync 独占） | `integration-tutorial.md:454`、`planner-openai/README.md:26-28`、`SpeechPerformancePlanner.ts:561` |
| ★ **某参数一旦出现在某帧，下一帧省略会被缓动到 `0`**；要保持在后续帧重复目标值 | `integration-tutorial.md:457,486` |
| 参数必须是绝对目标值，不是增量 | `integration-tutorial.md:452` |
| 不要传 `Date.now()` 毫秒值，也不要把毫秒当秒 | `packages/README.md:334` |
| 不要给普通交互传固定反应 seed | `packages/README.md:1013` |
| ★ 不要同时把 `parameterGain`、`bodyMotionGain`、`idleActionGain` 全拉到上限 | `packages/README.md:1031` |
| 不要用 `localStorage` 存大向量集合 | `classifier-embedding/README.md:130` |

> ★ 两条**直接影响本项目**：
> 1. **`idleActionGain`/`microMotionGain` 在 v1.7.9 被拉满**（1.9/1.8），与 `README.md:1031` 的警告相冲，建议回调
> 2. `mouth-open` 的 LipSync 独占规则 —— 见 §六

### 5.4 与本项目技术栈的冲突（**已正确规避**）

`live2d-pixi` 与所有官方示例要求 **PIXI v7 + pixi-live2d-display 0.5.0-beta**
（`packages/README.md:129,180`、`sdk/README.md:22`）。
本项目 **PIXI 6.5.10 + pixi-live2d-display 0.4.0** → **不能用 `live2d-pixi`，自己渲染是正确的**。
engine 不依赖 PIXI，只输出 `RuntimeSnapshot.live2dParams: Record<ParamId, number>`，直接 `setParameters` 即可。

### 5.5 其他运行时坑

| 坑 | 位置 | 影响 |
|---|---|---|
| `speak()` 里 `if (!tts \|\| !audio) return;` | `createSoullinkSession.ts:633` | 缺任一端口**静默不播** |
| `sendMessage` 默认不等回复 | `:218-220` | `awaitReply` 默认 `false` |
| `twoStage: true` 打两次 LLM | `SoullinkSpeakingMotionPlanner.ts:305,389-433` | 延迟与费用翻倍 |
| Embedding 首启嵌 1400 条语料 | `defaultCorpus.ts` 40KB | 首次启动慢 |
| 两条表演通道互斥 | `SoullinkRuntime.ts:365,415` | 不能叠加 |
| 降级路径多但静默 | `SoullinkLLMPlanner.ts:130` 只 `console.warn` | 需通过 `onSnapshot` 的 `apiError` 监控 |

---

## 六、`mouth-open` / LipSync 冲突核查（v1.7.10 实查）

**实测结果**

```
profile:  mouthOpen → ParamMouthOpenY   (mode=set, scale=1, min=0, max=1)
引擎:     LipSyncController.ts:57,106   也写 mouthOpen
设备:     miku.model3.json → Groups.LipSync.Ids = []      ← 空的
```

| 判断 | 结论 |
|---|---|
| **跨系统冲突** | ✅ **没有** —— `LipSync.Ids=[]` 意味着 `pixi-live2d-display` 不碰口型参数 |
| **引擎内部两个源** | ⚠️ 有（FACS 的 `mouthOpen` 通道 + `LipSyncController`），**但这是引擎自身设计，内部已处理优先级** |
| **将来接 TTS** | ★ 用 `runtime.setLipSyncEnabled(true)` + `setAudioLevelAnalyzer(analyzer)`，**不要用 `model.speak()`**（组为空时它不工作，且会变成「不工作」而非「冲突」） |

> 官方那句「JEV 不应覆盖 `mouth-open`（LipSync 独占）」在本项目**已被引擎内部满足**：
> 说话路径 `sanitizeParameterPlan` 主动排除 `mouth-open`（`SoullinkSpeakingMotionPlanner.ts:500-517`）。
> 本项目 profile 的 `mouthOpen` 是 **FACS 表情通道**，不是说话关键帧 —— 两者不在一层，不冲突。

**顺带查清**：模型有 141 个参数，口型相关为
`ParamMouthOpenY`（嘴 张开和闭合）/ `ParamMouthForm`（嘴 变形）/ `ParamMouthShrug` / `mouthRollLower2` / `mouthRollLower`。
`Groups.LipSync.Ids` 若要绑，应绑 `["ParamMouthOpenY"]`。

---

## 七、结论与建议优先级

### 一句话回答

> **本项目架构下最短路径**：Kotlin 侧调 LLM 拿回复 + `{emotion,intensity,confidence,vad,durationMs,semanticCues,deliveryHints}`
> → `evaluateJavascript` 喂给 WebView → WebView 侧 `SpeechPerformancePlanner.plan(...)`
> → `runtime.startSpeechPerformance(plan, now)`。
>
> **不需要打包 `sdk`/`runtime-core`/`planner-openai`/`classifier-embedding`/`api-client` 中的任何一个** ——
> 因为仓库里本来就没有把 LLM 接到 `SpeechPerformancePlan` 的代码，`runtime-core` 也从不调 `startSpeechPerformance`。
> **现有「只打 engine IIFE」的选择是正确且完整的。**

### 待办（按优先级）

| # | 事项 | 理由 | 状态 |
|---|---|---|---|
| 1 | 核查 `mouthOpen` 与 LipSync 抢 `ParamMouthOpenY` | 与眼部同类的「双源覆盖」风险 | ✅ **已完成**（见 §六，无冲突） |
| 2 | `idleActionGain`/`microMotionGain` 从拉满回调 | 官方警告 `README.md:1031` | ⬜ 待做 |
| 3 | Kotlin 侧加 `ACTION_SOULLINK_PERFORM`（带 JSON） | LLM 接入的正确落点，不违反密钥规则 | ⬜ 待做 |
| 4 | `durationMs` 由 Kotlin 按回复长度估算 | 不依赖上游 `字数*0.16` 的估算 | ⬜ 待做 |
| 5 | 核对 `soullink-engine.iife.js` 的 esbuild entry | 确认没引到 `internal.ts` 的不稳定面 | ⬜ 待做 |
| 6 | 若要「显式指令动作」→ 服务端 `SoullinkSpeakingMotionPlanner` + `startSpeechMotion` | 与方案 A 互斥，需自己做路由 | ⬜ 可选 |

---

## 附：本次调研标注为「未验证」的项

1. 本项目 `soullink-engine.iife.js` 的实际构建入口与暴露面（无构建脚本记录）
2. esbuild 在无 `node_modules` 情况下解析 `@soullink-emotion/engine` 的具体配置（未实跑）
3. `runtime-core` / `planner-openai` 打成 IIFE 的实际体积与是否零报错（静态分析可行，未实跑）
4. 把 `@soullink-emotion/engine` alias 成 `window.SoullinkEmotion` 的 shim 方案（思路可行，未验证；注意模块加载顺序）
5. `SpeakingMotionInput` 与 `SpeakingMotionPlanRequest` 的 TS 互赋是否需要断言
   （字段名一致，但 `mode` 联合类型不同：前者 `"duration"|"fixed-parallel"`，后者多一个 `"fixed"`）

---

## 八、实施结果（v1.7.12 / v1.7.13 —— 本方案已落地并真机验证）

> 本节是**事后补记**：上面 §一~§七 是调研与方案，本节记录**实际实现与验证结果**。

### 8.1 采用方案 A（LLM 只出语义，engine 本地编译）

```
用户消息
  → MainActivity.askLlmAndPerform()                     MainActivity.kt
      → LlmClient.converse(context, text, history)       LlmClient.kt（单线程 Executor）
          → POST {baseURL}/chat/completions              DeepSeek 实测 836ms
          → parse() → 情绪白名单 + cues/hints 白名单过滤   （第二次过滤）
          → Semantic{emotion,intensity,confidence,durationMs,cues,hints,reply}
      → JSONObject 拼语义层 → ACTION_SOULLINK_PERFORM     OverlayService.kt
          → JSONObject.quote() 转义 → evaluateJavascript
  → 页面 window.__mikuLive2DSoullinkPerform(json)         live2d_decor.html
      → 白名单第三次过滤
      → planner.plan({..., capabilities, seed})          复用同一 planner 实例
      → runtime.startSpeechPerformance(plan, now)
```

### 8.2 新增文件与接口

| 文件 / 接口 | 规模 | 职责 |
|---|---:|---|
| `SecureKeyStore.kt` | 137 行 | EncryptedSharedPreferences 封装（Android Keystore + AES-256-GCM） |
| `LlmClient.kt` | 277 行 | OpenAI-compatible 客户端 + 提示词 + 解析 + 白名单 |
| `MainActivity.askLlmAndPerform()` | — | LLM → 显示回复 → 投递表演 |
| `MainActivity.setupLlm()` / `refreshLlmView()` | — | 设置界面（Key / BaseURL / Model + 保存 / 测试 / 清除） |
| `OverlayService.ACTION_SOULLINK_PERFORM` | — | 语义层 JSON → 页面 |
| `window.__mikuLive2DSoullinkPerform(json)` | — | 页面侧入口 |

新增依赖：`androidx.security:security-crypto:1.1.0-alpha06`

### 8.3 真机验证（DeepSeek）

```
[AI] 调试触发一轮对话（DEBUG） | chars=16
[AI] LLM 请求开始 | model=deepseek-chat baseUrl=https://api.deepseek.com/v1 key=sk-***500f
[AI] LLM 返回 | emotion=excited intensity=0.85 cues=agreement/emphasis
              hints=celebrate/acknowledge durationMs=5400 耗时=836ms
              回复=哇——上线啦！恭喜恭喜！这下可以稍微松口气了吧？
[AI] 对话表演已投递 | bytes=193
[JS:AI] 已播放对话表演 | 第1轮 emotion=excited 时长=5400ms 手势=3 接受=true
```

| 验证点 | 结果 |
|---|---|
| LLM 往返 | ✅ 836ms（另一次自测 824ms） |
| 白名单过滤 | ✅ 三次过滤后 cues/hints 均在白名单内 |
| `durationMs` 估算 | ✅ 5400 = 600 + 24字×200（与 `LlmClient.estimateDurationMs` 一致） |
| 页面侧手势编排 | ✅ `手势=3` |
| `startSpeechPerformance` | ✅ `接受=true` |
| 轮次递增 | ✅ `第1轮`（seed 用 `turnOrdinal`） |
| 崩溃 | ✅ 0 |
| 凭据泄漏 | ✅ `git grep` 复核仓库内无 key 片段 |

### 8.4 实施中发现的**额外**问题与修正

| 问题 | 处理 |
|---|---|
| **`response_format` 不是所有服务商都支持** | 去掉该字段，只靠 `extractJson()` 兜底（智谱 / 中转站 / Ollama 可能 400） |
| **前置检查只 toast 不记日志** | 「对话没反应」查不到原因 → 改为记 `对话未发起：悬浮窗未运行 / Soullink 未开启` |
| **XML 里 `android:text` 不能含裸 `<`** | 写了 `http://<你的IP>` → `mergeDebugResources FAILED`，改为 `http://你的IP` |
| **本机 UI 自动化不可靠** | `uiautomator dump` 截断（12128 B 上限）；`ScrollView` **不响应合成 swipe**（连续 20 步下滚可见控件集合完全不变）→ 引入两个 debug 钩子 |

### 8.5 两个 **debug 专用**钩子（安全边界同 P0-4 的 `ACTION_DEBUG_JS`）

| 钩子 | 触发文件 | 作用 |
|---|---|---|
| `importLlmCredsIfDebug()` | `/sdcard/Live2DModels/llm-creds.txt`（3 行） | 导入凭据 + 立刻自测一次 |
| `debugSayIfRequested()` | `/sdcard/Live2DModels/llm-say.txt`（内容 = 用户说的话） | 触发一轮真实对话（悬浮窗未运行则自动 `startOverlay()`） |

**共同安全边界**
- `BuildConfig.DEBUG` 守卫 —— **release 构建里不执行**
- **文件存在本身就是守卫**（读完立即 `delete()`，天然只跑一次）
- 日志**只记掩码**（`sk-***500f`），原文从未进入任何日志
- 提交前用 `git grep` 复核仓库内无 key 片段

### 8.6 与原方案的偏差（如实记录）

| 原方案 | 实际 |
|---|---|
| §四 说「不需要打包任何包」 | ✅ 一致 —— 只用了已有的 engine IIFE |
| §四 提到「`currentPosture` 填当前实际姿态」 | ⚠️ **未实现** —— 没有采集当前姿态的接口，暂用默认（手势可能从非中性位置起步） |
| §四 提到「`audioPeaks` 对齐重音」 | ⚠️ **未实现** —— 需要 TTS + 音频分析，属语音阶段 |
| §四 提到「`"append"` 模式排队下一句」 | ⚠️ **未使用** —— 当前用默认 `"replace"` |
| §七 待办 2「回调 `idleActionGain`」 | ✅ v1.7.11 已做（1.9→1.55 / 1.8→1.5） |
| §七 待办 5「核对 IIFE 入口」 | ✅ v1.7.11 已做（确认走 `public.ts`） |

### 8.7 仍未做

| # | 事项 |
|---|---|
| 1 | 回复文字用气泡 / 字幕显示（现在只在设置界面的状态行） |
| 2 | 语音（TTS）—— 引擎支持 `setLipSyncEnabled` + `setAudioLevelAnalyzer`，**不要**用 `model.speak()`（见 §六） |
| 3 | `currentPosture` / `audioPeaks` / `"append"` 模式的接入 |
| 4 | 「显式指令动作」（服务端 `planner-openai` + `startSpeechMotion`）—— 与方案 A 互斥，需自己做路由 |
| 5 | 发布前评估两个 debug 钩子是否保留（它们是自动化验证的唯一手段，但也是攻击面） |

