package com.live2d.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 角色人设仓库 —— AI 角色系统 **P1** 的交付物。
 *
 * 职责边界（刻意收窄）：
 *   ✅ 扫描 / 加载 / 校验 / 列举人设，以及把选中的人设交给渲染层
 *   ❌ 不做情绪计算（P2 的 EmotionEngine）
 *   ❌ 不做 LLM 调用（P4 的 DialogueClient）
 *   ❌ 不解析 `llm.endpointRef`（P1 阶段密钥库还不存在）
 *
 * 加载优先级（高 → 低）：
 *   1. `/sdcard/Live2DModels/<id>/persona.json` —— 随模型走，放 sdcard 上可热改
 *   2. `assets/personas/<id>.json`             —— 内置，保证开箱可用
 *
 * 为什么不做 JSON Schema 库：见 assets/persona-schema.json 的 $comment。
 * 这里的 [validate] 是那份 Schema 的等价实现，**改一处必须同步改另一处**。
 */
object PersonaStore {

    /** 内置人设目录（assets 下） */
    private const val ASSET_DIR = "personas"

    /** 每模型目录下的人设文件名 */
    private const val FILE_NAME = "persona.json"

    /** 模型根目录（与人设的 sdcard 覆盖路径一致） */
    private const val MODEL_ROOT = "/sdcard/Live2DModels"

    /** 枚举结果：列表里显示一行所需的最小信息 */
    data class Summary(
        val id: String,
        val name: String,
        /** "内置" 或 "SD" —— 让用户知道这份人设从哪来、改了会不会被覆盖 */
        val source: String,
        val tags: String
    )

    /** 加载完成的人设 */
    class Persona(
        val id: String,
        val name: String,
        val tags: List<String>,
        /** avatar.model；空串表示「不切换模型」 */
        val avatarModel: String,
        /** personality.traits，0~1 */
        val traits: Map<String, Float>,
        /** emotionBias，-1~+1（偏置，不是绝对值） */
        val emotionBias: Map<String, Float>,
        /** 原始 JSON 文本，用于注入页面 / 后续交给情绪引擎 */
        val rawJson: String
    )

    // ------------------------------------------------------------------
    // 枚举
    // ------------------------------------------------------------------

