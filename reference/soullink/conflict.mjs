import { readFile } from "node:fs/promises";
const sl = JSON.parse(await readFile("D:/AI Dsh/_ref/miku/miku/soullink.profile.json","utf8"));
const mine = JSON.parse(await readFile("D:/AI Dsh/车机项目/Live2D悬浮窗/src/Live2DOverlay/app/src/main/assets/live2d/model-profile.json","utf8"));

// ── 你的 model-profile.json 会写哪些参数 ──
const mineW = { actions:new Set(), anims:new Set(), watermark:new Set() };
for (const a of mine.actions ?? []) {
  for (const k of Object.keys(a.set ?? {}))   mineW.actions.add(k);
  for (const k of Object.keys(a.clear ?? {})) mineW.actions.add(k);
}
for (const an of mine.animations ?? [])
  for (const t of an.tracks ?? []) mineW.anims.add(t.param);
if (mine.watermark?.param) mineW.watermark.add(mine.watermark.param);

// ── soullink profile 会写哪些参数 ──
const slW = { parameterMap:new Set(), privateEmotion:new Set(), neutral:new Set(), smoothing:new Set() };
for (const rule of Object.values(sl.parameterMap ?? {})) {
  if (rule.target)  slW.parameterMap.add(rule.target);
  for (const t of rule.targets ?? []) slW.parameterMap.add(t);
}
for (const m of Object.values(sl.privateEmotionMap ?? {})) {
  if (m.target)  slW.privateEmotion.add(m.target);
  for (const t of m.targets ?? []) slW.privateEmotion.add(t);
}
for (const k of Object.keys(sl.neutralParams ?? {})) slW.neutral.add(k);
for (const k of Object.keys(sl.parameterSmoothing ?? {})) slW.smoothing.add(k);

const mineAll = new Set([...mineW.actions, ...mineW.anims, ...mineW.watermark]);
const slAll   = new Set([...slW.parameterMap, ...slW.privateEmotion, ...slW.neutral, ...slW.smoothing]);

const both = [...mineAll].filter(p => slAll.has(p)).sort();
const onlyMine = [...mineAll].filter(p => !slAll.has(p)).sort();
const onlySl   = [...slAll].filter(p => !mineAll.has(p)).sort();

const fmt = s => [...s].sort().join(", ") || "(无)";
console.log("=== 你的 model-profile.json 会写的参数（" + mineAll.size + " 个）===");
console.log("  动作库 set/clear :", fmt(mineW.actions));
console.log("  关键帧 tracks    :", fmt(mineW.anims));
console.log("  水印             :", fmt(mineW.watermark));
console.log("\n=== soullink profile 会写的参数（" + slAll.size + " 个）===");
console.log("  parameterMap     :", fmt(slW.parameterMap));
console.log("  privateEmotionMap:", fmt(slW.privateEmotion));
console.log("  neutralParams    :", fmt(slW.neutral));
console.log("\n=== ★ 两边都写 = 冲突面（" + both.length + " 个）===");
if (!both.length) console.log("  ✅ 无冲突");
else for (const p of both) {
  const w = [];
  if (mineW.actions.has(p))  w.push("你的 actions");
  if (mineW.anims.has(p))    w.push("你的 animations");
  if (mineW.watermark.has(p))w.push("你的 watermark");
  if (slW.parameterMap.has(p))    w.push("parameterMap");
  if (slW.privateEmotion.has(p))  w.push("privateEmotionMap");
  if (slW.neutral.has(p))         w.push("neutralParams");
  console.log("  " + p.padEnd(16) + " ← " + w.join(" + "));
}
console.log("\n=== 只有你写（" + onlyMine.length + "）===");
console.log("  " + (onlyMine.join(", ") || "(无)"));
console.log("\n=== 只有 Soullink 写（" + onlySl.length + "）===");
console.log("  " + (onlySl.join(", ") || "(无)"));
