import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
const p = "D:/AI Dsh/_ref/miku/miku/soullink.profile.json";
const raw = await readFile(p, "utf8");
const profile = JSON.parse(raw);

// 结构性断言（等价于 schema 检查的核心项）
const errs = [];
if (typeof profile.modelId !== "string") errs.push("modelId 缺失");
if (typeof profile.version !== "string") errs.push("version 缺失");
if (!profile.parameterMap || typeof profile.parameterMap !== "object") errs.push("parameterMap 缺失");
const pem = profile.privateEmotionMap ?? {};
for (const [k, m] of Object.entries(pem)) {
  if (!m.target && !(m.targets?.length)) errs.push(`${k}: 缺 target/targets`);
  const vals = [m.activeValue, m.neutralValue].filter(v => v !== undefined);
  if (vals.some(v => typeof v !== "number")) errs.push(`${k}: activeValue/neutralValue 非数字`);
  for (const axis of ["valence","arousal","dominance"]) {
    const r = m.vadRange?.[axis];
    if (r && (r.length !== 2 || r[0] > r[1] || r[0] < -1 || r[1] > 1)) errs.push(`${k}: vadRange.${axis} 非法 ${r}`);
  }
}
console.log(errs.length ? "❌ " + errs.join("; ") : "✅ 结构校验通过");

const groups = {};
for (const [k, m] of Object.entries(pem)) {
  if (!m.exclusiveGroup) continue;
  (groups[m.exclusiveGroup] ??= []).push(`${k}→${(m.targets ?? [m.target]).join("+")}(p${m.priority})`);
}
console.log("\n=== 最终 profile 摘要 ===");
console.log(JSON.stringify({
  modelId: profile.modelId, schemaVersion: profile.schemaVersion,
  parameterMap: Object.keys(profile.parameterMap ?? {}).length,
  privateEmotionMap: Object.keys(pem).length,
  idleConfig: Object.keys(profile.idleConfig ?? {}).length,
  neutralParams: Object.keys(profile.neutralParams ?? {}).length,
  parameterSmoothing: Object.keys(profile.parameterSmoothing ?? {}).length,
  capabilities: profile.capabilities,
  exclusiveGroups: groups,
  autoProfileNotes: profile.autoProfile?.notes
}, null, 2));
console.log("\n体积 =", (raw.length/1024).toFixed(1), "KB");
