import { readFile } from "node:fs/promises";
const html = await readFile("D:/AI Dsh/车机项目/Live2D悬浮窗/src/Live2DOverlay/app/src/main/assets/live2d/live2d_decor.html","utf8");
const cdi = JSON.parse(await readFile("D:/AI Dsh/_ref/miku/miku/miku.cdi3.json","utf8"));
const ids = new Set((cdi.Parameters ?? []).map(p => p.Id));
const nameOf = new Map((cdi.Parameters ?? []).map(p => [p.Id, p.Name]));

// 抓所有字符串字面量形式的参数名（setParam/addParam/getParameterIndex 等）
const found = new Map();  // param -> Set(行号)
const lines = html.split("\n");
const re = /["']((?:Param|PARAM_)[A-Za-z0-9_]+)["']/g;
lines.forEach((ln, i) => {
  let m; re.lastIndex = 0;
  while ((m = re.exec(ln))) {
    if (!found.has(m[1])) found.set(m[1], []);
    found.get(m[1]).push(i + 1);
  }
});
console.log("=== 页面里出现的参数名（" + found.size + " 个）===");
const bad = [];
for (const [p, lns] of [...found].sort()) {
  const ok = ids.has(p);
  if (!ok) bad.push([p, lns]);
  console.log("  " + (ok ? "✅" : "❌") + " " + p.padEnd(22) + (ok ? "'" + (nameOf.get(p) ?? "") + "'" : "**不存在**") + "  行:" + lns.slice(0,8).join(","));
}
console.log("\n=== ❌ 无效参数（" + bad.length + " 个）→ 这些都是静默失效 ===");
for (const [p, lns] of bad) {
  const base = p.replace(/^Param/,"").toLowerCase();
  const near = [...ids].filter(i => {
    const s = i.replace(/^Param/,"").toLowerCase();
    return s.includes(base.slice(0,5)) || base.includes(s.slice(0,5));
  }).slice(0,5);
  console.log("  " + p.padEnd(22) + "行:" + lns.slice(0,10).join(",") + "   候选: " + (near.join(", ") || "无"));
}
