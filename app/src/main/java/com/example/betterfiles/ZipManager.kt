package com.example.betterfiles

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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
        val canonicalTargetDir = targetDir.canonicalFile

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val currentEntry = entry!!
                val destFile = File(targetDir, currentEntry.name).canonicalFile

                // Prevent Zip Slip: every extracted path must stay under targetDir.
                val isInsideTarget =
                    destFile.path == canonicalTargetDir.path ||
                        destFile.path.startsWith(canonicalTargetDir.path + File.separator)
                if (!isInsideTarget) {
                    throw SecurityException("Invalid zip entry path: ${currentEntry.name}")
                }

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
                zis.closeEntry()
            }
        }
    }

    // [추가됨] Zip 파일 정보 미리보기용 데이터 클래스
    data class ZipPreviewInfo(
        val fileCount: Int,
        val totalSize: Long,
        val fileNames: List<String>
    )

    // [추가됨] Zip 파일 헤더만 읽어서 정보 반환 (압축 해제 안 함)
    fun getZipInfo(zipFile: File): ZipPreviewInfo {
        var count = 0
        var size = 0L
        val names = mutableListOf<String>()

        // ZipFile 클래스를 사용하면 스트림을 끝까지 읽지 않고도 엔트리 정보를 빠르게 가져올 수 있습니다.
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count++

                if (entry.size != -1L) {
                    size += entry.size // 압축 해제 시 크기 누적
                }

                // 미리보기용 파일명은 최대 10개까지만 저장 (메모리 절약)
                if (names.size < 10) {
                    names.add(entry.name)
                }
            }
        }

        return ZipPreviewInfo(count, size, names)
    }
}
