package io.digibyte.ui.onboarding

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.digibyte.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteNavy

@Composable
fun SeedDisplayScreen(
    navController: NavController,
    wordCount: Int,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // ── FLAG_SECURE: block screenshots and screen recording ──────────────────
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Generate mnemonic once when this screen enters composition
    LaunchedEffect(Unit) {
        if (viewModel.getMnemonicWords().isEmpty()) {
            viewModel.setWordCount(wordCount)
            viewModel.generateMnemonic()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1628))
    ) {
        when (uiState) {
            is OnboardingUiState.Loading, is OnboardingUiState.Idle -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = DigiByteAccent
                )
            }

            is OnboardingUiState.Error -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = (uiState as OnboardingUiState.Error).message,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.generateMnemonic() }) {
                        Text(stringResource(R.string.seed_retry))
                    }
                }
            }

            else -> {
                val words = viewModel.getMnemonicWords()
                if (words.isEmpty()) return@Box

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // imePadding BEFORE verticalScroll, kept as the house pattern for any
                        // screen that may gain a text field. See PassphraseScreen for what goes
                        // wrong without it.
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Security notice
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A2742), RoundedCornerShape(10.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = DigiByteAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.seed_screenshot_blocked),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB0BEC5),
                            lineHeight = 18.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = stringResource(R.string.seed_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = stringResource(R.string.seed_word_count, words.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB0BEC5)
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    // Word grid — 3 columns
                    SeedWordGrid(words = words)

                    Spacer(modifier = Modifier.height(32.dp))

                    // Warning card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.seed_never_share),
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFFF8A65),
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            listOf(
                                stringResource(R.string.seed_tip_paper),
                                stringResource(R.string.seed_tip_safe),
                                stringResource(R.string.seed_tip_anyone)
                            ).forEach { tip ->
                                Text(
                                    text = "• $tip",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFB0BEC5),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // The optional BIP39 passphrase used to live here, directly beneath the
                    // words. It now has its own screen AFTER verification: a passphrase stored
                    // beside the seed protects nothing, and a page that showed both invited
                    // exactly the note-taking the warning tells the user to avoid. See
                    // PassphraseScreen and SeedAndPassphraseSeparationTest.

                    Button(
                        onClick = { navController.navigate("seed_verify") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
                    ) {
                        Text(
                            text = stringResource(R.string.seed_written_down),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SeedWordGrid(words: List<String>) {
    val columns = 3
    val rows = (words.size + columns - 1) / columns

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (row in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (col in 0 until columns) {
                    val index = row * columns + col
                    if (index < words.size) {
                        SeedWordCell(
                            number = index + 1,
                            word = words[index],
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SeedWordCell(number: Int, word: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xFF1A2742), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF243352), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Column {
            Text(
                text = "$number",
                fontSize = 10.sp,
                color = Color(0xFF546E7A),
                fontWeight = FontWeight.Medium
            )
            Text(
                text = word,
                fontSize = 14.sp,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}
