package io.digibyte.ui.onboarding

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.digibyte.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteBlue
import io.digibyte.ui.theme.DigiByteGreen
import io.digibyte.ui.theme.DigiByteRed

/**
 * SeedVerifyScreen — quizzes the user on 3 random word positions from their seed phrase.
 * Each question shows 4 options: 1 correct word + 3 random decoys from the BIP39 list.
 * The decoys are drawn from the full BIP39 list, NOT adjacent positions in the user's phrase.
 */
@Composable
fun SeedVerifyScreen(
    navController: NavController,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    // Keep FLAG_SECURE active — we're still showing seed-related data
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

    val words = viewModel.getMnemonicWords()

    // Build 3 quiz questions — stable, not recomposed on every render
    val questions = remember(words) { buildQuestions(words) }

    var currentQuestion by remember { mutableIntStateOf(0) }
    var selectedAnswer by remember { mutableStateOf<String?>(null) }
    var correctCount by remember { mutableIntStateOf(0) }
    var showResult by remember { mutableStateOf(false) }
    var allCorrect by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1628))
    ) {
        if (showResult) {
            VerifyResultScreen(
                allCorrect = allCorrect,
                onRetry = {
                    currentQuestion = 0
                    selectedAnswer = null
                    correctCount = 0
                    showResult = false
                },
                onContinue = {
                    navController.navigate("pin_setup") {
                        popUpTo("seed_display/{wordCount}") { inclusive = true }
                    }
                }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Progress indicator
                Text(
                    text = stringResource(R.string.verify_progress, currentQuestion + 1, questions.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFB0BEC5)
                )

                Spacer(Modifier.height(8.dp))

                LinearProgressIndicator(
                    progress = { (currentQuestion.toFloat() / questions.size) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = DigiByteAccent,
                    trackColor = Color(0xFF243352)
                )

                Spacer(Modifier.height(40.dp))

                AnimatedContent(
                    targetState = currentQuestion,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "question_transition"
                ) { qIndex ->
                    if (qIndex < questions.size) {
                        val question = questions[qIndex]
                        QuestionCard(
                            question = question,
                            selectedAnswer = selectedAnswer,
                            onAnswerSelected = { answer ->
                                if (selectedAnswer != null) return@QuestionCard
                                selectedAnswer = answer
                            }
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // Next / Finish button — only enabled after selection
                Button(
                    onClick = {
                        val question = questions[currentQuestion]
                        if (selectedAnswer == question.correctWord) correctCount++

                        if (currentQuestion < questions.size - 1) {
                            currentQuestion++
                            selectedAnswer = null
                        } else {
                            val finalCorrect = correctCount +
                                    (if (selectedAnswer == question.correctWord) 1 else 0)
                            // Already counted above before navigating
                            allCorrect = correctCount == questions.size
                            showResult = true
                        }
                    },
                    enabled = selectedAnswer != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
                ) {
                    Text(
                        text = if (currentQuestion < questions.size - 1) "Next" else "Finish",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: QuizQuestion,
    selectedAnswer: String?,
    onAnswerSelected: (String) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.verify_question, question.position),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        question.options.forEach { option ->
            val isSelected = selectedAnswer == option
            val isCorrect = selectedAnswer != null && option == question.correctWord
            val isWrong = selectedAnswer == option && option != question.correctWord

            val borderColor = when {
                isCorrect && selectedAnswer != null -> DigiByteGreen
                isWrong -> DigiByteRed
                isSelected -> DigiByteAccent
                else -> Color(0xFF243352)
            }
            val bgColor = when {
                isCorrect && selectedAnswer != null -> Color(0xFF1B3A2A)
                isWrong -> Color(0xFF3A1B1B)
                isSelected -> Color(0xFF1A2742)
                else -> Color(0xFF1A2742)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bgColor)
                    .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
                    .clickable(enabled = selectedAnswer == null) { onAnswerSelected(option) }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = option,
                    color = when {
                        isCorrect && selectedAnswer != null -> DigiByteGreen
                        isWrong -> DigiByteRed
                        else -> Color.White
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
private fun VerifyResultScreen(
    allCorrect: Boolean,
    onRetry: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (allCorrect) "Verified!" else "Let's try again",
            style = MaterialTheme.typography.headlineMedium,
            color = if (allCorrect) DigiByteGreen else DigiByteRed,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = if (allCorrect)
                "Your seed phrase is safely written down. Now let's set up a PIN."
            else
                "Some answers were incorrect. Please review your seed phrase and try again.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFB0BEC5),
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )

        Spacer(Modifier.height(48.dp))

        if (allCorrect) {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
            ) {
                Text("Set Up PIN", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
            ) {
                Text("Try Again", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── Data structures ────────────────────────────────────────────────────────────

data class QuizQuestion(
    val position: Int,       // 1-based word position shown to the user
    val correctWord: String,
    val options: List<String> // shuffled list of 4 choices
)

/**
 * Build 3 quiz questions from the user's mnemonic.
 * Positions are spread evenly across the phrase.
 * Decoys are drawn from the BIP39 word list, explicitly excluding all words
 * in the user's phrase to avoid adjacent-word leakage.
 */
private fun buildQuestions(words: List<String>): List<QuizQuestion> {
    if (words.isEmpty()) return emptyList()

    // SecureRandom rather than java.util.Random — neither leaks anything
    // material (the user is about to reveal these positions by tapping
    // the right answers anyway) but the timestamp-seeded Random would
    // let an on-device attacker reproduce the shuffle if they knew the
    // exact ms the screen rendered. Free defense-in-depth upgrade.
    val rng = java.security.SecureRandom()

    // Spread 3 positions across the word list
    val positions: List<Int> = when {
        words.size <= 12 -> {
            // 12-word: pick near positions 3, 7, 11
            listOf(3, 7, 11).filter { it <= words.size }
        }
        else -> {
            // 24-word: pick near positions 5, 13, 21
            listOf(5, 13, 21).filter { it <= words.size }
        }
    }.map { it - 1 } // convert to 0-based index

    // Decoy pool: BIP39 words NOT in the user's seed
    val phraseSet = words.toHashSet()
    val decoyPool = Bip39WordList.words.filter { it !in phraseSet }.shuffled(rng)

    return positions.mapIndexed { i, idx ->
        val correct = words[idx]
        // Take 3 distinct decoys from the pool (offset by i*3 to avoid repetition)
        val decoys = decoyPool
            .drop(i * 3)
            .take(3)
        val options = (decoys + correct).shuffled(rng)
        QuizQuestion(position = idx + 1, correctWord = correct, options = options)
    }
}
