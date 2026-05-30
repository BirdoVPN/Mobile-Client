package app.birdo.vpn.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.R
import app.birdo.vpn.ui.TestTags
import app.birdo.vpn.ui.theme.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role

/**
 * Login screen matching the Windows client's glassmorphic design:
 * - Pure black background
 * - Centered: pinging status dot + "Secure Connection" badge
 * - "Welcome Back" in gradient text (white→40% white top-to-bottom)
 * - Subtitle: "Sign in to access the sovereign network"
 * - Glass input fields with subtle white borders
 * - Solid white submit button with black text "Initialize Uplink"
 * - Stagger-fade-in animations for all elements
 * - 2FA verification form when backend requires it
 */
@Composable
fun LoginScreen(
    isLoading: Boolean,
    error: String?,
    requiresTwoFactor: Boolean = false,
    onLogin: (email: String, password: String) -> Unit,
    onVerifyTwoFactor: (code: String) -> Unit = {},
    onClearError: () -> Unit,
    onCancelTwoFactor: () -> Unit = {},
    onSignUp: () -> Unit = {},
    onLoginAnonymous: (anonymousId: String, password: String?) -> Unit = { _, _ -> },
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var twoFactorCode by remember { mutableStateOf("") }
    var showAnonymousDialog by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Stagger animation
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    // Pinging dot animation
    val infiniteTransition = rememberInfiniteTransition(label = "ping")
    val pingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pingScale",
    )
    val pingAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pingAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Brand mark (app launcher icon) ──
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 60)) +
                    slideInVertically(initialOffsetY = { 16 }),
            ) {
                app.birdo.vpn.ui.components.AppIconMark(
                    size = 72.dp,
                    cornerRadius = 20.dp,
                )
            }
            Spacer(Modifier.height(20.dp))

            // ── Status Badge (matches Windows: pinging dot + "Secure Connection") ──
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 100)) +
                    slideInVertically(initialOffsetY = { 20 }),
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = BirdoWhite05,
                    border = null,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Pinging dot
                        Box(contentAlignment = Alignment.Center) {
                            // Ping ring
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .scale(pingScale)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = pingAlpha * 0.6f)),
                            )
                            // Solid dot
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.login_status_badge),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = BirdoWhite80,
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── "Welcome Back" gradient text ──
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 150)) +
                    slideInVertically(initialOffsetY = { 20 }),
            ) {
                Text(
                    text = stringResource(R.string.login_welcome_back),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.White, Color.White.copy(alpha = 0.4f)),
                        ),
                    ),
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Subtitle ──
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 200)) +
                    slideInVertically(initialOffsetY = { 20 }),
            ) {
                Text(
                    text = stringResource(
                        if (requiresTwoFactor) R.string.login_2fa_subtitle
                        else R.string.login_subtitle
                    ),
                    fontSize = 14.sp,
                    color = BirdoWhite40,
                )
            }

            Spacer(Modifier.height(36.dp))

            // ── Error message ──
            AnimatedVisibility(
                visible = error != null,
                enter = fadeIn() + scaleIn(initialScale = 0.95f),
                exit = fadeOut(),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .testTag(TestTags.LOGIN_ERROR),
                    shape = RoundedCornerShape(12.dp),
                    color = BirdoRedBg,
                    border = null,
                ) {
                    Text(
                        text = error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = BirdoRed,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (requiresTwoFactor) {
                // ── 2FA Verification Form ─────────────────────────────────
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(animationSpec = tween(300)) +
                        slideInVertically(initialOffsetY = { 20 }),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Shield icon
                        Surface(
                            shape = CircleShape,
                            color = BirdoWhite05,
                            modifier = Modifier.padding(bottom = 16.dp),
                        ) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = BirdoWhite60,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .size(24.dp),
                            )
                        }

                        // 2FA code label
                        Text(
                            stringResource(R.string.login_2fa_code_label),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = BirdoWhite60,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )

                        // 2FA code input (centered, large tracking)
                        OutlinedTextField(
                            value = twoFactorCode,
                            onValueChange = { newValue ->
                                // Allow only digits, max 8 chars (6 for TOTP, 8 for backup codes)
                                val filtered = newValue.filter { it.isDigit() || it.isLetter() }.take(8)
                                twoFactorCode = filtered
                                onClearError()
                            },
                            placeholder = {
                                Text(
                                    "000000",
                                    color = BirdoWhite20,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                textAlign = TextAlign.Center,
                                fontSize = 24.sp,
                                letterSpacing = 8.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    if (twoFactorCode.length in 6..8) {
                                        onVerifyTwoFactor(twoFactorCode)
                                    }
                                },
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BirdoWhite20,
                                unfocusedBorderColor = BirdoWhite10,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = BirdoWhite80,
                                cursorColor = Color.White,
                                focusedContainerColor = GlassInput,
                                unfocusedContainerColor = GlassInput,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(TestTags.LOGIN_2FA_CODE_FIELD),
                        )

                        // Hint text
                        Text(
                            stringResource(R.string.login_2fa_code_hint),
                            fontSize = 12.sp,
                            color = BirdoWhite40,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )

                        Spacer(Modifier.height(24.dp))

                        // Verify button
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                onVerifyTwoFactor(twoFactorCode)
                            },
                            enabled = twoFactorCode.length in 6..8 && !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag(TestTags.LOGIN_2FA_VERIFY_BUTTON),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                                disabledContainerColor = BirdoWhite20,
                                disabledContentColor = BirdoWhite40,
                            ),
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.Black,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    stringResource(R.string.login_2fa_verifying),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                )
                            } else {
                                Text(
                                    stringResource(R.string.login_2fa_verify),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Back to login link
                        Text(
                            text = stringResource(R.string.login_2fa_back),
                            fontSize = 13.sp,
                            color = BirdoWhite40,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable(role = Role.Button) {
                                    twoFactorCode = ""
                                    onCancelTwoFactor()
                                }
                                .testTag(TestTags.LOGIN_2FA_BACK_BUTTON),
                        )
                    }
                }
            } else {
                // ── Standard Login Form ─────────────────────────────────

            // ── Email field ──
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 250)) +
                    slideInVertically(initialOffsetY = { 20 }),
            ) {
                Column {
                    Text(
                        stringResource(R.string.email_label),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = BirdoWhite60,
                        modifier = Modifier.padding(bottom = 6.dp, start = 4.dp),
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            onClearError()
                        },
                        placeholder = {
                            Text(stringResource(R.string.email_placeholder), color = BirdoWhite20)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BirdoBrand.PurpleSoft.copy(alpha = 0.6f),
                            unfocusedBorderColor = BirdoBrand.HairlineSoft,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = BirdoWhite80,
                            cursorColor = BirdoBrand.PurpleSoft,
                            focusedContainerColor = GlassInput,
                            unfocusedContainerColor = GlassInput,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.LOGIN_EMAIL_FIELD),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Password field ──
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 280)) +
                    slideInVertically(initialOffsetY = { 20 }),
            ) {
                Column {
                    Text(
                        stringResource(R.string.password_label),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = BirdoWhite60,
                        modifier = Modifier.padding(bottom = 6.dp, start = 4.dp),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            onClearError()
                        },
                        placeholder = {
                            Text("••••••••", color = BirdoWhite20)
                        },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    stringResource(R.string.cd_toggle_password),
                                    tint = BirdoWhite40,
                                )
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (email.isNotBlank() && password.isNotBlank()) {
                                    onLogin(email.trim(), password)
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BirdoBrand.PurpleSoft.copy(alpha = 0.6f),
                            unfocusedBorderColor = BirdoBrand.HairlineSoft,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = BirdoWhite80,
                            cursorColor = BirdoBrand.PurpleSoft,
                            focusedContainerColor = GlassInput,
                            unfocusedContainerColor = GlassInput,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.LOGIN_PASSWORD_FIELD),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── Submit button (solid white, black text — matching Windows) ──
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 300)) +
                    slideInVertically(initialOffsetY = { 20 }),
            ) {
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onLogin(email.trim(), password)
                    },
                    enabled = email.isNotBlank() && password.isNotBlank() && !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag(TestTags.LOGIN_BUTTON),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                        disabledContainerColor = BirdoWhite20,
                        disabledContentColor = BirdoWhite40,
                    ),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.login_connecting),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                    } else {
                        Text(
                            stringResource(R.string.login_button),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── Sign up link ──
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 350)),
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.login_no_account),
                        fontSize = 13.sp,
                        color = BirdoWhite40,
                    )
                    Text(
                        text = stringResource(R.string.login_sign_up),
                        fontSize = 13.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable(role = Role.Button) { onSignUp() }
                            .testTag(TestTags.LOGIN_SIGN_UP),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Continue Anonymously ──
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 400)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    OutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            showAnonymousDialog = true
                            onClearError()
                        },
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag(TestTags.LOGIN_ANONYMOUS_BUTTON),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = BirdoWhite60,
                            disabledContentColor = BirdoWhite20,
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BirdoWhite10),
                    ) {
                        Text(
                            stringResource(R.string.login_anonymous_button),
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.login_anonymous_no_account_link),
                        fontSize = 11.sp,
                        color = BirdoWhite40,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            } // end else (standard login form)
        }
    }

    if (showAnonymousDialog) {
        AnonymousLoginDialog(
            isLoading = isLoading,
            onDismiss = { showAnonymousDialog = false },
            onSubmit = { id, pwd ->
                showAnonymousDialog = false
                onLoginAnonymous(id, pwd)
            },
        )
    }
}

