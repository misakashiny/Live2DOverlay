import { readFile } from "node:fs/promises";
const cdi = JSON.parse(await readFile("D:/AI Dsh/_ref/miku/miku/miku.cdi3.json","utf8"));
const ids = new Set((cdi.Parameters ?? []).map(p => p.Id));
const nameOf = new Map((cdi.Parameters ?? []).map(p => [p.Id, p.Name]));

const used = ["Param125","Param130","Param131","Param132","Param133","Param134","Param135","Param136","Param137",
"ParamAngleX","ParamAngleY","ParamAngleZ","ParamBodyAngleX","ParamBodyAngleY","ParamBodyAngleZ","ParamBreath",
"ParamBustY","ParamEyeLOpen","ParamEyeROpen","ParamEyeSmile","ParamEyeLSmile","ParamEyeRSmile",
"ParamEyeBallX","ParamEyeBallY","ParamMouthForm","ParamMouthOpenY","Paramguzui",
"ParamBrowLY","ParamBrowRY","ParamBrowLAngle","ParamBrowRAngle","ParamBrowLForm","ParamBrowRForm"];

console.log("=== 参数存在性校验（对照 cdi3.json 的 " + ids.size + " 个参数）===");
const missing = [];
for (const p of used) {
  const ok = ids.has(p);
  if (!ok) missing.push(p);
  console.log("  " + (ok ? "✅" : "❌") + " " + p.padEnd(18) + (ok ? "'" + (nameOf.get(p) ?? "") + "'" : "** 模型中不存在 **"));
}
console.log("\n=== ❌ 不存在的参数（" + missing.length + " 个）===");
console.log("  " + (missing.join(", ") || "(无)"));

// 模糊找近似名
for (const m of missing) {
  const base = m.replace(/^(Param)/,"").toLowerCase();
  const near = [...ids].filter(i => i.toLowerCase().includes(base.slice(0,6)) || base.includes(i.replace(/^Param/,"").toLowerCase().slice(0,6)));
  console.log("  " + m + " 的近似候选: " + (near.slice(0,6).join(", ") || "(无)"));
}
console.log("\n=== cdi3 里所有含 Eye / Bust / Smile 的参数 ===");
for (const [id,nm] of nameOf) if (/Eye|Bust|Smile/i.test(id)) console.log("  " + id.padEnd(20) + "'" + nm + "'");
