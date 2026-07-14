package fr.dayview.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import fr.dayview.app.sync.SyncConfig
import fr.dayview.app.sync.SyncPairingCode
import fr.dayview.app.sync.SyncSetupResult
import fr.dayview.app.sync.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
class SyncSettingsScreenTest {
    private val configured = SyncConfig(
        baseUrl = "https://sync.example.com",
        userId = "me@example.com",
        token = "secret-token",
    )

    @Test
    fun newSetupStartsWithServerThenGuidesToAccount() = runComposeUiTest {
        var config by mutableStateOf<SyncConfig?>(null)
        setContent {
            DayViewTheme {
                SyncSettingsScreen(
                    config = config,
                    status = SyncStatus.NotConfigured,
                    hasKey = false,
                    onConfigChange = { config = it },
                    onGenerateKey = { "" },
                    onPasteKey = { false },
                    onSyncNow = {},
                    onClear = {},
                )
            }
        }

        onNodeWithTag(DayViewTestTags.SyncSetupServerStep).assertExists()
        onNodeWithTag(DayViewTestTags.SyncSettingsUrl).performTextInput("https://sync.example.com")
        assertEquals("https://sync.example.com", config?.baseUrl)
        onNodeWithTag(DayViewTestTags.SyncSetupContinue).performClick()
        onNodeWithTag(DayViewTestTags.SyncSetupAccountStep).assertExists()
    }

    @Test
    fun accountStepCollectsUserAndMaskedToken() = runComposeUiTest {
        var config by mutableStateOf(SyncConfig("https://sync.example.com", "", ""))
        setContent {
            DayViewTheme {
                SyncSettingsScreen(
                    config = config,
                    status = SyncStatus.NotConfigured,
                    hasKey = false,
                    onConfigChange = { config = it },
                    onGenerateKey = { "" },
                    onPasteKey = { false },
                    onSyncNow = {},
                    onClear = {},
                )
            }
        }

        onNodeWithTag(DayViewTestTags.SyncSettingsUser).performTextInput("alice")
        onNodeWithTag(DayViewTestTags.SyncSettingsToken).performTextInput("token")
        assertEquals("alice", config.userId)
        assertEquals("token", config.token)
        onNodeWithTag(DayViewTestTags.SyncSetupContinue).performClick()
        onNodeWithTag(DayViewTestTags.SyncSetupKeyStep).assertExists()
    }

    @Test
    fun keyStepGeneratesAndDisplaysRecoveryPhrase() = runComposeUiTest {
        var hasKey by mutableStateOf(false)
        setContent {
            DayViewTheme {
                SyncSettingsScreen(
                    config = configured,
                    status = SyncStatus.NotConfigured,
                    hasKey = hasKey,
                    onConfigChange = {},
                    onGenerateKey = {
                        hasKey = true
                        "abandon ability able"
                    },
                    onPasteKey = { false },
                    onSyncNow = {},
                    onClear = {},
                )
            }
        }

        onNodeWithTag(DayViewTestTags.SyncSetupKeyStep).assertExists()
        onNodeWithTag(DayViewTestTags.SyncSettingsGenerateKey).performClick()
        assertTrue(hasKey)
        onNodeWithTag(DayViewTestTags.SyncSettingsGeneratedPhrase).assertExists()
    }

    @Test
    fun successfulTestEndsOnSynchronizedConfirmation() = runComposeUiTest {
        setContent {
            DayViewTheme {
                SyncSettingsScreen(
                    config = configured,
                    status = SyncStatus.Idle,
                    hasKey = true,
                    onConfigChange = {},
                    onGenerateKey = { "" },
                    onPasteKey = { false },
                    onSyncNow = {},
                    onTestSetup = { SyncSetupResult.Success },
                    onClear = {},
                )
            }
        }

        onNodeWithTag(DayViewTestTags.SyncSetupTestStep).assertExists()
        onNodeWithTag(DayViewTestTags.SyncSetupTest).performClick()
        waitForIdle()
        onNodeWithTag(DayViewTestTags.SyncSetupCompleteStep).assertExists()
    }

    @Test
    fun completeStepSyncsAndCreatesQrCode() = runComposeUiTest {
        var syncNowCalled = false
        setContent {
            DayViewTheme {
                SyncSettingsScreen(
                    config = configured,
                    status = SyncStatus.Ok,
                    hasKey = true,
                    onConfigChange = {},
                    onGenerateKey = { "" },
                    onPasteKey = { false },
                    onSyncNow = { syncNowCalled = true },
                    onCreatePairing = {
                        SyncPairingCode(
                            content = "dayview-sync:{test}",
                            expiresAtEpochSeconds = Clock.System.now().toEpochMilliseconds() / 1_000L + 120L,
                        )
                    },
                    onClear = {},
                )
            }
        }

        onNodeWithTag(DayViewTestTags.SyncSettingsSyncNow).performClick()
        assertTrue(syncNowCalled)
        onNodeWithTag(DayViewTestTags.SyncSetupCreatePairing).performClick()
        waitForIdle()
        onNodeWithTag(DayViewTestTags.SyncSetupQrCode).assertExists()
    }

    @Test
    fun eraseStillRequiresConfirmation() = runComposeUiTest {
        var cleared = false
        setContent {
            DayViewTheme {
                SyncSettingsScreen(
                    config = configured,
                    status = SyncStatus.Ok,
                    hasKey = true,
                    onConfigChange = {},
                    onGenerateKey = { "" },
                    onPasteKey = { false },
                    onSyncNow = {},
                    onClear = { cleared = true },
                )
            }
        }

        onNodeWithTag(DayViewTestTags.SyncSettingsClear).performClick()
        assertFalse(cleared)
    }
}
