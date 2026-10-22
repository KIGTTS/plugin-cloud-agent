package com.kigtts.tools.agent

import kotlinx.serialization.json.*
import java.net.URI

/** Discover real page image URLs without fetching or inventing any image bytes. */
internal fun htmlImageLinks(page: String, html: String): JsonArray {
    val base = URI(page)
    val attributes = Regex("""([a-zA-Z_:][\w:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>]+))""")
    val candidates = linkedMapOf<String, String>()
    fun add(raw: String?, description: String) {
        if (raw.isNullOrBlank() || candidates.size >= 16) return
        val uri = runCatching { base.resolve(raw.trim().replace("&amp;", "&")) }.getOrNull() ?: return
        if (uri.scheme == "https" && uri.userInfo == null && !uri.host.isNullOrBlank() && uri.port in setOf(-1, 443))
            candidates.putIfAbsent(uri.toString(), description.take(200))
    }
    Regex("""<(img|meta)\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { tag ->
        val attrs = attributes.findAll(tag.value).associate { match ->
            match.groupValues[1].lowercase() to match.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty()
        }
        if (tag.groupValues[1].equals("meta", true)) {
            if ((attrs["property"] ?: attrs["name"]).orEmpty().lowercase() in setOf("og:image", "og:image:url", "twitter:image", "twitter:image:src"))
                add(attrs["content"], "页面分享图片")
        } else {
            val description = attrs["alt"] ?: attrs["title"].orEmpty()
            add(attrs["src"], description); add(attrs["data-src"], description)
            attrs["srcset"]?.split(',')?.firstOrNull()?.trim()?.substringBefore(' ')?.let { add(it, description) }
        }
    }
    return buildJsonArray {
        candidates.forEach { (url, description) -> add(buildJsonObject { put("url", url); put("description", description) }) }
    }
}
