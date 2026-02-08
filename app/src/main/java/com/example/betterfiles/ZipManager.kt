package com.example.betterfiles

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipManager {

    // 압축하기 (목록 -> zip 파일)
    fun zip(files: List<File>, zipFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { out ->
            for (file in files) {
                // 상대 경로 계산을 위해 부모 경로 저장
                val parentPath = file.parentFile?.absolutePath ?: ""
                zipFileRecursive(file, parentPath, out)
            }
        }
    }

    // 재귀적으로 폴더/파일 압축
    private fun zipFileRecursive(file: File, parentPath: String, out: ZipOutputStream) {
        // Zip 내부에서의 경로 (절대 경로 떼어내기)
        val relativePath = file.absolutePath.removePrefix(parentPath).removePrefix("/")

        if (file.isDirectory) {
            // 폴더인 경우 엔트리 생성 (끝에 / 붙임)
            val entryName = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
            out.putNextEntry(ZipEntry(entryName))
            out.closeEntry()

            // 내부 파일들 재귀 호출
            file.listFiles()?.forEach { child ->
                zipFileRecursive(child, parentPath, out)
            }
        } else {
            // 파일인 경우 데이터 쓰기
            FileInputStream(file).use { fi ->
                BufferedInputStream(fi).use { origin ->
                    val entry = ZipEntry(relativePath)
                    out.putNextEntry(entry)

                    val data = ByteArray(4096)
                    var count: Int
                    while (origin.read(data, 0, 4096).also { count = it } != -1) {
                        out.write(data, 0, count)
                    }
                    out.closeEntry()
                }
            }
        }
    }

    // 압축 풀기 (zip 파일 -> 대상 폴더)
    fun unzip(zipFile: File, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val currentEntry = entry!!
                val destFile = File(targetDir, currentEntry.name)

                if (currentEntry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { fos ->
                        val buffer = ByteArray(4096)
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
            }
        }
    }
}