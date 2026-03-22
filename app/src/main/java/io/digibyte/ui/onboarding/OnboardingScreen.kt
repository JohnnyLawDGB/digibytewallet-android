package io.digibyte.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
fun OnboardingScreen(
    navController: NavController,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    var selectedWordCount by remember { mutableIntStateOf(12) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A1628), DigiByteNavy, Color(0xFF0A1628))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo / brand header
            DigiByteLogoHeader()

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Secure your DGB",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your keys, your coins. No third parties.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB0BEC5),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Word count toggle — only relevant for create flow
            Text(
                text = "Seed phrase length",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFB0BEC5),
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(8.dp))
            WordCountToggle(
                selected = selectedWordCount,
                onSelect = { selectedWordCount = it }
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Create wallet button
            Button(
                onClick = {
                    viewModel.setWordCount(selectedWordCount)
                    navController.navigate("seed_display/$selectedWordCount")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DigiByteBlue)
            ) {
                Text(
                    text = "Create New Wallet",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Recover wallet button
            OutlinedButton(
                onClick = { navController.navigate("mnemonic_input") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DigiByteAccent),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DigiByteAccent)
            ) {
                Text(
                    text = "Recover Existing Wallet",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = "DigiByte — Secure, Fast, Decentralized",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF546E7A),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun WordCountToggle(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF243352), RoundedCornerShape(10.dp)),
        horizontalArrangement = Arrangement.Center
    ) {
        listOf(12, 24).forEach { count ->
            val isSelected = selected == count
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (isSelected) DigiByteBlue else Color.Transparent
                    )
                    .clip(RoundedCornerShape(10.dp))
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = { onSelect(count) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "$count words",
                        color = if (isSelected) Color.White else Color(0xFFB0BEC5),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
fun DigiByteLogoHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Geometric diamond/hexagon representing DigiByte logo
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(DigiByteBlue, DigiByteNavy)
                    ),
                    shape = RoundedCornerShape(20.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "DGB",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp,
                letterSpacing = 1.sp
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "DigiByte Wallet",
            style = MaterialTheme.typography.titleLarge,
            color = DigiByteAccent,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}
