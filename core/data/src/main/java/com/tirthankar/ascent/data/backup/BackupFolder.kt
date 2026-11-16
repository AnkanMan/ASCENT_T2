package com.tirthankar.ascent.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Where backup files go: the folder you picked, or a plain directory in tests. */
interface BackupFolder {
    fun list(): List<String>
    fun write(name: String, block: (OutputStream) -> Unit)
    fun delete(name: String): Boolean
}

class DirBackupFolder(private val dir: File) : BackupFolder {
    override fun list(): List<String> = dir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()

    override fun write(name: String, block: (OutputStream) -> Unit) {
        dir.mkdirs()
        File(dir, name).outputStream().use(block)
    }

    override fun delete(name: String): Boolean = File(dir, name).delete()
}

/**
 * A folder picked once through the Storage Access Framework (PHASE5.md section 3), with
 * its permission persisted. Any sync tool can mirror it off the phone.
 */
class SafBackupFolder(context: Context, private val tree: Uri) : BackupFolder {
    private val resolver = context.contentResolver
    private val treeDocument = DocumentsContract.getTreeDocumentId(tree)
    private val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocument)

    /** Display name → document id. */
    private fun children(): Map<String, String> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocument)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return resolver.query(uri, columns, null, null, null)?.use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(1), c.getString(0)) }
        } ?: throw IOException("The backup folder can't be read")
    }

    override fun list(): List<String> = children().keys.toList()

    override fun write(name: String, block: (OutputStream) -> Unit) {
        val doc = DocumentsContract.createDocument(resolver, parent, MIME_ZIP, name)
            ?: throw IOException("The backup folder can't be written")
        (resolver.openOutputStream(doc, "w") ?: throw IOException("The backup file can't be opened")).use(block)
    }

    override fun delete(name: String): Boolean {
        val id = children()[name] ?: return false
        return DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))
    }

    /** The folder's name, for Settings. */
    fun label(): String? =
        resolver.query(parent, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private companion object {
        const val MIME_ZIP = "application/zip"
    }
}
