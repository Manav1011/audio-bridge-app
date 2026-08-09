package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.CardBorder
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.PrimaryOrange
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceCardHeader
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val pcIpInput by viewModel.pcIpInput.collectAsState()
    val speakerPortInput by viewModel.speakerPortInput.collectAsState()
    val micPortInput by viewModel.micPortInput.collectAsState()
    val isMicEnabled by viewModel.isMicEnabled.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val lastSavedNotice by viewModel.lastSavedCaptureNotice.collectAsState()

    androidx.compose.runtime.LaunchedEffect(lastSavedNotice) {
        lastSavedNotice?.let { notice ->
            android.widget.Toast.makeText(context, notice, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val keyboardController = LocalSoftwareKeyboardController.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted || !isMicEnabled) {
            val outputDir = context.getExternalFilesDir(null) ?: context.cacheDir
            viewModel.toggleTransport(outputDir)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("main_screen"),
        containerColor = DarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App Header & Branding
            HeaderTitleBanner(isRunning = isRunning)

            // Main Connection Settings Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("control_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                border = BorderStroke(1.dp, CardBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Router,
                                contentDescription = "Connection",
                                tint = PrimaryOrange,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "CONNECTION SETTINGS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp,
                                    color = TextSecondary
                                )
                            )
                        }
                        StatusBadge(isRunning = isRunning)
                    }

                    // PC / Server IP Address Field
                    OutlinedTextField(
                        value = pcIpInput,
                        onValueChange = { viewModel.onPcIpChange(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pc_ip_input"),
                        label = { Text("PC / Server IP Address", color = TextMuted) },
                        placeholder = { Text("192.168.1.100", color = TextMuted) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Computer,
                                contentDescription = null,
                                tint = if (isRunning) TextMuted else PrimaryOrange,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        singleLine = true,
                        enabled = !isRunning,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DarkBackground,
                            unfocusedContainerColor = DarkBackground,
                            disabledContainerColor = DarkBackground.copy(alpha = 0.5f),
                            focusedBorderColor = PrimaryOrange,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            disabledTextColor = TextMuted
                        ),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    )

                    // Speaker & Microphone Ports Config Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = speakerPortInput,
                            onValueChange = { viewModel.onSpeakerPortChange(it) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("rx_port_input"),
                            label = { Text("Speaker TCP Port", color = TextMuted, fontSize = 11.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Speaker,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            singleLine = true,
                            enabled = !isRunning,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DarkBackground,
                                unfocusedContainerColor = DarkBackground,
                                disabledContainerColor = DarkBackground.copy(alpha = 0.5f),
                                focusedBorderColor = PrimaryOrange,
                                unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                disabledTextColor = TextMuted
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                        )

                        OutlinedTextField(
                            value = micPortInput,
                            onValueChange = { viewModel.onMicPortChange(it) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tx_port_input"),
                            label = { Text("Mic TCP Port", color = TextMuted, fontSize = 11.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            singleLine = true,
                            enabled = !isRunning,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = DarkBackground,
                                unfocusedContainerColor = DarkBackground,
                                disabledContainerColor = DarkBackground.copy(alpha = 0.5f),
                                focusedBorderColor = PrimaryOrange,
                                unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                disabledTextColor = TextMuted
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                        )
                    }

                    // Microphone Streaming Mode Switch Card
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkBackground)
                            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        if (isMicEnabled) PrimaryOrange.copy(alpha = 0.15f) else CardBorder.copy(alpha = 0.3f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                                    contentDescription = "Live Microphone Input",
                                    tint = if (isMicEnabled) PrimaryOrange else TextMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Phone Microphone Stream",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = if (isMicEnabled) "Streaming active to TCP port $micPortInput" else "Microphone muted (Receive only)",
                                    fontSize = 11.sp,
                                    color = if (isMicEnabled) StatusGreen else TextMuted
                                )
                            }
                        }

                        Switch(
                            checked = isMicEnabled,
                            onCheckedChange = { viewModel.onMicEnabledToggle(it) },
                            enabled = !isRunning,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PrimaryOrange,
                                checkedTrackColor = PrimaryOrange.copy(alpha = 0.3f),
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = DarkBackground,
                                uncheckedBorderColor = CardBorder
                            ),
                            modifier = Modifier.testTag("mic_enabled_switch")
                        )
                    }

                    // Big Action Button: Connect / Disconnect Audio Stream
                    Button(
                        onClick = {
                            keyboardController?.hide()
                            if (isRunning) {
                                viewModel.toggleTransport()
                            } else {
                                if (isMicEnabled) {
                                    val hasPermission = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED

                                    if (hasPermission) {
                                        val outputDir = context.getExternalFilesDir(null) ?: context.cacheDir
                                        viewModel.toggleTransport(outputDir)
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                } else {
                                    val outputDir = context.getExternalFilesDir(null) ?: context.cacheDir
                                    viewModel.toggleTransport(outputDir)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("start_stop_button"),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) StatusRed else PrimaryOrange,
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (isRunning) Icons.Default.PowerSettingsNew else Icons.Default.GraphicEq,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isRunning) "STOP AUDIO STREAM" else "START AUDIO STREAM",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            )
                        }
                    }
                }
            }

            // Production Audio Stream Status / Specs Card
            AnimatedVisibility(
                visible = isRunning,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ActiveStreamCard(
                    pcIp = pcIpInput,
                    speakerPort = speakerPortInput,
                    micPort = micPortInput,
                    isMicEnabled = isMicEnabled
                )
            }

            // Saved Speaker UDP Diagnostic Recording Card
            if (!isRunning && lastSavedNotice != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("saved_capture_notice_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, StatusGreen.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = StatusGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SPEAKER UDP CAPTURE SAVED",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp,
                                    color = StatusGreen
                                )
                            )
                        }
                        Text(
                            text = lastSavedNotice ?: "",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary
                        )
                    }
                }
            }

            // Overview & Technical Specs
            AudioSpecsCard()
        }
    }
}

