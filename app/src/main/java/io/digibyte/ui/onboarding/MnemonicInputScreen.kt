package io.digibyte.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteRed

@Composable
fun MnemonicInputScreen(
    navController: NavController,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    var wordCount by remember { mutableIntStateOf(12) }

    // Build a stable MutableStateList of strings for the current wordCount
    val wordStates: MutableList<String> = remember(wordCount) {
        mutableStateListOf<String>().also { list ->
            repeat(wordCount) { list.add("") }
        }
    }

    var validationError by remember { mutableStateOf<String?>(null) }
    var focusedField by remember { mutableIntStateOf(-1) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1628))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Text(
                text = "Recover Wallet",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Enter your seed phrase to restore your wallet.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB0BEC5)
            )
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
                        label = { Text("$count words") },
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

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val phrase = wordStates.toList().joinToString(" ").trim()
                    val phraseWords = phrase.split(" ")

                    // Validate all fields filled
                    if (phraseWords.any { it.isBlank() }) {
                        validationError = "Please enter all $wordCount words."
                        return@Button
                    }

                    // Validate all words are in BIP39 list
                    val invalidWords = phraseWords.filter { !Bip39WordList.isValidWord(it) }
                    if (invalidWords.isNotEmpty()) {
                        validationError = "Invalid word(s): ${invalidWords.take(3).joinToString(", ")}"
                        return@Button
                    }

                    viewModel.setRecoveryMnemonic(phrase)
                    // Universal Restore: scan every known derivation path
                    // BEFORE the date picker. Surfaces funds on non-native
                    // paths (BIP44, BIP49, legacy m/0H w/ either HMAC) so we
                    // know to sweep them during recovery.
                    navController.navigate("recovery_scan")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
            ) {
                Text(
                    text = "Continue",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

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

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { input ->
                onValueChange(input.lowercase().filter { c -> c.isLetter() })
            },
            label = { Text("${index + 1}", fontSize = 11.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state -> if (state.isFocused) onFocused() },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
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
