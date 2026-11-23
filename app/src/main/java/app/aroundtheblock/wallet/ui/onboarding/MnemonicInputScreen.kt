package app.aroundtheblock.wallet.ui.onboarding

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import app.aroundtheblock.wallet.R
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import app.aroundtheblock.wallet.core.bridge.NativeBridge
import app.aroundtheblock.wallet.ui.components.SecureWindow
import app.aroundtheblock.wallet.ui.theme.DigiByteAccent
import app.aroundtheblock.wallet.ui.theme.DigiByteBlue
import app.aroundtheblock.wallet.ui.theme.DigiByteRed
import kotlinx.coroutines.launch
import app.aroundtheblock.wallet.ui.components.keyboardPadding
import app.aroundtheblock.wallet.ui.components.keyboardBottomPx

@Composable
fun MnemonicInputScreen(
    navController: NavController,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    SecureWindow()

    var wordCount by remember { mutableIntStateOf(12) }

    // Build a stable MutableStateList of strings for the current wordCount
    val wordStates: MutableList<String> = remember(wordCount) {
        mutableStateListOf<String>().also { list ->
            repeat(wordCount) { list.add("") }
        }
    }

    var validationError by remember { mutableStateOf<String?>(null) }
    var passphraseExpanded by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var focusedField by remember { mutableIntStateOf(-1) }
    val context = LocalContext.current
    // ADD mode: the wallet this phrase already opens, when it is in the app; and one check at a time.
    var duplicate by remember { mutableStateOf<app.aroundtheblock.wallet.core.wallets.WalletEntry?>(null) }
    var checkingDuplicate by remember { mutableStateOf(false) }
    val addScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1628))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .keyboardPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.recover_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.recover_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB0BEC5)
            )
            Spacer(Modifier.height(14.dp))
            CompromisedSourceWarning()
            Spacer(Modifier.height(20.dp))

            // Word count selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(12, 24).forEach { count ->
                    val selected = wordCount == count
                    FilterChip(
                        selected = selected,
                        onClick = { wordCount = count },
                        label = { Text(stringResource(R.string.onb_words_count, count)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = DigiByteBlue,
                            selectedLabelColor = Color.White,
                            containerColor = Color(0xFF1A2742),
                            labelColor = Color(0xFFB0BEC5)
                        )
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            // Word input fields in pairs (2 per row)
            val rowCount = wordCount / 2
            for (row in 0 until rowCount) {
                val leftIdx = row * 2
                val rightIdx = leftIdx + 1

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WordInputField(
                        index = leftIdx,
                        value = if (leftIdx < wordStates.size) wordStates[leftIdx] else "",
                        onValueChange = { newVal ->
                            if (leftIdx < wordStates.size) wordStates[leftIdx] = newVal
                            validationError = null
                        },
                        onFocused = { focusedField = leftIdx },
                        onSuggestionSelected = { suggestion ->
                            if (leftIdx < wordStates.size) wordStates[leftIdx] = suggestion
                            focusedField = -1
                        },
                        isFocused = focusedField == leftIdx,
                        modifier = Modifier.weight(1f),
                        imeAction = ImeAction.Next
                    )

                    if (rightIdx < wordCount) {
                        WordInputField(
                            index = rightIdx,
                            value = if (rightIdx < wordStates.size) wordStates[rightIdx] else "",
                            onValueChange = { newVal ->
                                if (rightIdx < wordStates.size) wordStates[rightIdx] = newVal
                                validationError = null
                            },
                            onFocused = { focusedField = rightIdx },
                            onSuggestionSelected = { suggestion ->
                                if (rightIdx < wordStates.size) wordStates[rightIdx] = suggestion
                                focusedField = -1
                            },
                            isFocused = focusedField == rightIdx,
                            modifier = Modifier.weight(1f),
                            imeAction = if (rightIdx == wordCount - 1) ImeAction.Done else ImeAction.Next
                        )
                    }
                }
            }

            RestorePassphraseField(
                expanded = passphraseExpanded,
                onExpandedChange = { passphraseExpanded = it },
                passphrase = passphrase,
                onPassphraseChange = { passphrase = it },
                modifier = Modifier.padding(top = 8.dp),
            )

            // Validation error
            validationError?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error,
                    color = DigiByteRed,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            duplicate?.let { existing ->
                val shown = addFlowWalletName(existing)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.wallet_add_duplicate, shown),
                    color = Color(0xFFFFCC66),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = {
                        // The phrase of the wallet already running answers ALREADY_ACTIVE: nothing to
                        // restart into, so the add flow is left instead of the button doing nothing.
                        if (viewModel.switchTo(existing.id) != app.aroundtheblock.wallet.WalletSwitcher.Result.RESTARTING) {
                            if (!navController.popBackStack(ONBOARDING_ROUTE, inclusive = true)) navController.navigateUp()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.wallet_add_duplicate_switch, shown))
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    duplicate = null
                    val phrase = wordStates.toList().joinToString(" ").trim()
                    val phraseWords = phrase.split(" ")

                    // Validate all fields filled
                    if (phraseWords.any { it.isBlank() }) {
                        validationError = context.getString(R.string.restore_err_fill_all, wordCount)
                        return@Button
                    }

                    // Validate all words are in BIP39 list
                    val invalidWords = phraseWords.filter { !Bip39WordList.isValidWord(it) }
                    if (invalidWords.isNotEmpty()) {
                        validationError = context.getString(
                            R.string.restore_err_invalid_words,
                            invalidWords.take(3).joinToString(", "),
                        )
                        return@Button
                    }

                    // Normalize to canonical form (lowercase, single-spaced) and verify
                    // the BIP39 checksum. All-valid-words but bad-checksum means a
                    // typo'd or made-up phrase — accepting it silently builds no wallet
                    // and leaves sync stuck at "Connecting" forever. Reject it here.
                    val normalizedPhrase = phraseWords.joinToString(" ") { it.trim().lowercase() }
                    if (!NativeBridge.isValidMnemonic(normalizedPhrase)) {
                        validationError = context.getString(R.string.restore_err_checksum)
                        return@Button
                    }

                    // Only an open, non-empty field counts: collapsing it means "no passphrase",
                    // whatever was typed before.
                    val enteredPassphrase = passphrase.takeIf { passphraseExpanded && it.isNotEmpty() }
                    if (enteredPassphrase != null &&
                        !app.aroundtheblock.wallet.core.Bip39Passphrase.isValid(enteredPassphrase)
                    ) {
                        return@Button
                    }

                    viewModel.setRecoveryMnemonic(normalizedPhrase)
                    viewModel.setPassphrase(enteredPassphrase)
                    if (viewModel.mode == OnboardingMode.FIRST_RUN) {
                        // Next: the restore-privately screen, where the user may opt in to the
                        // other-formats scan, then the wallet-birth date.
                        navController.navigate("recovery_scan")
                    } else {
                        // ADD mode: a phrase the app already holds opens that wallet instead. Then
                        // straight to the birth date: the recovery scan's disclosure does not say a
                        // lookup links this wallet to the others, so it is not offered here (F9).
                        if (checkingDuplicate) return@Button
                        checkingDuplicate = true
                        addScope.launch {
                            val existing = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                viewModel.checkDuplicate()
                            }
                            checkingDuplicate = false
                            if (existing != null) {
                                duplicate = existing
                            } else {
                                navController.navigate("recovery_date")
                            }
                        }
                    }
                },
                enabled = !checkingDuplicate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
            ) {
                Text(
                    text = stringResource(R.string.recover_continue),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Space kept between the focused word (with its suggestions) and the top of the keyboard. */
private val KEYBOARD_GAP = 16.dp

/**
 * RESTORE-WARN-SIGMA (owner 2026-11-23): shown on every phrase entry, onboarding and "add a wallet"
 * alike, before any word is typed. The wallet cannot tell which app a phrase came from, so the
 * warning is unconditional. It names the safe path: a new wallet, then "Recover funds from another
 * wallet", which moves the coins off the old phrase instead of adopting it.
 */
@Composable
private fun CompromisedSourceWarning() {
    Surface(
        color = DigiByteRed.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DigiByteRed.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.recover_sigma_warning_title),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.recover_sigma_warning_body,
                    stringResource(R.string.rf_title)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE0E0E0)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WordInputField(
    index: Int,
    value: String,
    onValueChange: (String) -> Unit,
    onFocused: () -> Unit,
    onSuggestionSelected: (String) -> Unit,
    isFocused: Boolean,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next
) {
    val suggestions = remember(value, isFocused) {
        if (isFocused && value.length >= 2) Bip39WordList.suggest(value, limit = 4)
        else emptyList()
    }

    // Brings the focused field AND its suggestions into view above the soft keyboard.
    // Doing it on focus alone fires BEFORE the keyboard finishes animating
    // up, so the requested position is wrong by the time IME is fully
    // visible — we re-trigger every time the IME bottom inset changes
    // (initial show, autocomplete state changes, configuration changes).
    //
    // The requester sits on the Column that holds the field and the suggestion list, not on
    // the text field, and the request is repeated when the suggestions change. The suggestions
    // appear only after typing, below the field, with no focus or inset change to re-trigger a
    // request on the field alone. Measured on a Pixel 6 profile (API 36, Gboard, 1080x2400):
    // from word 9 of 24 on, the focused field was brought flush to the viewport bottom at
    // [..,1197][..,1365] and the suggestions began at y=1355, so all four sat under the
    // keyboard (top 1428) and could not be tapped.
    //
    // The requested rect extends [KEYBOARD_GAP] below the column, so the field and its
    // suggestions stop short of the keyboard instead of touching its top edge.
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val imeBottomPx = keyboardBottomPx()
    val gapPx = with(LocalDensity.current) { KEYBOARD_GAP.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val bringFieldIntoView: suspend () -> Unit = {
        if (size == IntSize.Zero) bringIntoView.bringIntoView()
        else bringIntoView.bringIntoView(
            Rect(0f, 0f, size.width.toFloat(), size.height.toFloat() + gapPx)
        )
    }

    LaunchedEffect(isFocused, imeBottomPx, suggestions, size) {
        if (isFocused && imeBottomPx > 0) {
            bringFieldIntoView()
        }
    }

    Column(
        modifier = modifier
            .onSizeChanged { size = it }
            .bringIntoViewRequester(bringIntoView)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { input ->
                onValueChange(input.lowercase().filter { c -> c.isLetter() })
            },
            label = { Text("${index + 1}", fontSize = 11.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { password() }
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        onFocused()
                        // Initial fire — covers the case where the keyboard
                        // is already up (focus moves between fields).
                        scope.launch { bringFieldIntoView() }
                    }
                },
            singleLine = true,
            // Password is the IME hint that stops the keyboard learning the word, syncing it
            // to a personal dictionary, or offering it back as a suggestion elsewhere. There is
            // deliberately no visualTransformation: the user must see what they typed, and the
            // in-app BIP39 suggestions below key off `value`, not the IME.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = imeAction,
                autoCorrect = false
            ),
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = DigiByteAccent,
                unfocusedBorderColor = Color(0xFF243352),
                focusedLabelColor = DigiByteAccent,
                unfocusedLabelColor = Color(0xFF546E7A),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = DigiByteAccent,
                focusedContainerColor = Color(0xFF1A2742),
                unfocusedContainerColor = Color(0xFF1A2742)
            ),
            isError = value.isNotBlank() && !isFocused && !Bip39WordList.isValidWord(value)
        )

        // Autocomplete suggestions
        if (suggestions.isNotEmpty() && isFocused) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF243352), RoundedCornerShape(0.dp, 0.dp, 6.dp, 6.dp))
            ) {
                suggestions.forEach { suggestion ->
                    Text(
                        text = suggestion,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSuggestionSelected(suggestion) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        color = DigiByteAccent,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    HorizontalDivider(color = Color(0xFF1A2742), thickness = 0.5.dp)
                }
            }
        }
    }
}
