package com.example.myapplication1.ftp

import android.content.Context
import android.net.Uri
import org.apache.ftpserver.ftplet.FileSystemFactory
import org.apache.ftpserver.ftplet.FileSystemView
import org.apache.ftpserver.ftplet.User

class SafFileSystemFactory(
    private val context: Context,
    private val rootUri: Uri
) : FileSystemFactory {

    override fun createFileSystemView(user: User): FileSystemView {
        // 每个用户/会话都获取一个独立的 FileSystemView 实例
        return SafFileSystemView(context.applicationContext, rootUri, user)
    }
}