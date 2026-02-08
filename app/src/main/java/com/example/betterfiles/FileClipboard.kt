package com.example.betterfiles

import java.io.File

// 앱 어디서든 접근 가능한 파일 클립보드 (싱글톤)
object FileClipboard {
    // 복사/이동할 파일 목록
    var files: List<File> = emptyList()

    // true면 이동(잘라내기), false면 복사
    var isMove: Boolean = false

    // 클립보드가 비어있는지 확인
    fun hasClip(): Boolean = files.isNotEmpty()

    // 클립보드 초기화
    fun clear() {
        files = emptyList()
        isMove = false
    }
}