/**
 * Modal dialog for entering an anonymous account ID + optional password.
 * Mirrors the website's login flow for accounts with `/^\d{24}$/` IDs.
 * Account creation is web-only — this dialog only logs into existing accounts.
 */
@Composable
private fun AnonymousLoginDialog(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (anonymousId: String, password: String?) -> Unit,
) {
    var rawId by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var showPwd by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val digits = rawId.filter { it.isDigit() }.take(24)
    val canSubmit = digits.length == 24 && !isLoading

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        containerColor = Color(0xFF0A0A0A),
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                stringResource(R.string.login_anonymous_dialog_title),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.login_anonymous_dialog_subtitle),
                    color = BirdoWhite60,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.login_anonymous_id_label),
                    color = BirdoWhite60,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp),
                )
                OutlinedTextField(
                    value = formatAnonymousId(digits),
                    onValueChange = { input ->
                        rawId = input.filter { it.isDigit() }.take(24)
                    },
                    placeholder = {
                        Text(stringResource(R.string.login_anonymous_id_placeholder), color = BirdoWhite20, fontSize = 13.sp)
                    },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        letterSpacing = 1.sp,
                        fontSize = 14.sp,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BirdoBrand.PurpleSoft.copy(alpha = 0.6f),
                        unfocusedBorderColor = BirdoBrand.HairlineSoft,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = BirdoWhite80,
                        cursorColor = BirdoBrand.PurpleSoft,
                        focusedContainerColor = GlassInput,
                        unfocusedContainerColor = GlassInput,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.LOGIN_ANONYMOUS_ID_FIELD),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${digits.length} / 24",
                    color = if (digits.length == 24) BirdoWhite60 else BirdoWhite40,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.login_anonymous_password_label),
                    color = BirdoWhite60,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp),
                )
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    placeholder = { Text("••••••••", color = BirdoWhite20) },
                    trailingIcon = {
                        IconButton(onClick = { showPwd = !showPwd }) {
                            Icon(
                                if (showPwd) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                stringResource(R.string.cd_toggle_password),
                                tint = BirdoWhite40,
                            )
                        }
                    },
                    singleLine = true,
                    visualTransformation = if (showPwd) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (canSubmit) onSubmit(digits, pwd.ifBlank { null })
                        },
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BirdoBrand.PurpleSoft.copy(alpha = 0.6f),
                        unfocusedBorderColor = BirdoBrand.HairlineSoft,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = BirdoWhite80,
                        cursorColor = BirdoBrand.PurpleSoft,
                        focusedContainerColor = GlassInput,
                        unfocusedContainerColor = GlassInput,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.LOGIN_ANONYMOUS_PASSWORD_FIELD),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(digits, pwd.ifBlank { null }) },
                enabled = canSubmit,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    disabledContainerColor = BirdoWhite20,
                    disabledContentColor = BirdoWhite40,
                ),
                modifier = Modifier.testTag(TestTags.LOGIN_ANONYMOUS_SUBMIT),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.login_anonymous_signing_in), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                } else {
                    Text(stringResource(R.string.login_anonymous_submit), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(stringResource(R.string.login_anonymous_cancel), color = BirdoWhite60, fontSize = 13.sp)
            }
        },
    )
}

/** Format a digits-only string as XXXX XXXX XXXX XXXX XXXX XXXX (groups of 4). */
private fun formatAnonymousId(digits: String): String =
    digits.chunked(4).joinToString(" ")
