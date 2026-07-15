package fr.dayview.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.sync_settings_clear
import fr.dayview.app.generated.resources.sync_settings_erase_confirm_button
import fr.dayview.app.generated.resources.sync_settings_erase_confirm_message
import fr.dayview.app.generated.resources.sync_settings_erase_confirm_title
import fr.dayview.app.generated.resources.sync_settings_generate_key
import fr.dayview.app.generated.resources.sync_settings_key_present
import fr.dayview.app.generated.resources.sync_settings_phrase_accepted
import fr.dayview.app.generated.resources.sync_settings_regenerate_key
import fr.dayview.app.generated.resources.sync_settings_replace_key
import fr.dayview.app.generated.resources.sync_settings_sync_now
import fr.dayview.app.generated.resources.sync_settings_token_label
import fr.dayview.app.generated.resources.sync_settings_token_placeholder
import fr.dayview.app.generated.resources.sync_settings_url_label
import fr.dayview.app.generated.resources.sync_settings_url_placeholder
import fr.dayview.app.generated.resources.sync_settings_user_label
import fr.dayview.app.generated.resources.sync_settings_user_placeholder
import fr.dayview.app.generated.resources.sync_setup_account_description
import fr.dayview.app.generated.resources.sync_setup_account_title
import fr.dayview.app.generated.resources.sync_setup_add_device
import fr.dayview.app.generated.resources.sync_setup_complete_description
import fr.dayview.app.generated.resources.sync_setup_complete_title
import fr.dayview.app.generated.resources.sync_setup_continue
import fr.dayview.app.generated.resources.sync_setup_importing
import fr.dayview.app.generated.resources.sync_setup_key_description
import fr.dayview.app.generated.resources.sync_setup_key_title
import fr.dayview.app.generated.resources.sync_setup_pairing_creating
import fr.dayview.app.generated.resources.sync_setup_pairing_description
import fr.dayview.app.generated.resources.sync_setup_pairing_failed
import fr.dayview.app.generated.resources.sync_setup_pairing_qr_description
import fr.dayview.app.generated.resources.sync_setup_pairing_title
import fr.dayview.app.generated.resources.sync_setup_previous
import fr.dayview.app.generated.resources.sync_setup_progress
import fr.dayview.app.generated.resources.sync_setup_reconfigure
import fr.dayview.app.generated.resources.sync_setup_scan_button
import fr.dayview.app.generated.resources.sync_setup_scan_description
import fr.dayview.app.generated.resources.sync_setup_scan_expired
import fr.dayview.app.generated.resources.sync_setup_scan_failed
import fr.dayview.app.generated.resources.sync_setup_scan_invalid
import fr.dayview.app.generated.resources.sync_setup_scan_title
import fr.dayview.app.generated.resources.sync_setup_scan_unavailable
import fr.dayview.app.generated.resources.sync_setup_server_description
import fr.dayview.app.generated.resources.sync_setup_server_title
import fr.dayview.app.generated.resources.sync_setup_test_auth_error
import fr.dayview.app.generated.resources.sync_setup_test_button
import fr.dayview.app.generated.resources.sync_setup_test_connection_error
import fr.dayview.app.generated.resources.sync_setup_test_description
import fr.dayview.app.generated.resources.sync_setup_test_key_error
import fr.dayview.app.generated.resources.sync_setup_test_title
import fr.dayview.app.sync.SyncConfig
import fr.dayview.app.sync.SyncPairingCode
import fr.dayview.app.sync.SyncPairingImportResult
import fr.dayview.app.sync.SyncSetupResult
import fr.dayview.app.sync.SyncStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

private enum class SyncWizardStep(val number: Int) {
    Server(1),
    Account(2),
    Key(3),
    Test(4),
    Complete(5),
}

