package com.gameocr.app.trigger

import com.gameocr.app.shizuku.SpeechPermissionCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConsentDialogPolicyTest {
    @Test fun onlyThisRequestsDialogsAreConfirmed_tableDriven() {
        data class Case(
            val name: String,
            val packageName: String?,
            val text: String,
            val now: Long,
            val clicked: Set<ConsentDialogKind>,
            val expected: ConsentDialogKind?,
        )
        val label = "Overlay Translator"
        listOf(
            Case("projection dialog", "com.android.systemui", "Start recording or casting with Overlay Translator?", 0, emptySet(), ConsentDialogKind.PROJECTION),
            Case("record permission", "com.google.android.permissioncontroller", "Allow Overlay Translator to record audio?", 0, emptySet(), ConsentDialogKind.RECORD_AUDIO),
            Case("aosp permission package", "com.android.permissioncontroller", "Allow Overlay Translator to record audio?", 0, emptySet(), ConsentDialogKind.RECORD_AUDIO),
            Case("another app's permission", "com.google.android.permissioncontroller", "Allow Maps to access location?", 0, emptySet(), null),
            Case("another app's projection", "com.android.systemui", "Start recording or casting with Recorder?", 0, emptySet(), null),
            Case("unrelated package naming us", "com.example.chat", "Overlay Translator", 0, emptySet(), null),
            Case("expired request", "com.android.systemui", "Start recording with Overlay Translator?", 1_001, emptySet(), null),
            Case("already confirmed once", "com.android.systemui", "Start recording with Overlay Translator?", 0, setOf(ConsentDialogKind.PROJECTION), null),
            Case("no package", null, "Overlay Translator", 0, emptySet(), null),
        ).forEach { case ->
            assertEquals(case.name, case.expected, ConsentDialogPolicy.kindFor(
                case.packageName, case.text, label, armedUntilMs = 1_000, nowMs = case.now, alreadyClicked = case.clicked,
            ))
        }
        assertEquals(null, ConsentDialogPolicy.kindFor("com.android.systemui", "anything", "", 1_000, 0, emptySet()))
    }

    @Test fun positiveButtonsAreTheVerifiedViewIds() {
        assertEquals(listOf("android:id/button1"), ConsentDialogPolicy.buttonIds(ConsentDialogKind.PROJECTION, "com.android.systemui"))
        assertEquals(
            listOf(
                "com.google.android.permissioncontroller:id/permission_allow_foreground_only_button",
                "com.google.android.permissioncontroller:id/permission_allow_button",
            ),
            ConsentDialogPolicy.buttonIds(ConsentDialogKind.RECORD_AUDIO, "com.google.android.permissioncontroller"),
        )
    }

    @Test fun backendCommandsAreFixedAndValidated() {
        assertEquals(
            listOf("pm", "grant", "--user", "0", "com.gameocr.app", "android.permission.RECORD_AUDIO"),
            SpeechPermissionCommands.grantRecordAudio("com.gameocr.app", 0).toList(),
        )
        assertEquals(
            listOf("appops", "set", "--user", "10", "com.gameocr.app", "PROJECT_MEDIA", "allow"),
            SpeechPermissionCommands.allowProjectMedia("com.gameocr.app", 10).toList(),
        )
        assertEquals("appops set --user 0 a.b PROJECT_MEDIA allow",
            SpeechPermissionCommands.asShellLine(SpeechPermissionCommands.allowProjectMedia("a.b", 0)))
        listOf("a.b; rm -rf /", "a b", "").forEach { bad ->
            assertThrows(bad, IllegalArgumentException::class.java) { SpeechPermissionCommands.grantRecordAudio(bad, 0) }
        }
        assertThrows(IllegalArgumentException::class.java) { SpeechPermissionCommands.allowProjectMedia("a.b", -1) }
    }
}