    /**
     * 列出所有可用人设。同 id 时 sdcard 覆盖 assets。
     *
     * 单个文件损坏不影响整体 —— 坏的那个跳过并记日志，其余照常列出。
     */
    fun list(ctx: Context): List<Summary> {
        val out = LinkedHashMap<String, Summary>()

        // 1) 内置
        try {
            val names = ctx.assets.list(ASSET_DIR) ?: emptyArray()
            for (n in names) {
                if (!n.endsWith(".json", ignoreCase = true)) continue
                val json = readAsset(ctx, "$ASSET_DIR/$n") ?: continue
                val s = summaryOf(json, "内置") ?: continue
                out[s.id] = s
            }
        } catch (t: Throwable) {
            L2DLog.w(L2DLog.Mod.AI, "枚举内置人设失败", "err=${t.javaClass.simpleName}")
        }

        // 2) sdcard（覆盖同名内置）
        try {
            val root = File(MODEL_ROOT)
            val dirs = root.listFiles()
            if (dirs != null) {
                for (d in dirs) {
                    if (!d.isDirectory) continue
                    val f = File(d, FILE_NAME)
                    if (!f.isFile) continue
                    val s = summaryOf(f.readText(), "SD") ?: continue
                    out[s.id] = s
                }
            }
        } catch (t: Throwable) {
            L2DLog.w(L2DLog.Mod.AI, "枚举 sdcard 人设失败", "err=${t.javaClass.simpleName}")
        }

        return out.values.toList()
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /** 按 id 加载；找不到或校验不过返回 null（调用方负责提示） */
    fun load(ctx: Context, id: String): Persona? {
        if (id.isBlank()) return null

        // 1) sdcard 覆盖
        val sd = File(File(MODEL_ROOT, id), FILE_NAME)
        if (sd.isFile) {
            try {
                val json = sd.readText()
                validate(json)?.let {
                    L2DLog.w(L2DLog.Mod.AI, "sdcard 人设校验失败，回退内置", "id=$id 原因=$it")
                    return@load loadAsset(ctx, id)
                }
                L2DLog.i(L2DLog.Mod.AI, "已加载 sdcard 人设", "id=$id path=${sd.absolutePath}")
                return parse(json)
            } catch (t: Throwable) {
                L2DLog.w(L2DLog.Mod.AI, "读取 sdcard 人设失败，回退内置", "id=$id err=${t.javaClass.simpleName}")
            }
        }

        return loadAsset(ctx, id)
    }

    private fun loadAsset(ctx: Context, id: String): Persona? {
        val json = readAsset(ctx, "$ASSET_DIR/$id.json") ?: run {
            L2DLog.w(L2DLog.Mod.AI, "人设不存在", "id=$id")
            return null
        }
        validate(json)?.let {
            L2DLog.w(L2DLog.Mod.AI, "内置人设校验失败", "id=$id 原因=$it")
            return null
        }
        L2DLog.i(L2DLog.Mod.AI, "已加载内置人设", "id=$id")
        return parse(json)
    }

    private fun readAsset(ctx: Context, path: String): String? = try {
        ctx.assets.open(path).bufferedReader().use { it.readText() }
    } catch (t: Throwable) {
        null
    }

    // ------------------------------------------------------------------
    // 校验（persona-schema.json 的等价实现）
    // ------------------------------------------------------------------

    /**
     * 校验人设结构。
     * @return null = 合法；否则返回**给人看的中文原因**
     */
    fun validate(json: String): String? {
        val o = try {
            JSONObject(json)
        } catch (t: Throwable) {
            return "不是合法的 JSON（${t.javaClass.simpleName}）"
        }

        if (o.optInt("schemaVersion", -1) != 1) return "schemaVersion 必须为 1"

        val id = o.optString("id", "")
        if (!Regex("^[a-zA-Z0-9_-]{1,32}$").matches(id)) {
            return "id 非法（只允许字母/数字/下划线/连字符，1~32 位，实际=\"$id\"）"
        }

        val name = o.optString("name", "")
        if (name.isBlank() || name.length > 32) return "name 缺失或超长（1~32 字）"

        // traits 取值域 0~1
        val traits = o.optJSONObject("personality")?.optJSONObject("traits")
        if (traits != null) {
            for (k in traits.keys()) {
                val v = traits.optDouble(k, Double.NaN)
                if (v.isNaN() || v < 0.0 || v > 1.0) return "personality.traits.$k 必须为 0~1（实际=$v）"
            }
        }

        // emotionBias 取值域 -1~+1
        val bias = o.optJSONObject("emotionBias")
        if (bias != null) {
            for (k in bias.keys()) {
                if (k.startsWith("_")) continue          // 允许 _comment 之类的说明键
                val v = bias.optDouble(k, Double.NaN)
                if (v.isNaN() || v < -1.0 || v > 1.0) return "emotionBias.$k 必须为 -1~+1（实际=$v）"
            }
        }

        // 权重之和不必为 1，但必须都是 0~1
        val phys = o.optJSONObject("emotionPhysics")
        if (phys != null) {
            for (k in listOf("maxDeltaPerSec", "aiWeight", "localWeight")) {
                if (phys.has(k)) {
                    val v = phys.optDouble(k, Double.NaN)
                    if (v.isNaN() || v < 0.0 || v > 1.0) return "emotionPhysics.$k 必须为 0~1（实际=$v）"
                }
            }
        }

        // animations 的值必须是字符串数组（id 是否存在于 model-profile 由页面告警，这里不拦）
        val anim = o.optJSONObject("animations")
        if (anim != null) {
            for (k in anim.keys()) {
                if (k.startsWith("_")) continue
                val v = anim.opt(k)
                if (v is JSONArray) {
                    for (i in 0 until v.length()) {
                        if (v.opt(i) !is String) return "animations.$k[$i] 必须是字符串（动画 id）"
                    }
                } else if (v is JSONObject) {
                    for (ek in v.keys()) {
                        val arr = v.optJSONArray(ek) ?: return "animations.$k.$ek 必须是数组"
                        for (i in 0 until arr.length()) {
                            if (arr.opt(i) !is String) return "animations.$k.$ek[$i] 必须是字符串"
                        }
                    }
                } else {
                    return "animations.$k 必须是数组或对象"
                }
            }
        }

        return null
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private fun summaryOf(json: String, source: String): Summary? {
        if (validate(json) != null) return null
        return try {
            val o = JSONObject(json)
            Summary(
                id = o.getString("id"),
                name = o.getString("name"),
                source = source,
                tags = tagsOf(o).joinToString(" · ")
            )
        } catch (t: Throwable) {
            null
        }
    }

    private fun parse(json: String): Persona {
        val o = JSONObject(json)
        return Persona(
            id = o.optString("id"),
            name = o.optString("name"),
            tags = tagsOf(o),
            avatarModel = o.optJSONObject("avatar")?.optString("model", "") ?: "",
            traits = floatMap(o.optJSONObject("personality")?.optJSONObject("traits")),
            emotionBias = floatMap(o.optJSONObject("emotionBias")),
            rawJson = json
        )
    }

    private fun tagsOf(o: JSONObject): List<String> {
        val arr = o.optJSONArray("tags") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out
    }

    /** 把 JSON 对象转成 Map，跳过 `_comment` 这类说明键 */
    private fun floatMap(o: JSONObject?): Map<String, Float> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, Float>()
        for (k in o.keys()) {
            if (k.startsWith("_")) continue
            val v = o.optDouble(k, Double.NaN)
            if (!v.isNaN()) out[k] = v.toFloat()
        }
        return out
    }
}
