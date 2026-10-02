package com.live2d.overlay

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * Live2D 模型定位工具。
 *
 * ## 为什么需要它
 *
 * Live2D 模型不是单个文件，而是「一个描述文件 + 若干兄弟资源」的集合：
 * ```
 * miku/
 * ├── miku.model3.json      入口描述文件（引用下面的资源）
 * ├── miku.moc3             网格数据
 * ├── miku.4096/           纹理目录（内含 texture_00.png 等）
 * └── miku.physics3.json    物理演算
 * ```
 * 页面拿到的 `model=` 参数必须是一个**可被相对路径解析**的路径 —— 也就是必须落在文件系统上
 * （`file://`），页面才能顺着它找到同目录的 `.moc3` 与纹理。
 *
 * 因此**不能用 SAF 的单文件选择器**：它返回 `content://` URI，只能读那一个文件，
 * 无法让页面访问同目录的兄弟资源。正确做法是拿到**目录**（或直接的文件路径），
 * 枚举其中的模型描述文件。
 */
object ModelLocator {

    private const val TAG = "ModelLocator"

    /** 模型描述文件的两种命名规范（Cubism 4/3 与 Cubism 2） */
    private val MODEL_SUFFIXES = listOf(".model3.json", ".model.json")

    /**
     * 从 model3.json 中提取所引用资源文件名的正则。
     *
     * 匹配 json 里形如 "miku.moc3"、"miku.4096/texture_00.png" 的字符串值。
     */
    private const val RESOURCE_PATTERN =
        "\"([^\"]+\\.(moc3|moc|png|physics3\\.json|cdi3\\.json))\""

    /** 扫描结果 */
    data class ScanResult(
        /** 找到的模型描述文件（绝对路径） */
        val models: List<File>,
        /** 扫描的目录 */
        val dir: File,
        /** 扫描中遇到的问题说明（成功时为 null） */
        val error: String? = null
    )

    /**
     * 扫描指定目录，找出其中的 Live2D 模型描述文件。
     *
     * 只扫描一层（不递归），因为标准模型结构中描述文件就位于模型根目录。
     * 若根目录没有，则向下找一层（兼容 `xxx/model/miku.model3.json` 这类嵌套布局）。
     */
    fun scan(dir: File): ScanResult {
        if (!dir.exists()) {
            return ScanResult(emptyList(), dir, "目录不存在")
        }
        if (!dir.isDirectory) {
            return ScanResult(emptyList(), dir, "目标不是目录")
        }
        if (!dir.canRead()) {
            return ScanResult(emptyList(), dir, "目录不可读（权限不足）")
        }

        val found = mutableListOf<File>()

        // 第一层：直接子文件
        dir.listFiles()?.forEach { f ->
            if (f.isFile && isModelFile(f)) found.add(f)
        }

        // 第二层：子目录中再找一层（常见于模型包多套一层外壳）
        if (found.isEmpty()) {
            dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                sub.listFiles()?.forEach { f ->
                    if (f.isFile && isModelFile(f)) found.add(f)
                }
            }
        }

        return if (found.isEmpty()) {
            ScanResult(emptyList(), dir, "该目录下未找到 .model3.json / .model.json")
        } else {
            ScanResult(found.sortedBy { it.name }, dir, null)
        }
    }

    private fun isModelFile(f: File): Boolean {
        val n = f.name.lowercase()
        return MODEL_SUFFIXES.any { n.endsWith(it) }
    }

    /**
     * 校验模型文件的完整性。
     *
     * 检查描述文件里引用的关键资源是否真实存在于同目录，
     * 提前发现「只拷了 json 没拷资源」这类会导致白屏的情况。
     */
    fun validate(modelJson: File): String? {
        if (!modelJson.exists()) return "模型文件不存在"
        if (!modelJson.canRead()) return "模型文件不可读"

        val dir = modelJson.parentFile ?: return "无法确定模型所在目录"

        val text = try {
            modelJson.readText()
        } catch (t: Throwable) {
            return "无法读取模型文件：${t.message}"
        }

        // 从 json 中提取被引用的文件名（不引入 JSON 库，做轻量匹配即可）
        val referenced = Regex(RESOURCE_PATTERN)
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()

        if (referenced.isEmpty()) {
            return "模型文件中未找到资源引用，可能不是有效的 Live2D 模型"
        }

        val missing = referenced.filter { rel ->
            val f = File(dir, rel)
            !f.exists()
        }

        return if (missing.isEmpty()) {
            null
        } else {
            val preview = missing.take(3).joinToString("、")
            val more = if (missing.size > 3) " 等 ${missing.size} 个" else ""
            "缺少资源文件：$preview$more（请确认模型未拆散拷贝）"
        }
    }

    /**
     * 尝试把 SAF 的 `tree://` 目录 URI 解析为真实文件路径。
     *
     * 优先走 `DocumentFile` 的 documentId 反解（多数 ROM 可行）；
     * 失败则返回 null，由调用方引导用户改用直接路径。
     */
    fun treeUriToFile(context: Context, treeUri: Uri): File? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            // documentId 形如 "primary:Download/models" 或 "xxxx-xxxx:models"
            val parts = docId.split(":", limit = 2)
            if (parts.size != 2) return null

            val volume = parts[0]
            val relative = parts[1]

            val base = when {
                volume.equals("primary", ignoreCase = true) -> "/sdcard"
                else -> "/storage/$volume"
            }
            File(base, relative)
        } catch (t: Throwable) {
            Log.w(TAG, "treeUriToFile failed", t)
            null
        }
    }

    /** 把 SAF 的单文件 URI 解析为真实路径（用于兜底） */
    fun documentUriToFile(uri: Uri): File? {
        return try {
            val docId = DocumentsContract.getDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            if (parts.size != 2) return null
            val volume = parts[0]
            val relative = parts[1]
            val base = when {
                volume.equals("primary", ignoreCase = true) -> "/sdcard"
                else -> "/storage/$volume"
            }
            File(base, relative)
        } catch (t: Throwable) {
            Log.w(TAG, "documentUriToFile failed", t)
            null
        }
    }

    /** 默认推荐目录：模型通常放在这里 */
    fun defaultModelsDir(): File = File("/sdcard/Live2DModels")
}
