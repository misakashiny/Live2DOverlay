import { readFile } from "node:fs/promises";
import { validateModelProfile } from "@soullink-emotion/engine/internal";

const sl = JSON.parse(await readFile("D:/AI Dsh/_ref/miku/miku/soullink.profile.json","utf8"));
const mine = JSON.parse(await readFile("D:/AI Dsh/车机项目/Live2D悬浮窗/src/Live2DOverlay/app/src/main/assets/live2d/model-profile.json","utf8"));

const v = validateModelProfile(sl);
console.log("=== SDK 官方 validateModelProfile ===");
console.log(JSON.stringify(v, null, 2));

const row = (k, a, b) => console.log("  " + k.padEnd(22) + "| " + String(a).padEnd(34) + "| " + String(b));
console.log("\n=== 逐项对照 ===");
row("维度","你的 model-profile.json","soullink.profile.json");
console.log("  " + "-".repeat(22) + "+" + "-".repeat(35) + "+" + "-".repeat(30));
row("schemaVersion", mine.schemaVersion, sl.schemaVersion);
row("离散动作库", (mine.actions?.length ?? 0) + " 个 (actions[])", "无此概念");
row("关键帧动画", (mine.animations?.length ?? 0) + " 个 (animations[])", "无此概念");
row("FACS 参数映射", "无", Object.keys(sl.parameterMap??{}).length + " 个通道");
row("语义情绪(VAD)", "无", Object.keys(sl.privateEmotionMap??{}).length + " 条");
row("排他组", "无", [...new Set(Object.values(sl.privateEmotionMap??{}).map(m=>m.exclusiveGroup).filter(Boolean))].length + " 组");
row("模型能力声明", "无", Object.values(sl.capabilities??{}).filter(Boolean).length + "/" + Object.keys(sl.capabilities??{}).length + " 项 true");
row("待机配置", "无(页面硬编码眨眼/摆动)", Object.keys(sl.idleConfig??{}).length + " 项");
row("中性值", "无", Object.keys(sl.neutralParams??{}).length + " 个参数");
row("参数平滑", "无", Object.keys(sl.parameterSmoothing??{}).length + " 个参数");
row("水印参数", "watermark.param=Param137", "不纳入(宿主压制)");
row("裁切", "layout.clipBottomDesignY", "不纳入(属渲染层)");
row("原生动作/表情", "无", (sl.nativeAnimations?.expressions?.length??0) + " 表情 / " + (sl.nativeAnimations?.motions?.length??0) + " 动作");

console.log("\n=== 7 个离散动作：你的实现 vs Soullink 实现 ===");
const map = [
  ["脸红","Param130","actions[] set 1 / 2600ms","privateEmotionMap.blush  (shy 窗口, p60)"],
  ["前倾","Param132","actions[] set 1 / 1800ms","privateEmotionMap.leanForward (curious/affectionate, p40)"],
  ["圈圈","Param125","actions[] set 1 / 2400ms","privateEmotionMap.spin  (excited, p55)"],
  ["大葱","Param133","actions[] set 1 / 2600ms","privateEmotionMap.scallion (happy, 组 miku_prop)"],
  ["唱歌","Param134","actions[] set 1 / 2600ms","privateEmotionMap.sing     (happy/affectionate)"],
  ["比心","Param135","actions[] set 1 / 2600ms","privateEmotionMap.heart    (affectionate/happy)"],
  ["QQ人","Param131+136","actions[] set 两个参数","privateEmotionMap.qqMode   (excited/happy, p50)"],
  ["水印","Param137","watermark.param 压制","未纳入(宿主页面 forceWatermarkOff)"]
];
for (const [n,p,a,b] of map) console.log("  " + n.padEnd(6) + p.padEnd(14) + a.padEnd(28) + "→  " + b);