@Composable
private fun HeaderTitleBanner(isRunning: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        color = SurfaceCardHeader,
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(PrimaryOrange.copy(alpha = 0.15f), CircleShape)
                        .border(1.dp, PrimaryOrange.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Headphones,
                        contentDescription = "Audio Bridge",
                        tint = PrimaryOrange,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "AUDIO BRIDGE",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = "Low-Latency PC Audio Sync",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    )
                }
            }

            PulsingStatusIndicator(isRunning = isRunning)
        }
    }
}

@Composable
private fun PulsingStatusIndicator(isRunning: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .size(36.dp)
            .background(DarkBackground, CircleShape)
            .border(1.dp, CardBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (isRunning) StatusGreen else TextMuted)
                .then(if (isRunning) Modifier.alpha(alpha) else Modifier)
        )
    }
}

@Composable
private fun StatusBadge(isRunning: Boolean) {
    val (bgColor, textColor, label) = if (isRunning) {
        Triple(StatusGreen.copy(alpha = 0.15f), StatusGreen, "LIVE STREAM")
    } else {
        Triple(DarkBackground, TextMuted, "DISCONNECTED")
    }

    Box(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(6.dp))
            .border(1.dp, textColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("status_badge")
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = textColor,
                fontFamily = FontFamily.Monospace
            )
        )
    }
}

@Composable
private fun ActiveStreamCard(
    pcIp: String,
    speakerPort: String,
    micPort: String,
    isMicEnabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, StatusGreen.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = "Active",
                        tint = StatusGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ACTIVE STREAMING DETAILS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = StatusGreen
                        )
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Ultra Low Latency",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBackground, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StreamDetailRow(
                    icon = Icons.Default.Computer,
                    label = "Target Host",
                    value = pcIp.ifBlank { "192.168.1.100" }
                )
                StreamDetailRow(
                    icon = Icons.Default.Speaker,
                    label = "Speaker Output",
                    value = "TCP Server Port $speakerPort (48 kHz Stereo)"
                )
                StreamDetailRow(
                    icon = Icons.Default.Mic,
                    label = "Microphone Input",
                    value = if (isMicEnabled) "TCP Port $micPort (48 kHz PCM)" else "Muted / Inactive"
                )
            }
        }
    }
}

@Composable
private fun StreamDetailRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PrimaryOrange,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                color = TextMuted
            )
        }
        Text(
            text = value,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = TextPrimary
        )
    }
}

@Composable
private fun AudioSpecsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Specs",
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "AUDIO ARCHITECTURE SPECS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = TextMuted
                    )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SpecBadge(title = "SPEAKER", desc = "TCP Server • 48kHz Stereo", modifier = Modifier.weight(1f))
                SpecBadge(title = "MICROPHONE", desc = "TCP Client • 48kHz PCM", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SpecBadge(title: String, desc: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = PrimaryOrange
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = desc,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = TextSecondary
        )
    }
}
