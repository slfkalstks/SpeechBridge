@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kc.ac.uc.speechbridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.*

class IosSessionStore(private val directoryName: String = "SpeechBridge") : SessionStore {
    init { require(directoryName.matches(Regex("[A-Za-z0-9_-]+"))) }
    private fun directory(): NSURL {
        val root = NSFileManager.defaultManager.URLForDirectory(
            NSApplicationSupportDirectory, NSUserDomainMask, null, true, null
        ) ?: error("저장 위치를 열 수 없습니다.")
        val dir = root.URLByAppendingPathComponent(directoryName, true) ?: error("저장 위치 오류")
        check(NSFileManager.defaultManager.createDirectoryAtURL(dir, true, null, null))
        check(dir.setResourceValue(true, NSURLIsExcludedFromBackupKey, null))
        return dir
    }
    private fun file() = directory().URLByAppendingPathComponent("conversations-v1.json")!!
    override suspend fun read(): String? = withContext(Dispatchers.Default) {
        val url = file()
        if (!NSFileManager.defaultManager.fileExistsAtPath(url.path!!)) null
        else NSString.stringWithContentsOfURL(url, NSUTF8StringEncoding, null)
            ?: error("저장된 기록을 읽지 못했습니다.")
    }
    override suspend fun write(text: String): Unit = withContext(Dispatchers.Default) {
        @Suppress("CAST_NEVER_SUCCEEDS")
        val content = text as NSString
        check(content.writeToURL(file(), true, NSUTF8StringEncoding, null)) { "기록 저장 실패" }
    }
}
