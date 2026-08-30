package io.digibyte.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import io.digibyte.R
import io.digibyte.ui.components.SecureWindow
import io.digibyte.ui.theme.DigiByteAccent
import io.digibyte.ui.theme.DigiByteNavy

/**
 * The optional BIP39 passphrase, on a screen of its own.
 *
 * ## Why it is not on the seed screen any more
 *
 * A BIP39 passphrase has no cryptographic defence against being written down beside the seed. In
 * one envelope they are a single secret again and the passphrase contributes nothing at all. The
 * threat model is entirely behavioural, which makes this layout a security control rather than a
 * presentation choice.
 *
 * It used to sit directly below the word grid, with a warning printed underneath the very words
 * it was telling the user to keep it away from. People write down what is in front of them; the
 * page said "together" while the sentence said "apart", and the page wins that argument every
 * time. Both also landed inside one undifferentiated "set up my backup" step — and one step
 * produces one artifact.
 *
 * So this comes AFTER verification. The seed ritual opens and closes first: see the words, prove
 * they were written, put them away. Only then is a passphrase mentioned, as a separate act of
 * recording. Separation by construction instead of by instruction.
 *
 * [SeedAndPassphraseSeparationTest] keeps it that way — nothing else in the code would fail if a
 * future refactor folded the two back together "to save a step".
 *
 * ## Skipping is the expected answer
 *
 * Most people should not set one, and those who should already know they want it. Continuing with
 * both fields empty derives exactly what the wallet derived before this feature existed.
 */
@Composable
fun PassphraseScreen(
    navController: NavController,
    viewModel: OnboardingViewModel,
) {
    SecureWindow()

    // Never rememberSaveable: a passphrase must not be written into Android's saved-instance
    // state bundle, which is the same rule the recovery-phrase inputs follow.
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val ready = passphraseEntryReady(passphrase, confirm)
    val hasOne = passphrase.isNotEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DigiByteNavy)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // imePadding BEFORE verticalScroll, so the scrollable viewport shrinks to the
                // space the keyboard leaves. MainActivity sets adjustResize but also calls
                // enableEdgeToEdge(), and an edge-to-edge window does NOT resize for the IME —
                // Compose has to inset it. Ordered the other way round the confirm field sits
                // under the keyboard and cannot be reached: measured at [66,2176][1014,2220] on
                // a 2220px screen, clipped to 44px tall, when this section lived on the seed
                // screen.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.pass_screen_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.pass_screen_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB0BEC5),
                lineHeight = 20.sp,
            )

            Spacer(Modifier.height(24.dp))

            // Deliberately reused rather than reimplemented: the two-field confirmation and the
            // unflattering copy are the parts that matter, and a second copy of them would drift.
            PassphraseSection(
                passphrase = passphrase,
                confirm = confirm,
                onPassphraseChange = { passphrase = it },
                onConfirmChange = { confirm = it },
            )

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = {
                    // Committed straight to the shared ViewModel so the value never travels
                    // through a navigation argument or a saved-state bundle.
                    viewModel.setPassphrase(passphrase)
                    navController.navigate("pin_setup") {
                        popUpTo("seed_verify") { inclusive = true }
                    }
                },
                enabled = ready,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DigiByteAccent),
            ) {
                Text(
                    // The label states which of the two things is about to happen, so nobody
                    // sets a passphrase by accident or skips one by accident either.
                    text = stringResource(
                        if (hasOne) R.string.pass_screen_continue_with
                        else R.string.pass_screen_continue_without
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.pass_screen_separate_note),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8FA3B8),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
