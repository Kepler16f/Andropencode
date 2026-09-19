package ai.opencode.mobile.bridge

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSObject
import com.getcapacitor.JSArray
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * SAF bridge. Lets the SPA pick a directory tree, persist the URI grant, and
 * list / read / write / create / delete documents inside it.
 *
 * JS contract (all URIs are `content://` strings returned by [pickDirectory]
 * or [list]):
 *   pickDirectory()                                    -> { uri, name }
 *   list({ uri })                                       -> { entries: [{ uri, name, directory, mime, size, modified }] }
 *   read({ uri })                                       -> { content: <base64> }
 *   write({ uri, content })                             -> {}
 *   create({ dirUri, name, mime })                      -> { uri }
 *   remove({ uri })                                     -> {}
 *   resolve({ uri })                                    -> { name, directory, mime, size, modified }
 */
@CapacitorPlugin(name = "FsBridge")
class FsBridgePlugin : Plugin() {

    companion object {
        private const val TAG = "FsBridge"
        private val GRANT_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        private val PERSIST_FLAGS =
            GRANT_FLAGS or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }

    @PluginMethod
    fun pickDirectory(call: PluginCall) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply { addFlags(PERSIST_FLAGS) }
        call.setKeepAlive(true)
        startActivityForResult(call, intent, "onDirectoryPicked")
    }

    @ActivityCallback
    private fun onDirectoryPicked(call: PluginCall, result: ActivityResult) {
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri == null) {
            call.reject("directory pick canceled")
            return
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, GRANT_FLAGS)
            Log.i(TAG, "persisted URI permission for $uri")
        }.onFailure { Log.w(TAG, "failed to persist URI permission", it) }
        call.resolve(entry(uri))
    }

    @PluginMethod
    fun list(call: PluginCall) {
        val uri = uri(call)
        if (uri == null) {
            call.reject("uri is required")
            return
        }
        val entries = JSArray()
        val dirId = DocumentsContract.getTreeDocumentId(uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, dirId)
        queryChildren(childrenUri) { cursor ->
            while (cursor.moveToNext()) {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId(cursor))
                entries.put(entry(docUri, cursor))
            }
        }
        call.resolve(JSObject().put("entries", entries))
    }

    @PluginMethod
    fun read(call: PluginCall) {
        val uri = uri(call)
        if (uri == null) {
            call.reject("uri is required")
            return
        }
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri).use { it?.readBytes() } ?: ByteArray(0)
            call.resolve(JSObject().put("content", Base64.encodeToString(bytes, Base64.NO_WRAP)))
        }.onFailure { call.reject("read failed: ${it.message}") }
    }

    @PluginMethod
    fun write(call: PluginCall) {
        val uri = uri(call)
        val content = call.getString("content")
        if (uri == null || content == null) {
            call.reject("uri and content are required")
            return
        }
        runCatching {
            val bytes = Base64.decode(content, Base64.DEFAULT)
            context.contentResolver.openOutputStream(uri, "w").use { it?.write(bytes) }
            call.resolve()
        }.onFailure { call.reject("write failed: ${it.message}") }
    }

    @PluginMethod
    fun create(call: PluginCall) {
        val dirUri = uri(call)
        val name = call.getString("name")
        val mime = call.getString("mime") ?: "application/octet-stream"
        if (dirUri == null || name == null) {
            call.reject("dirUri and name are required")
            return
        }
        runCatching {
            val doc = DocumentsContract.createDocument(context.contentResolver, dirUri, mime, name)
                ?: throw RuntimeException("SAF refused to create $name")
            call.resolve(entry(doc))
        }.onFailure { call.reject("create failed: ${it.message}") }
    }

    @PluginMethod
    fun remove(call: PluginCall) {
        val uri = uri(call)
        if (uri == null) {
            call.reject("uri is required")
            return
        }
        runCatching {
            if (!DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                throw RuntimeException("SAF refused to delete $uri")
            }
            call.resolve()
        }.onFailure { call.reject("remove failed: ${it.message}") }
    }

    @PluginMethod
    fun resolve(call: PluginCall) {
        val uri = uri(call)
        if (uri == null) {
            call.reject("uri is required")
            return
        }
        runCatching { call.resolve(entry(uri)) }.onFailure { call.reject("resolve failed: ${it.message}") }
    }

    // ---------------------------------------------------------------- helpers

    private fun uri(call: PluginCall): Uri? = call.getString("uri")?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun queryChildren(uri: Uri, consume: (Cursor) -> Unit) {
        context.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { consume(it) }
    }

    private fun documentId(cursor: Cursor): String =
        cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))

    private fun entry(uri: Uri, cursor: Cursor? = null): JSObject {
        val resolver = context.contentResolver
        val directory = if (cursor != null) {
            cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE).let { col ->
                col >= 0 && cursor.getString(col) == DocumentsContract.Document.MIME_TYPE_DIR
            }
        } else {
            runCatching { resolver.getType(uri) == DocumentsContract.Document.MIME_TYPE_DIR }.getOrDefault(false)
        }
        return JSObject().apply {
            put("uri", uri.toString())
            put("name", cursor?.let { displayName(it) } ?: displayNameFromResolver(resolver, uri))
            put("directory", directory)
            put("mime", runCatching { resolver.getType(uri) }.getOrNull())
            put("size", cursor?.let { longCol(it, DocumentsContract.Document.COLUMN_SIZE) } ?: -1L)
            put("modified", cursor?.let { longCol(it, DocumentsContract.Document.COLUMN_LAST_MODIFIED) } ?: 0L)
        }
    }

    private fun displayName(cursor: Cursor): String =
        cursor.getString(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) ?: ""

    private fun displayNameFromResolver(resolver: android.content.ContentResolver, uri: Uri): String =
        runCatching { DocumentsContract.getDocumentId(uri).substringAfterLast('/') }.getOrDefault("")

    private fun longCol(cursor: Cursor, column: String): Long =
        cursor.getColumnIndex(column).let { if (it >= 0 && !cursor.isNull(it)) cursor.getLong(it) else 0L }
}