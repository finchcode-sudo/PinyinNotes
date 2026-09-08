package com.example.pinyinnotes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/** 根目录下的分类（子文件夹）列表与新建，分类名也加密 */
class CategoryRepository(private val context: Context, treeUri: Uri) {

    private val rootDoc: DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("无法访问所选文件夹")

    // ✅ 跟 NoteRepository.getAllNotes() 同款优化：一次批量 query 把所有子项的
    // id + 名字 + MIME 一起拿回来，而不是 rootDoc.listFiles() 之后再对每一项单独
    // 调用 .isDirectory / .name（SAF 下每次单独属性访问都是一次独立的跨进程查询，
    // 分类一多——几十上百个——主页面就会明显卡顿，此前只优化了笔记列表，漏了分类列表）
    fun getAllCategories(): List<Category> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            rootDoc.uri, DocumentsContract.getDocumentId(rootDoc.uri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val result = mutableListOf<Category>()
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    try {
                        val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                        if (mime != DocumentsContract.Document.MIME_TYPE_DIR) continue // 只要文件夹

                        val encName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                            ?: continue
                        val docId = cursor.getString(idIdx)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(rootDoc.uri, docId)

                        val name = CryptoUtil.decryptFileName(encName)
                        result.add(Category(name, uri))
                    } catch (e: Exception) {
                        // 跳过单条解析失败的分类，不影响其它分类
                    }
                }
            }
        } catch (e: Exception) {
            // 查询失败就退回空列表，避免整个 App 崩掉
        }
        return result.sortedWith(compareBy({ PinyinUtils.getFirstLetter(it.name) }, { it.name }))
    }

    fun addCategory(name: String): Category? {
        val encName = CryptoUtil.encryptToFileName(name)
        val dir = rootDoc.createDirectory(encName) ?: return null
        return Category(name, dir.uri)
    }

    fun renameCategory(context: Context, uri: Uri, newName: String): Category? {
        val newFolderName = CryptoUtil.encryptToFileName(newName)
        val newUri = DocStore.rename(context, uri, newFolderName) ?: return null
        return Category(newName, newUri)
    }
}
