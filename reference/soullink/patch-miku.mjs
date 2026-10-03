import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { loadModelProfile } from "@soullink-emotion/engine";

const modelsRoot = "D:/AI Dsh/_ref/miku";
const modelDir = "miku";
const profilePath = resolve(modelsRoot, modelDir, "soullink.profile.json");

const profile = JSON.parse(await readFile(profilePath, "utf8"));

// ── 人工补的 privateEmotionMap ────────────────────────────────────────────
// 数据来源：8 个 .exp3.json 的实际内容（不是文档）+ cdi3.json 的显示名。
// VAD 三轴范围均为 -1..+1；emotions 取值来自 emotionVADPresets 的 15 个键。
//
// ★ 关键：葱/唱歌/比心 在原 exp3 里各自把 133/134/135 全写一遍（一个 1、两个 0）
//   —— 那是「排他组」的手写实现。SDK 的 exclusiveGroup 原生支持：
//   组内 amount+priority/100 最高者胜，败者 amount 归 0 → 落回 neutralValue(0)。
//   所以这里只声明「自己那一个参数」，互斥由 exclusiveGroup 保证。
profile.privateEmotionMap = {
  blush: {
    target: "Param130", category: "blush",
    emotions: ["shy"], triggerMode: "any",
    vadRange: { valence: [0.10, 0.80], arousal: [0.30, 1.00], dominance: [-1.00, -0.10] },
    activeValue: 1, neutralValue: 0, intensity: 0.9, priority: 60,
    source: "manual", confidence: 0.95
  },
  qqMode: {
    targets: ["Param131", "Param136"],
    emotions: ["excited", "happy"], triggerMode: "any",
    vadRange: { valence: [0.60, 1.00], arousal: [0.60, 1.00] },
    activeValue: 1, neutralValue: 0, intensity: 0.8, priority: 50,
    exclusiveGroup: "miku_pose", source: "manual", confidence: 0.9
  },
  spin: {
    target: "Param125",
    emotions: ["excited"], triggerMode: "any",
    vadRange: { valence: [0.50, 1.00], arousal: [0.70, 1.00] },
    activeValue: 1, neutralValue: 0, intensity: 0.75, priority: 55,
    exclusiveGroup: "miku_pose", source: "manual", confidence: 0.9
  },
  leanForward: {
    target: "Param132",
    emotions: ["curious", "affectionate"], triggerMode: "any",
    vadRange: { valence: [0.15, 1.00], arousal: [0.10, 0.90] },
    activeValue: 1, neutralValue: 0, intensity: 0.7, priority: 40,
    exclusiveGroup: "miku_pose", source: "manual", confidence: 0.85
  },
  scallion: {
    target: "Param133",
    emotions: ["happy"], triggerMode: "any",
    vadRange: { valence: [0.55, 1.00], arousal: [0.25, 0.90] },
    activeValue: 1, neutralValue: 0, intensity: 0.7, priority: 30,
    exclusiveGroup: "miku_prop", source: "manual", confidence: 0.85
  },
  sing: {
    target: "Param134",
    emotions: ["happy", "affectionate"], triggerMode: "any",
    vadRange: { valence: [0.45, 1.00], arousal: [0.15, 0.80] },
    activeValue: 1, neutralValue: 0, intensity: 0.7, priority: 30,
    exclusiveGroup: "miku_prop", source: "manual", confidence: 0.85
  },
  heart: {
    target: "Param135",
    emotions: ["affectionate", "happy"], triggerMode: "any",
    vadRange: { valence: [0.65, 1.00], arousal: [0.05, 0.70] },
    activeValue: 1, neutralValue: 0, intensity: 0.8, priority: 30,
    exclusiveGroup: "miku_prop", source: "manual", confidence: 0.9
  }
};

// 记录来源，便于将来重新生成时知道哪些是人工的
profile.autoProfile = {
  ...(profile.autoProfile ?? {}),
  provider: "heuristic",
  notes: [
    ...((profile.autoProfile?.notes) ?? []),
    "privateEmotionMap 由人工补齐（来源：8 个 exp3 文件的实际内容 + cdi3 显示名）",
    "Param137(水印) 未纳入 privateEmotionMap —— 它是反向语义的显示开关，不是情绪，由宿主页面压制"
  ]
};

await writeFile(profilePath, JSON.stringify(profile, null, 2) + "\n", "utf8");

// ── 用 SDK 自己的 loader 校验最终 profile 是否合法 ─────────────────────
const { profile: loaded } = await loadModelProfile(profilePath);
console.log("=== ✅ 用 SDK 的 loadModelProfile 校验通过 ===");
console.log(JSON.stringify({
  modelId: loaded.modelId,
  schemaVersion: loaded.schemaVersion,
  parameterMap: Object.keys(loaded.parameterMap ?? {}).length,
  privateEmotionMap: Object.keys(loaded.privateEmotionMap ?? {}).length,
  privateEmotionKeys: Object.keys(loaded.privateEmotionMap ?? {}),
  idleConfig: Object.keys(loaded.idleConfig ?? {}).length,
  neutralParams: Object.keys(loaded.neutralParams ?? {}).length,
  parameterSmoothing: Object.keys(loaded.parameterSmoothing ?? {}).length,
  customParams: Object.keys(loaded.customParams ?? {}).length,
  capabilities: loaded.capabilities,
  exclusiveGroups: [...new Set(Object.values(loaded.privateEmotionMap ?? {})
      .map(m => m.exclusiveGroup).filter(Boolean))]
}, null, 2));
console.log("文件体积 =", ((await readFile(profilePath)).length / 1024).toFixed(1), "KB");
