package com.example.myapplication1.ftp

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream

class SafFtpFile(
    private val context: Context,
    private val rootUri: Uri, // 用户授权的根目录 Uri
    private val path: String, // 当前文件相对于根目录的路径
    private val user: User?
) : FtpFile {

    // 通过路径找到对应的 DocumentFile
    private val documentFile: DocumentFile? by lazy {
        findFile(path)
    }

    // ✨ 优化：懒加载父目录的DocumentFile
    private val parentDocumentFile: DocumentFile? by lazy {
        // 根目录没有父目录
        if (path == "/") return@lazy null
        val parentPath = path.substringBeforeLast('/', "/")
        findFile(if (parentPath.isEmpty()) "/" else parentPath)
    }

    private fun findFile(targetPath: String): DocumentFile? {
        // DocumentFile.fromTreeUri 必须在主线程之外调用，但这里是懒加载，通常没问题
        var currentFile = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        if (targetPath == "/" || targetPath.isEmpty()) {
            return currentFile
        }

        val segments = targetPath.trimStart('/').split('/').filter { it.isNotEmpty() }

        for (segment in segments) {
            currentFile = currentFile.findFile(segment) ?: return null
        }
        return currentFile
    }

    override fun getAbsolutePath(): String = path

    override fun getName(): String = documentFile?.name ?: path.substringAfterLast('/')

    override fun isHidden(): Boolean = documentFile?.name?.startsWith('.') ?: false

    override fun isDirectory(): Boolean = documentFile?.isDirectory ?: false

    override fun isFile(): Boolean = documentFile?.isFile ?: false

    override fun doesExist(): Boolean = documentFile?.exists() ?: false

    override fun isReadable(): Boolean = documentFile?.canRead() ?: parentDocumentFile?.canRead() ?: false

    // ✨ FIX: 核心修复点。如果文件不存在，检查父目录是否可写
    override fun isWritable(): Boolean {
        return if (doesExist()) {
            documentFile?.canWrite() ?: false
        } else {
            // 文件不存在，检查我们是否可以在其父目录中创建它
            parentDocumentFile?.canWrite() ?: false
        }
    }

    override fun isRemovable(): Boolean = isWritable

    override fun getOwnerName(): String? = user?.name

    override fun getGroupName(): String? = user?.name

    override fun getLinkCount(): Int = if (isDirectory) 3 else 1

    override fun getLastModified(): Long = documentFile?.lastModified() ?: 0L

    override fun setLastModified(time: Long): Boolean = false

    override fun getSize(): Long = documentFile?.length() ?: 0L

    override fun getPhysicalFile(): Any? = documentFile?.uri

    // ✨ FIX: 确保在检查权限后能成功创建
    override fun mkdir(): Boolean {
        if (doesExist() || !isWritable()) return false
        return parentDocumentFile?.createDirectory(getName()) != null
    }

    override fun delete(): Boolean = if (doesExist() && isWritable()) documentFile?.delete() ?: false else false

    override fun move(destination: FtpFile): Boolean {
        // 注意：SAF的move操作（renameTo）仅在同一父目录下有效。
        // 跨目录移动需要复制+删除，这里简化为重命名。
        if (!doesExist() || !destination.isWritable) return false
        //val destDocFile = (destination as? SafFtpFile)?.documentFile ?: return false
        return documentFile?.renameTo(destination.name!!) ?: false
    }

    override fun listFiles(): List<FtpFile>? {
        if (!isDirectory) return null

        return documentFile?.listFiles()?.map {
            val childPath = if (path == "/") "/${it.name}" else "$path/${it.name}"
            SafFtpFile(context, rootUri, childPath, user)
        }
    }

    // ✨ FIX & PERF: 修复创建逻辑并添加缓冲以提高性能
    override fun createOutputStream(offset: Long): OutputStream? {
        if (!isWritable()) return null

        try {
            val mode = if (offset > 0) "wa" else "w" // "wa" for append, "w" for truncate
            val targetUri = if (doesExist()) {
                documentFile!!.uri
            } else {
                // 文件不存在，先在父目录中创建它
                parentDocumentFile?.createFile("*/*", getName())?.uri ?: return null
            }

            val outputStream = context.contentResolver.openOutputStream(targetUri, mode) ?: return null

            // 重要：添加缓冲层
            return BufferedOutputStream(outputStream)

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // ✨ PERF: 添加缓冲以提高性能
    override fun createInputStream(offset: Long): InputStream? {
        if (!isReadable()) return null

        try {
            val inputStream = context.contentResolver.openInputStream(documentFile!!.uri) ?: return null
            if (offset > 0) {
                inputStream.skip(offset)
            }
            // 重要：添加缓冲层
            return BufferedInputStream(inputStream)
        } catch(e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}