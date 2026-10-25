package com.kigtts.tools.agent

import java.util.Locale
import java.util.TimeZone

/** Coarse device preferences only; never request location or look up the user's IP. */
internal fun agentResearchContext(locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String =
    "检索环境偏好：系统语言=${locale.toLanguageTag().takeUnless { it == "und" }.orEmpty().ifBlank { "未提供" }}；" +
        "地区偏好=${locale.country.ifBlank { "未提供" }}；时区=${zone.id}。" +
        "这些是设备偏好，不是真实定位或网络可达性证明；用户明确说明和当前会话语言优先。"