@Composable
internal fun SyncSettingsScreen(
    config: SyncConfig?,
    status: SyncStatus,
    hasKey: Boolean,
    onConfigChange: (SyncConfig) -> Unit,
    onGenerateKey: () -> String,
    onPasteKey: (String) -> Boolean,
    onSyncNow: () -> Unit,
    onTestSetup: suspend () -> SyncSetupResult = { SyncSetupResult.NotConfigured },
    onCreatePairing: suspend () -> SyncPairingCode? = { null },
    onImportPairing: suspend (String) -> SyncPairingImportResult = { SyncPairingImportResult.InvalidCode },
    onClear: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    val scope = rememberCoroutineScope()
    val effectiveConfig = config ?: SyncConfig(baseUrl = "", userId = "", token = "")
    var step by remember { mutableStateOf(initialSyncWizardStep(config, hasKey, status)) }
    var generatedPhrase by remember { mutableStateOf<String?>(null) }
    var showKeyEntry by remember { mutableStateOf(!hasKey) }
    var phraseError by remember { mutableStateOf(false) }
    var phraseAccepted by remember { mutableStateOf(false) }
    var phraseDraft by remember { mutableStateOf("") }
    var testInProgress by remember { mutableStateOf(false) }
    var testError by remember { mutableStateOf<StringResource?>(null) }
    var importInProgress by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<StringResource?>(null) }
    var pairingInProgress by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf<SyncPairingCode?>(null) }
    var pairingFailed by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }

    fun importCode(raw: String) {
        importInProgress = true
        importError = null
        scope.launch {
            when (onImportPairing(raw)) {
                SyncPairingImportResult.Success -> step = SyncWizardStep.Complete
                SyncPairingImportResult.InvalidCode -> importError = Res.string.sync_setup_scan_invalid
                SyncPairingImportResult.Expired -> importError = Res.string.sync_setup_scan_expired
                SyncPairingImportResult.ConnectionFailed -> importError = Res.string.sync_setup_scan_failed
            }
            importInProgress = false
        }
    }

    val scanQr = rememberSyncPairingScanner(
        onResult = ::importCode,
        onFailure = { error ->
            importError = when (error) {
                SyncPairingScanError.Unavailable -> Res.string.sync_setup_scan_unavailable
                SyncPairingScanError.Failed -> Res.string.sync_setup_scan_failed
            }
        },
    )

    LaunchedEffect(status, config, hasKey) {
        if (status == SyncStatus.Ok && config != null && hasKey && step == SyncWizardStep.Test) {
            step = SyncWizardStep.Complete
        }
    }
    LaunchedEffect(pairingCode) {
        val code = pairingCode ?: return@LaunchedEffect
        val remainingMillis = code.expiresAtEpochSeconds * 1_000L - Clock.System.now().toEpochMilliseconds()
        if (remainingMillis > 0) delay(remainingMillis)
        if (pairingCode == code) pairingCode = null
    }

    Column(modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SyncSettingsScreen)) {
        SyncWizardProgress(step)
        Spacer(Modifier.height(14.dp))

        when (step) {
            SyncWizardStep.Server -> {
                SettingsPanelCard(
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.testTag(DayViewTestTags.SyncSetupServerStep),
                ) {
                    SyncWizardHeader(
                        title = stringResource(Res.string.sync_setup_server_title),
                        description = stringResource(Res.string.sync_setup_server_description),
                    )
                    Spacer(Modifier.height(14.dp))
                    SyncFieldLabel(stringResource(Res.string.sync_settings_url_label))
                    GoalTextField(
                        value = effectiveConfig.baseUrl,
                        semanticLabel = stringResource(Res.string.sync_settings_url_label),
                        placeholder = stringResource(Res.string.sync_settings_url_placeholder),
                        onValueChange = { onConfigChange(effectiveConfig.copy(baseUrl = it)) },
                        keyboardType = KeyboardType.Uri,
                        modifier = Modifier.testTag(DayViewTestTags.SyncSettingsUrl),
                    )
                    Spacer(Modifier.height(14.dp))
                    SettingsAccentButton(
                        text = stringResource(Res.string.sync_setup_continue),
                        onClick = { step = SyncWizardStep.Account },
                        enabled = effectiveConfig.baseUrl.isSyncServerUrl(),
                        modifier = Modifier.testTag(DayViewTestTags.SyncSetupContinue),
                    )
                }

                if (scanQr != null) {
                    Spacer(Modifier.height(14.dp))
                    SettingsPanelCard(contentPadding = PaddingValues(16.dp)) {
                        SyncWizardHeader(
                            title = stringResource(Res.string.sync_setup_scan_title),
                            description = stringResource(Res.string.sync_setup_scan_description),
                        )
                        Spacer(Modifier.height(12.dp))
                        SettingsAccentButton(
                            text = stringResource(Res.string.sync_setup_scan_button),
                            onClick = scanQr,
                            enabled = !importInProgress,
                            modifier = Modifier.testTag(DayViewTestTags.SyncSetupScan),
                        )
                        if (importInProgress) SyncWizardMessage(stringResource(Res.string.sync_setup_importing), colors.amber)
                        importError?.let { SyncWizardMessage(stringResource(it), colors.red) }
                    }
                }
            }

            SyncWizardStep.Account -> {
                SettingsPanelCard(
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.testTag(DayViewTestTags.SyncSetupAccountStep),
                ) {
                    SyncWizardHeader(
                        title = stringResource(Res.string.sync_setup_account_title),
                        description = stringResource(Res.string.sync_setup_account_description),
                    )
                    Spacer(Modifier.height(14.dp))
                    SyncFieldLabel(stringResource(Res.string.sync_settings_user_label))
                    GoalTextField(
                        value = effectiveConfig.userId,
                        semanticLabel = stringResource(Res.string.sync_settings_user_label),
                        placeholder = stringResource(Res.string.sync_settings_user_placeholder),
                        onValueChange = { onConfigChange(effectiveConfig.copy(userId = it)) },
                        imeAction = ImeAction.Next,
                        modifier = Modifier.testTag(DayViewTestTags.SyncSettingsUser),
                    )
                    Spacer(Modifier.height(12.dp))
                    SyncFieldLabel(stringResource(Res.string.sync_settings_token_label))
                    GoalTextField(
                        value = effectiveConfig.token,
                        semanticLabel = stringResource(Res.string.sync_settings_token_label),
                        placeholder = stringResource(Res.string.sync_settings_token_placeholder),
                        onValueChange = { onConfigChange(effectiveConfig.copy(token = it)) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.testTag(DayViewTestTags.SyncSettingsToken),
                    )
                    Spacer(Modifier.height(14.dp))
                    SyncWizardNavigation(
                        onPrevious = { step = SyncWizardStep.Server },
                        onContinue = { step = SyncWizardStep.Key },
                        canContinue = effectiveConfig.userId.isNotBlank() && effectiveConfig.token.isNotBlank(),
                    )
                }
            }

            SyncWizardStep.Key -> {
                SettingsPanelCard(
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.testTag(DayViewTestTags.SyncSetupKeyStep),
                ) {
                    SyncWizardHeader(
                        title = stringResource(Res.string.sync_setup_key_title),
                        description = stringResource(Res.string.sync_setup_key_description),
                    )
                    Spacer(Modifier.height(14.dp))
                    if (hasKey && !showKeyEntry) {
                        Text(stringResource(Res.string.sync_settings_key_present), color = colors.mint, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        SettingsAccentButton(
                            text = stringResource(Res.string.sync_settings_replace_key),
                            onClick = { showKeyEntry = true },
                        )
                    } else {
                        SettingsAccentButton(
                            text = stringResource(
                                if (hasKey) Res.string.sync_settings_regenerate_key else Res.string.sync_settings_generate_key,
                            ),
                            onClick = {
                                generatedPhrase = onGenerateKey()
                                phraseAccepted = false
                            },
                            modifier = Modifier.testTag(DayViewTestTags.SyncSettingsGenerateKey),
                        )
                        GeneratedPhraseBlock(generatedPhrase)
                        Spacer(Modifier.height(14.dp))
                        SettingsDivider()
                        Spacer(Modifier.height(14.dp))
                        PhraseEntry(
                            draft = phraseDraft,
                            isError = phraseError,
                            isAccepted = phraseAccepted,
                            onDraftChange = {
                                phraseDraft = it
                                phraseError = false
                                phraseAccepted = false
                            },
                            onUse = {
                                val accepted = onPasteKey(phraseDraft)
                                phraseError = !accepted
                                phraseAccepted = accepted
                                if (accepted) phraseDraft = ""
                            },
                        )
                    }
                    if (phraseAccepted) {
                        SyncWizardMessage(stringResource(Res.string.sync_settings_phrase_accepted), colors.mint)
                    }
                    Spacer(Modifier.height(14.dp))
                    SyncWizardNavigation(
                        onPrevious = { step = SyncWizardStep.Account },
                        onContinue = { step = SyncWizardStep.Test },
                        canContinue = hasKey,
                    )
                }
            }

            SyncWizardStep.Test -> {
                SettingsPanelCard(
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.testTag(DayViewTestTags.SyncSetupTestStep),
                ) {
                    SyncWizardHeader(
                        title = stringResource(Res.string.sync_setup_test_title),
                        description = stringResource(Res.string.sync_setup_test_description),
                    )
                    Spacer(Modifier.height(14.dp))
                    SyncSetupSummary(effectiveConfig)
                    Spacer(Modifier.height(14.dp))
                    SyncWizardNavigation(
                        previousLabel = stringResource(Res.string.sync_setup_previous),
                        continueLabel = stringResource(Res.string.sync_setup_test_button),
                        onPrevious = { step = SyncWizardStep.Key },
                        onContinue = {
                            testInProgress = true
                            testError = null
                            scope.launch {
                                when (onTestSetup()) {
                                    SyncSetupResult.Success -> step = SyncWizardStep.Complete
                                    SyncSetupResult.AuthenticationFailed -> {
                                        testError = Res.string.sync_setup_test_auth_error
                                    }
                                    SyncSetupResult.KeyMismatch -> testError = Res.string.sync_setup_test_key_error
                                    SyncSetupResult.ConnectionFailed,
                                    SyncSetupResult.NotConfigured,
                                    -> testError = Res.string.sync_setup_test_connection_error
                                }
                                testInProgress = false
                            }
                        },
                        canContinue = !testInProgress,
                        continueTag = DayViewTestTags.SyncSetupTest,
                    )
                    if (testInProgress) SyncWizardMessage(stringResource(Res.string.sync_setup_importing), colors.amber)
                    testError?.let { SyncWizardMessage(stringResource(it), colors.red) }
                }
            }

            SyncWizardStep.Complete -> {
                SettingsPanelCard(
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.testTag(DayViewTestTags.SyncSetupCompleteStep),
                ) {
                    Text("✓", color = colors.mint, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    SyncWizardHeader(
                        title = stringResource(Res.string.sync_setup_complete_title),
                        description = stringResource(
                            Res.string.sync_setup_complete_description,
                            effectiveConfig.baseUrl,
                            effectiveConfig.userId,
                        ),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsAccentButton(
                            text = stringResource(Res.string.sync_settings_sync_now),
                            onClick = onSyncNow,
                            modifier = Modifier.testTag(DayViewTestTags.SyncSettingsSyncNow),
                        )
                        SettingsAccentButton(
                            text = stringResource(Res.string.sync_setup_add_device),
                            onClick = {
                                pairingInProgress = true
                                pairingFailed = false
                                pairingCode = null
                                scope.launch {
                                    pairingCode = onCreatePairing()
                                    pairingFailed = pairingCode == null
                                    pairingInProgress = false
                                }
                            },
                            enabled = !pairingInProgress,
                            modifier = Modifier.testTag(DayViewTestTags.SyncSetupCreatePairing),
                        )
                    }
                    if (pairingInProgress) {
                        SyncWizardMessage(stringResource(Res.string.sync_setup_pairing_creating), colors.amber)
                    }
                    if (pairingFailed) {
                        SyncWizardMessage(stringResource(Res.string.sync_setup_pairing_failed), colors.red)
                    }
                    pairingCode?.let { SyncPairingQrCard(it) }
                    Spacer(Modifier.height(14.dp))
                    SettingsDivider()
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsAccentButton(
                            text = stringResource(Res.string.sync_setup_reconfigure),
                            onClick = { step = SyncWizardStep.Server },
                        )
                        SettingsAccentButton(
                            text = stringResource(Res.string.sync_settings_clear),
                            onClick = { confirmErase = true },
                            modifier = Modifier.testTag(DayViewTestTags.SyncSettingsClear),
                        )
                    }
                }
            }
        }
    }

    if (confirmErase) {
        SyncConfirmDialog(
            title = stringResource(Res.string.sync_settings_erase_confirm_title),
            message = stringResource(Res.string.sync_settings_erase_confirm_message),
            confirmLabel = stringResource(Res.string.sync_settings_erase_confirm_button),
            onConfirm = {
                onClear()
                pairingCode = null
                generatedPhrase = null
                showKeyEntry = true
                step = SyncWizardStep.Server
                confirmErase = false
            },
            onDismiss = { confirmErase = false },
        )
    }
}

private fun initialSyncWizardStep(config: SyncConfig?, hasKey: Boolean, status: SyncStatus): SyncWizardStep = when {
    config == null || !config.baseUrl.isSyncServerUrl() -> SyncWizardStep.Server
    config.userId.isBlank() || config.token.isBlank() -> SyncWizardStep.Account
    !hasKey -> SyncWizardStep.Key
    status == SyncStatus.Ok -> SyncWizardStep.Complete
    else -> SyncWizardStep.Test
}

private fun String.isSyncServerUrl(): Boolean = startsWith("https://") || startsWith("http://")

@Composable
private fun SyncWizardProgress(step: SyncWizardStep) {
    val colors = LocalDayViewColors.current
    Text(
        stringResource(Res.string.sync_setup_progress, step.number),
        color = colors.muted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(5) { index ->
            val reached = index < step.number
            Spacer(
                Modifier.weight(1f).height(3.dp)
                    .background(
                        if (reached) colors.mint else colors.overlay.copy(alpha = .10f),
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

@Composable
private fun SyncWizardHeader(title: String, description: String) {
    val colors = LocalDayViewColors.current
    Text(title, color = colors.cloud, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(description, color = colors.muted, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
private fun SyncWizardNavigation(
    onPrevious: () -> Unit,
    onContinue: () -> Unit,
    canContinue: Boolean,
    previousLabel: String = stringResource(Res.string.sync_setup_previous),
    continueLabel: String = stringResource(Res.string.sync_setup_continue),
    continueTag: String = DayViewTestTags.SyncSetupContinue,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsAccentButton(
            text = previousLabel,
            onClick = onPrevious,
            modifier = Modifier.testTag(DayViewTestTags.SyncSetupPrevious),
        )
        SettingsAccentButton(
            text = continueLabel,
            onClick = onContinue,
            enabled = canContinue,
            modifier = Modifier.testTag(continueTag),
        )
    }
}

@Composable
private fun SyncSetupSummary(config: SyncConfig) {
    val colors = LocalDayViewColors.current
    Text(config.baseUrl, color = colors.cloud, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Text(config.userId, color = colors.muted, fontSize = 11.sp)
}

@Composable
private fun SyncWizardMessage(text: String, color: Color) {
    Spacer(Modifier.height(10.dp))
    Text(text, color = color, fontSize = 11.sp, lineHeight = 15.sp)
}

@Composable
private fun SyncPairingQrCard(code: SyncPairingCode) {
    val colors = LocalDayViewColors.current
    val matrix = remember(code.content) { createSyncQrCode(code.content) }
    val qrDescription = stringResource(Res.string.sync_setup_pairing_qr_description)
    Spacer(Modifier.height(16.dp))
    SettingsDivider()
    Spacer(Modifier.height(14.dp))
    Text(stringResource(Res.string.sync_setup_pairing_title), color = colors.cloud, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(stringResource(Res.string.sync_setup_pairing_description), color = colors.muted, fontSize = 11.sp, lineHeight = 15.sp)
    Spacer(Modifier.height(12.dp))
    Canvas(
        Modifier
            .size(228.dp)
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .semantics { contentDescription = qrDescription }
            .testTag(DayViewTestTags.SyncSetupQrCode),
    ) {
        drawRect(Color.White)
        val moduleWidth = size.width / matrix.width
        val moduleHeight = size.height / matrix.height
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * moduleWidth, y * moduleHeight),
                        size = Size(moduleWidth + .5f, moduleHeight + .5f),
                    )
                }
            }
        }
    }
}
