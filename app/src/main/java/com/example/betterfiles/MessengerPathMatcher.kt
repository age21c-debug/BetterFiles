package com.example.betterfiles

import java.io.File
import java.util.Locale

data class MessengerSource(
    val appName: String,
    val ownerPackages: List<String>,
    val pathPatterns: List<String>
)

object MessengerPathMatcher {
    private const val MIN_FILE_BYTES = 10L * 1024L

    val sources: List<MessengerSource> = listOf(
        MessengerSource(
            appName = "WhatsApp",
            ownerPackages = listOf("com.whatsapp"),
            pathPatterns = listOf(
                "Android/media/com.whatsapp/WhatsApp Images",
                "Android/media/com.whatsapp/WhatsApp Documents",
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
                "WhatsApp Images",
                "WhatsApp Documents",
                "Android/media/com.whatsapp",
                "Android/data/com.whatsapp/files"
            )
        ),
        MessengerSource(
            appName = "Telegram",
            ownerPackages = listOf("org.telegram.messenger", "org.telegram.messenger.web"),
            pathPatterns = listOf(
                "Telegram Images",
                "Telegram Documents",
                "Telegram Video",
                "Telegram Audio",
                "Telegram Files",
                "Android/media/org.telegram.messenger/Telegram",
                "Android/media/org.telegram.messenger",
                "Android/media/org.telegram.messenger.web",
                "Android/data/org.telegram.messenger/files",
                "Android/data/org.telegram.messenger.web/files"
            )
        ),
        MessengerSource(
            appName = "KakaoTalk",
            ownerPackages = listOf("com.kakao.talk"),
            pathPatterns = listOf(
                "KakaoTalk/Download",
                "Download/KakaoTalk",
                "Pictures/KakaoTalk",
                "Documents/KakaoTalk",
                "Movies/KakaoTalk",
                "Android/media/com.kakao.talk/KakaoTalk",
                "Android/media/com.kakao.talk",
                "Android/data/com.kakao.talk/files"
            )
        ),
        MessengerSource(
            appName = "Messenger",
            ownerPackages = listOf("com.facebook.orca"),
            pathPatterns = listOf(
                "Android/media/com.facebook.orca",
                "Android/data/com.facebook.orca/files"
            )
        ),
        MessengerSource(
            appName = "LINE",
            ownerPackages = listOf("jp.naver.line.android"),
            pathPatterns = listOf(
                "Android/media/jp.naver.line.android",
                "Android/data/jp.naver.line.android/files",
                "Download/LINE",
                "Pictures/LINE",
                "Movies/LINE"
            )
        ),
        MessengerSource(
            appName = "Discord",
            ownerPackages = listOf("com.discord"),
            pathPatterns = listOf(
                "Android/media/com.discord",
                "Android/data/com.discord/files",
                "Download/Discord",
                "Pictures/Discord",
                "Movies/Discord"
            )
        ),
        MessengerSource(
            appName = "Snapchat",
            ownerPackages = listOf("com.snapchat.android"),
            pathPatterns = listOf(
                "Android/media/com.snapchat.android",
                "Android/data/com.snapchat.android/files",
                "Download/Snapchat",
                "Pictures/Snapchat",
                "Movies/Snapchat"
            )
        ),
        MessengerSource(
            appName = "Viber",
            ownerPackages = listOf("com.viber.voip"),
            pathPatterns = listOf(
                "Android/media/com.viber.voip",
                "Android/data/com.viber.voip/files",
                "Viber",
                "Viber/Media",
                "Download/Viber"
            )
        ),
        MessengerSource(
            appName = "Signal",
            ownerPackages = listOf("org.thoughtcrime.securesms"),
            pathPatterns = listOf(
                "Android/media/org.thoughtcrime.securesms",
                "Android/data/org.thoughtcrime.securesms/files",
                "Signal",
                "Signal/Backups",
                "Download/Signal"
            )
        ),
        MessengerSource(
            appName = "Facebook",
            ownerPackages = listOf("com.facebook.katana"),
            pathPatterns = listOf(
                "Android/media/com.facebook.katana",
                "Android/data/com.facebook.katana/files",
                "Facebook",
                "Pictures/Facebook",
                "Movies/Facebook"
            )
        ),
        MessengerSource(
            appName = "TikTok",
            ownerPackages = listOf("com.zhiliaoapp.musically"),
            pathPatterns = listOf(
                "Android/media/com.zhiliaoapp.musically",
                "Android/data/com.zhiliaoapp.musically/files",
                "TikTok",
                "Download/TikTok",
                "Movies/TikTok"
            )
        ),
        MessengerSource(
            appName = "Threads",
            ownerPackages = listOf("com.instagram.barcelona"),
            pathPatterns = listOf(
                "Android/media/com.instagram.barcelona",
                "Android/data/com.instagram.barcelona/files",
                "Threads",
                "Download/Threads",
                "Pictures/Threads"
            )
        ),
        MessengerSource(
            appName = "X",
            ownerPackages = listOf("com.twitter.android"),
            pathPatterns = listOf(
                "Android/media/com.twitter.android",
                "Android/data/com.twitter.android/files",
                "Twitter",
                "Download/Twitter",
                "Pictures/Twitter"
            )
        ),
        MessengerSource(
            appName = "Zalo",
            ownerPackages = listOf("com.zing.zalo"),
            pathPatterns = listOf(
                "Android/media/com.zing.zalo",
                "Android/data/com.zing.zalo/files",
                "Zalo",
                "Download/Zalo",
                "Pictures/Zalo"
            )
        ),
        MessengerSource(
            appName = "Slack",
            ownerPackages = listOf("com.Slack", "com.slack"),
            pathPatterns = listOf(
                "Android/media/com.Slack",
                "Android/media/com.slack",
                "Android/data/com.Slack/files",
                "Android/data/com.slack/files",
                "Slack",
                "Download/Slack"
            )
        )
    )

