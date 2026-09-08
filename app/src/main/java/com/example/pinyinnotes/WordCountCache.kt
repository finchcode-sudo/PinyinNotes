package com.example.pinyinnotes

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 笔记字数的磁盘缓存：每个分类文件夹对应一份缓存文件，
 * key 是笔记 uri 字符串，value 是 (lastModified, 字数)。
 *
 * 背景：CategoryActivity 每次 onResume 都要重新计算所有笔记的字数用于列表展示，
 * 旧做法是对每一条笔记都单独 openInputStream + 解密（一次 SAF 跨进程 IPC），
 * 笔记一多（几百条）就很慢，而且每次返回列表页都要再跑一遍。
 *
 * 现在改成：先读磁盘缓存，只有 lastModified 发生变化（新增/编辑过）的笔记
 * 才重新解密计算字数，其余笔记直接复用缓存值——IO 量从 O(N) 降到"增量"。
 */
object WordCountCache {

    data class Entry(val lastModified: Long, val count: Int)

    private fun cacheDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, "word_count_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // 每个分类一个缓存文件，用分类 uri 的 hashCode 做文件名，避免特殊字符问题
    private fun cacheFile(context: Context, categoryUri: Uri): File {
        val safeName = categoryUri.toString().hashCode().toString()
        return File(cacheDir(context), "$safeName.json")
    }

    /** 读取某个分类的字数缓存（uri字符串 -> Entry）。文件不存在或解析失败时返回空表。 */
    fun load(context: Context, categoryUri: Uri): Map<String, Entry> {
        return try {
            val file = cacheFile(context, categoryUri)
            if (!file.exists()) return emptyMap()
            val text = file.readText()
            if (text.isBlank()) return emptyMap()
            val array = JSONArray(text)
            val result = HashMap<String, Entry>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val uriStr = obj.optString("uri").takeIf { it.isNotEmpty() } ?: continue
                val lastMod = obj.optLong("lastModified", -1L)
                val count = obj.optInt("count", 0)
                result[uriStr] = Entry(lastMod, count)
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 写入某个分类的字数缓存（整份覆盖写）。
     * 调用方每次传入"当前分类下全部笔记的最新缓存"，
     * 已删除的笔记自然不会出现在下一份文件里，不会造成缓存无限增长。
     */
    fun save(context: Context, categoryUri: Uri, entries: Map<String, Entry>) {
        try {
            val array = JSONArray()
            for ((uriStr, entry) in entries) {
                val obj = JSONObject()
                obj.put("uri", uriStr)
                obj.put("lastModified", entry.lastModified)
                obj.put("count", entry.count)
                array.put(obj)
            }
            cacheFile(context, categoryUri).writeText(array.toString())
        } catch (e: Exception) {
            // 缓存写入失败不影响主流程（下次仍会正常重新计算）
        }
    }
}
