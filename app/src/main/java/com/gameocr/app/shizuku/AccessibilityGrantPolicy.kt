package com.gameocr.app.shizuku

/** Keep every other service untouched; recognize both flattened spellings of our component. */
internal fun withAccessibilityService(existing: String?, packageName: String, className: String): String {
    require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
    require(className.matches(Regex("[A-Za-z0-9_.$]+")))
    val services = existing?.trim()?.takeUnless { it == "null" }.orEmpty()
        .split(':').filter(String::isNotBlank).toMutableList()
    val full = "$packageName/$className"
    val short = if (className.startsWith("$packageName."))
        "$packageName/${className.removePrefix(packageName)}" else full
    if (full !in services && short !in services) services += full
    return services.joinToString(":")
}

internal fun accessibilitySettingsCommand(userId: Int, value: String? = null): Array<String> {
    require(userId >= 0)
    val prefix = arrayOf("settings", "--user", userId.toString())
    return if (value == null) prefix + arrayOf("get", "secure", "enabled_accessibility_services")
    else prefix + arrayOf("put", "secure", "enabled_accessibility_services", value)
}