    fun isMessengerPath(path: String): Boolean {
        val normalized = normalize(path)
        if (normalized.isBlank()) return false

        // Exclude transient folders.
        val lowered = normalized.lowercase(Locale.ROOT)
        if (lowered.contains("/cache/") || lowered.contains("/temp/") || lowered.contains("/thumbnails/")) {
            return false
        }

        val relative = normalized.removePrefix("/storage/emulated/0/").removePrefix("storage/emulated/0/")
        val segments = relative.split('/').filter { it.isNotBlank() }
        val loweredSegments = segments.map { it.lowercase(Locale.ROOT) }

        return sources.any { source ->
            source.pathPatterns.any { pattern ->
                val patternSegments = normalize(pattern).split('/').filter { it.isNotBlank() }
                if (patternSegments.isEmpty()) {
                    false
                } else {
                    // 1) Fast-path: normalized substring match for common real-world path variants.
                    val patternNormalized = normalize(pattern).lowercase(Locale.ROOT)
                    if (relative.lowercase(Locale.ROOT).contains(patternNormalized)) {
                        true
                    } else {
                        // 2) Fallback: ordered segment subsequence match (non-contiguous).
                        val loweredPattern = patternSegments.map { it.lowercase(Locale.ROOT) }
                        containsOrderedSegments(loweredSegments, loweredPattern)
                    }
                }
            }
        }
    }

    fun detectSourceName(path: String): String {
        val normalized = normalize(path)
            .removePrefix("/storage/emulated/0/")
            .removePrefix("storage/emulated/0/")
            .lowercase(Locale.ROOT)

        for (source in sources) {
            for (pattern in source.pathPatterns) {
                val p = normalize(pattern).lowercase(Locale.ROOT)
                if (p.isNotBlank() && normalized.contains(p)) {
                    return source.appName
                }
            }
        }
        return "Other"
    }

    fun isValidSize(sizeBytes: Long): Boolean = sizeBytes >= MIN_FILE_BYTES

    private fun normalize(path: String): String {
        return path.replace('\\', '/').trim()
    }

    private fun containsOrderedSegments(target: List<String>, pattern: List<String>): Boolean {
        if (pattern.isEmpty()) return false
        var patternIndex = 0
        for (segment in target) {
            if (segment == pattern[patternIndex]) {
                patternIndex++
                if (patternIndex == pattern.size) return true
            }
        }
        return false
    }
}
