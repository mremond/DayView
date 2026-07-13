package fr.dayview.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import fr.dayview.app.sync.SyncConfig
import fr.dayview.app.sync.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SyncSettingsScreenTest {
    private val seededConfig = SyncConfig(baseUrl = "https://sync.example.com", userId = "me@example.com", token = "secret-token")

    @Composable
    private fun setSyncSettingsScreen(
        config: SyncConfig? = seededConfig,
        status: SyncStatus = SyncStatus.Idle,
        hasKey: Boolean = false,
        onConfigChange: (SyncConfig) -> Unit = {},
        onGenerateKey: () -> String = { "" },
        onPasteKey: (String) -> Boolean = { true },
        onSyncNow: () -> Unit = {},
        onClear: () -> Unit = {},
    ) {
        DayViewTheme {
            SyncSettingsScreen(
                config = config,
                status = status,
                hasKey = hasKey,
                onConfigChange = onConfigChange,
                onGenerateKey = onGenerateKey,
                onPasteKey = onPasteKey,
                onSyncNow = onSyncNow,
                onClear = onClear,
            )
        }
    }

    @Test
    fun clickingSyncNowInvokesCallback() = runComposeUiTest {
        var syncNowCalled = false
        setContent { setSyncSettingsScreen(onSyncNow = { syncNowCalled = true }) }

        onNodeWithTag(DayViewTestTags.SyncSettingsSyncNow).performClick()
        assertTrue(syncNowCalled)
    }

    @Test
    fun typingInUrlFieldInvokesConfigChange() = runComposeUiTest {
        var recorded: SyncConfig? = null
        setContent { setSyncSettingsScreen(config = null, onConfigChange = { recorded = it }) }

        onNodeWithTag(DayViewTestTags.SyncSettingsUrl).performTextInput("https://new.example.com")
        assertEquals("https://new.example.com", recorded?.baseUrl)
    }

    @Test
    fun typingInUserFieldInvokesConfigChange() = runComposeUiTest {
        var recorded: SyncConfig? = null
        setContent { setSyncSettingsScreen(config = null, onConfigChange = { recorded = it }) }

        onNodeWithTag(DayViewTestTags.SyncSettingsUser).performTextInput("me@example.com")
        assertEquals("me@example.com", recorded?.userId)
    }

    @Test
    fun typingInTokenFieldInvokesConfigChange() = runComposeUiTest {
        var recorded: SyncConfig? = null
        setContent { setSyncSettingsScreen(config = null, onConfigChange = { recorded = it }) }

        onNodeWithTag(DayViewTestTags.SyncSettingsToken).performTextInput("a-token")
        assertEquals("a-token", recorded?.token)
    }

    @Test
    fun generatingAKeyDisplaysItForCopying() = runComposeUiTest {
        var generateCalled = false
        setContent {
            setSyncSettingsScreen(
                onGenerateKey = {
                    generateCalled = true
                    "abandon abandon ability"
                },
            )
        }

        onNodeWithTag(DayViewTestTags.SyncSettingsGeneratedPhrase).assertDoesNotExist()
        onNodeWithTag(DayViewTestTags.SyncSettingsGenerateKey).performClick()
        assertTrue(generateCalled)
        onNodeWithTag(DayViewTestTags.SyncSettingsGeneratedPhrase).assertExists()
    }

    @Test
    fun validPhraseInvokesPasteCallback() = runComposeUiTest {
        var pasted: String? = null
        setContent {
            SyncSettingsScreen(
                config = null,
                status = SyncStatus.Idle,
                hasKey = false,
                onConfigChange = {},
                onGenerateKey = { "abandon" },
                onPasteKey = {
                    pasted = it
                    true
                },
                onSyncNow = {},
                onClear = {},
            )
        }
        onNodeWithTag(DayViewTestTags.SyncSettingsPhraseInput).performTextInput("some phrase")
        assertEquals(null, pasted)
        onNodeWithTag(DayViewTestTags.SyncSettingsPhraseError).assertDoesNotExist()

        onNodeWithTag(DayViewTestTags.SyncSettingsUsePhrase).performClick()
        assertEquals("some phrase", pasted)
    }

    @Test
    fun invalidPhraseShowsErrorTag() = runComposeUiTest {
        setContent {
            SyncSettingsScreen(
                config = null,
                status = SyncStatus.Idle,
                hasKey = false,
                onConfigChange = {},
                onGenerateKey = { "" },
                onPasteKey = { false },
                onSyncNow = {},
                onClear = {},
            )
        }
        onNodeWithTag(DayViewTestTags.SyncSettingsPhraseInput).performTextInput("bad")
        onNodeWithTag(DayViewTestTags.SyncSettingsUsePhrase).performClick()
        onNodeWithTag(DayViewTestTags.SyncSettingsPhraseError).assertExists()
    }

    @Test
    fun clickingClearInvokesCallback() = runComposeUiTest {
        var cleared = false
        setContent { setSyncSettingsScreen(onClear = { cleared = true }) }

        onNodeWithTag(DayViewTestTags.SyncSettingsClear).performClick()
        assertTrue(cleared)
    }

    @Test
    fun statusLineIsAlwaysPresent() = runComposeUiTest {
        setContent { setSyncSettingsScreen(status = SyncStatus.Failed) }

        onNodeWithTag(DayViewTestTags.SyncSettingsStatus).assertExists()
    }

    @Test
    fun nullConfigRendersEmptyFieldsAndStillReportsChanges() = runComposeUiTest {
        var recorded: SyncConfig? = null
        setContent { setSyncSettingsScreen(config = null, onConfigChange = { recorded = it }) }

        onNodeWithTag(DayViewTestTags.SyncSettingsUrl).performTextInput("h")
        assertEquals("h", recorded?.baseUrl)
        assertEquals("", recorded?.userId)
        assertEquals("", recorded?.token)
    }
}
