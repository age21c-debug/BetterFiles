package com.example.betterfiles

import android.content.Context
import java.io.File

object FavoritesManager {
    private const val PREF_NAME = "BetterFilesFavorites"
    private const val KEY_FAVORITES = "favorite_paths"

    // 즐겨찾기 목록 불러오기 (가나다순 정렬)
    fun getAll(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()

        // 실제 존재하는 폴더만 필터링하고 정렬하여 반환
        return set.filter { File(it).exists() }.sorted()
    }

    // 즐겨찾기 추가
    fun add(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val currentSet = prefs.getStringSet(KEY_FAVORITES, mutableSetOf()) ?: mutableSetOf()

        // SharedPreferences 변경 감지를 위해 새로운 Set 객체 생성
        val newSet = HashSet(currentSet)
        newSet.add(path)

        prefs.edit().putStringSet(KEY_FAVORITES, newSet).apply()
    }

    // 즐겨찾기 삭제
    fun remove(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val currentSet = prefs.getStringSet(KEY_FAVORITES, mutableSetOf()) ?: mutableSetOf()

        val newSet = HashSet(currentSet)
        newSet.remove(path)

        prefs.edit().putStringSet(KEY_FAVORITES, newSet).apply()
    }

    // 이미 즐겨찾기인지 확인 (토글 UI용)
    fun isFavorite(context: Context, path: String): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_FAVORITES, emptySet())
        return set?.contains(path) == true
    }
}