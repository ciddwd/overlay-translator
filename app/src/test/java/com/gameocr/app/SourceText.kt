package com.gameocr.app

import java.io.File

/**
 * Source text for wiring tests, with LF line endings. Windows checkouts with core.autocrlf=true
 * have CRLF on disk, which would break every marker that spans lines.
 */
fun File.readSourceText(): String = readText().replace("\r\n", "\n")
