package com.example.pinyinnotes

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 主页分类列表缓存：内存 + 磁盘两层，思路跟 NotesCache 完全一致。
 *
 * - 内存层：同一次进程运行期间，重复回到主页（比如从分类页返回）秒开，零 IO。
 * - 磁盘层：App 进程被杀掉重启后，内存层是空的，第一次打开主页也能先用磁盘快照
 *   秒开占位，真实数据由 MainActivity.refreshList() 在后台刷新后无感替换。
 *
 * 只存"分类名字 + uri"，跟 NotesCache 一样不含任何笔记正文。
 */
object CategoriesCache {
    private var memCache: List<Category>? = null

    fun get(): List<Category>? = memCache

    fun put(categories: List<Category>) {
        memCache = categories
    }

    // ---------- 磁盘层 ----------

    private const val CACHE_FILE_NAME = "categories_cache.json"

    private fun cacheFile(context: Context): File {
        return File(context.applicationContext.filesDir, CACHE_FILE_NAME)
    }

    /** 从磁盘读取分类列表快照。需在后台线程调用。文件不存在/解析失败时返回 null。 */
    fun loadDisk(context: Context): List<Category>? {
        return try {
            val file = cacheFile(context)
            if (!file.exists()) return null
            val text = file.readText()
            if (text.isBlank()) return null
            val array = JSONArray(text)
            val result = ArrayList<Category>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: continue
                val uriStr = obj.optString("uri").takeIf { it.isNotEmpty() } ?: continue
                result.add(Category(name, Uri.parse(uriStr)))
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    /** 把分类列表快照写入磁盘（整份覆盖写）。需在后台线程调用。 */
    fun saveDisk(context: Context, categories: List<Category>) {
        try {
            val array = JSONArray()
            for (category in categories) {
                val obj = JSONObject()
                obj.put("name", category.name)
                obj.put("uri", category.uri.toString())
                array.put(obj)
            }
            cacheFile(context).writeText(array.toString())
        } catch (e: Exception) {
            // 缓存写入失败不影响主流程
        }
    }
}
