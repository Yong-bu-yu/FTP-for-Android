package com.example.myapplication1.ftp

import android.content.Context
import android.net.Uri
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.FtpFile
import org.apache.ftpserver.ftplet.User

class SafFileSystemView(
    private val context: Context,
    private val rootUri: Uri,
    private val user: User?
) : FileSystemView {

    private var workingDir: FtpFile = SafFtpFile(context, rootUri, "/", user)

    override fun getHomeDirectory(): FtpFile = SafFtpFile(context, rootUri, "/", user)

    override fun getWorkingDirectory(): FtpFile = workingDir

    override fun changeWorkingDirectory(dir: String): Boolean {
        val newPath = when {
            dir.startsWith('/') -> dir // 绝对路径
            workingDir.absolutePath == "/" -> "/$dir" // 基于根目录的相对路径
            else -> "${workingDir.absolutePath}/$dir" // 基于当前目录的相对路径
        }

        val newFile = getFile(newPath)
        return if (newFile.doesExist() && newFile.isDirectory) {
            workingDir = newFile
            true
        } else {
            false
        }
    }

    override fun getFile(file: String): FtpFile {
        val newPath = when {
            file.startsWith('/') -> file
            workingDir.absolutePath == "/" -> "/$file"
            else -> "${workingDir.absolutePath}/$file"
        }
        // 规范化路径，处理 ".." 等情况
        val finalPath = java.io.File(newPath).canonicalPath.replace('\\', '/')
        return SafFtpFile(context, rootUri, finalPath, user)
    }

    override fun isRandomAccessible(): Boolean = false // SAF I/O 是流式的，不是随机访问

    override fun dispose() {
        // No resources to dispose
    }
}