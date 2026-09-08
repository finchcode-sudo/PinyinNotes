package com.example.pinyinnotes

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 笔记列表缓存：内存 + 磁盘两层。
 *
 * - 内存层：同一次进程运行期间，重复进入同一分类时直接秒开，零 IO。
 * - 磁盘层：App 进程被杀掉重新启动后，内存缓存是空的，
 *   这时候第一次打开分类也能先用磁盘缓存秒开占位，
 *   同时后台照常跑一次真实的 refreshList() 去刷新（对照 apkextractor 的 quickLoadFromCache 思路）。
 *
 * 磁盘缓存只存"笔记名字 + uri + lastModified"这些轻量字段，不含笔记正文，
 * 所以不会因为加了缓存就额外泄露内容——正文仍然只存在于用户选择的那个加密文件夹里。
 */
object NotesCache {
    private val cache = mutableMapOf<Uri, List<Note>>()

    fun get(categoryUri: Uri): List<Note>? = cache[categoryUri]

    fun put(categoryUri: Uri, notes: List<Note>) {
        cache[categoryUri] = notes
    }

    // ---------- 磁盘层 ----------

    private fun cacheDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, "notes_list_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun cacheFile(context: Context, categoryUri: Uri): File {
        val safeName = categoryUri.toString().hashCode().toString()
        return File(cacheDir(context), "$safeName.json")
    }

    /**
     * 从磁盘读取某个分类的笔记列表快照。需在后台线程调用（有文件 IO）。
     * 文件不存在或解析失败时返回 null（调用方应保持原有的"等真实数据"逻辑，不强行显示空列表）。
     */
    fun loadDisk(context: Context, categoryUri: Uri): List<Note>? {
        return try {
            val file = cacheFile(context, categoryUri)
            if (!file.exists()) return null
            val text = file.readText()
            if (text.isBlank()) return null
            val array = JSONArray(text)
            val result = ArrayList<Note>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: continue
                val uriStr = obj.optString("uri").takeIf { it.isNotEmpty() } ?: continue
                val lastMod = obj.optLong("lastModified", 0L)
                result.add(Note(name, Uri.parse(uriStr), lastMod))
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    /** 把某个分类的笔记列表快照写入磁盘（整份覆盖写）。需在后台线程调用。 */
    fun saveDisk(context: Context, categoryUri: Uri, notes: List<Note>) {
        try {
            val array = JSONArray()
            for (note in notes) {
                val obj = JSONObject()
                obj.put("name", note.name)
                obj.put("uri", note.uri.toString())
                obj.put("lastModified", note.lastModified)
                array.put(obj)
            }
            cacheFile(context, categoryUri).writeText(array.toString())
        } catch (e: Exception) {
            // 缓存写入失败不影响主流程，下次仍会正常刷新
        }
    }
}
