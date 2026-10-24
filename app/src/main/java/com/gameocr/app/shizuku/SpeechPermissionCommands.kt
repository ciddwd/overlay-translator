package com.gameocr.app.shizuku

/**
 * Shell commands that pre-authorize playback capture for this app. `PROJECT_MEDIA` set to allow
 * makes AOSP SystemUI return the projection result without its dialog
 * (MediaProjectionManagerService.hasProjectionPermission, Android 10 through current main);
 * vendor builds may still show it, so the normal consent flow always runs afterwards.
 */
internal object SpeechPermissionCommands {
    private val safeName = Regex("[A-Za-z0-9_.]+")

    fun grantRecordAudio(packageName: String, userId: Int): Array<String> {
        require(packageName.matches(safeName) && userId >= 0)
        return arrayOf("pm", "grant", "--user", userId.toString(), packageName, "android.permission.RECORD_AUDIO")
    }

    fun allowProjectMedia(packageName: String, userId: Int): Array<String> {
        require(packageName.matches(safeName) && userId >= 0)
        return arrayOf("appops", "set", "--user", userId.toString(), packageName, "PROJECT_MEDIA", "allow")
    }

    /** Root runs commands through one shell; arguments are validated above, so joining is safe. */
    fun asShellLine(command: Array<String>): String = command.joinToString(" ")
}
