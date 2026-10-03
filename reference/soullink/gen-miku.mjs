import { writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { Live2DProfileAutoGenerator } from "@soullink-emotion/profile-generator";

const modelsRoot = "D:/AI Dsh/_ref/miku";   // adb pull 多套了一层
const modelDir = "miku";

const generator = new Live2DProfileAutoGenerator({
  modelsRoot, modelsBaseUrl: "", defaultModelDir: modelDir,
  useConfiguredOpenAI: false
});

const result = await generator.ensure({ modelDir, displayName: "Miku", force: true });
const p = result.profile;
await writeFile(resolve(modelsRoot, modelDir, "soullink.profile.json"),
  JSON.stringify(p, null, 2) + "\n", "utf8");

const cat = p.nativeAnimations ?? {};
console.log(JSON.stringify({
  generated: result.generated, reason: result.reason, provider: result.provider,
  schemaVersion: p.schemaVersion, modelId: p.modelId,
  mappedFACS: Object.keys(p.parameterMap ?? {}).length,
  facsKeys: Object.keys(p.parameterMap ?? {}),
  privateEmotions: Object.keys(p.privateEmotionMap ?? {}).length,
  privateEmotionKeys: Object.keys(p.privateEmotionMap ?? {}),
  customParams: Object.keys(p.customParams ?? {}).length,
  idleConfigKeys: Object.keys(p.idleConfig ?? {}).length,
  expressions: (cat.expressions ?? []).length,
  expressionNames: (cat.expressions ?? []).map(e => e.name),
  motions: (cat.motions ?? []).length,
  capabilities: p.capabilities,
  notes: result.notes
}, null, 2));
