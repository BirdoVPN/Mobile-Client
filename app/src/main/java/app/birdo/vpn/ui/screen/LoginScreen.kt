package app.birdo.vpn.ui.screen

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import app.birdo.vpn.R
import app.birdo.vpn.ui.TestTags
import app.birdo.vpn.utils.copySensitiveToClipboard
import app.birdo.vpn.ui.theme.*
import app.birdo.vpn.utils.formatAnonymousId
import app.birdo.vpn.utils.is2faCodeComplete
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/** Which authentication method the standard login form shows. */
private enum class AuthTab { Email, Anonymous, Sso }

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
    onSsoLogin: (provider: String) -> Unit = {},
    onCreateAnonymous: () -> Unit = {},
    /** Non-null right after an in-app anonymous account was created: the minted
     *  24-digit ID, shown once and blocking until acknowledged. */
    pendingAnonymousId: String? = null,
    onAcknowledgeAnonymousId: () -> Unit = {},
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var twoFactorCode by remember { mutableStateOf("") }
    // Which auth method the standard (non-2FA) form shows: Email or Anonymous.
    var activeTab by remember { mutableStateOf(AuthTab.Email) }
    var anonId by remember { mutableStateOf("") }
    var anonPassword by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    // Stagger animation
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    // The account already exists at this point and its 24-digit ID is the only
    // way back into it, so this is rendered over whatever form is showing and
    // has no dismiss path — the ViewModel holds sign-in until it is confirmed.
    if (pendingAnonymousId != null) {
        AnonymousIdSavedDialog(
            anonymousId = pendingAnonymousId,
            onAcknowledge = onAcknowledgeAnonymousId,
        )
    }

    // Scrollable + centered: centers the form when it fits, and scrolls instead
    // of CLIPPING when the content is taller than the viewport (small screens, or
    // once the SSO buttons were added the bottom Sign-up / Anonymous rows were
    // being cut off the bottom edge). vertical padding keeps the first/last rows
    // off the very edges when scrolled.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
            // ── Status badge: small SQUARE app mark next to "Secure Connection",
            //     centered. (Replaces the large top icon + the pinging dot.) ──
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(BirdoMotion.Slow, delayMillis = 80, easing = BirdoMotion.Decel)) +
                    slideInVertically(initialOffsetY = { 20 }),
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = BirdoWhite05,
                    border = null,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Full square launcher image (not the round-cropped mark).
                        app.birdo.vpn.ui.components.AppIconMark(
                            size = 26.dp,
                            cornerRadius = 6.dp,
                            square = true,
                        )
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
                enter = fadeIn(animationSpec = tween(BirdoMotion.Slow, delayMillis = 150, easing = BirdoMotion.Decel)) +
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
                enter = fadeIn(animationSpec = tween(BirdoMotion.Slow, delayMillis = 200, easing = BirdoMotion.Decel)) +
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
                        // Assertive: a login failure that appears silently is a
                        // failure a TalkBack user never learns about.
                        .semantics { liveRegion = LiveRegionMode.Assertive }
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
                                // Accept a 6-digit TOTP OR a hex backup code (16 hex,
                                // up to 19 chars with dashes). Keep digits, hex
                                // letters and dashes; cap at 19.
                                val filtered = newValue
                                    .filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '-' }
                                    .take(19)
                                twoFactorCode = filtered
                                onClearError()
                            },
                            placeholder = {
                                Text(
                                    "000000 or backup code",
                                    color = BirdoWhite40,
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
                                // Text (not Number) so hex backup-code letters are typable;
                                // autoCorrect off so the IME can't mangle/compose the code
                                // (the value is filtered char-by-char in onValueChange anyway).
                                keyboardType = KeyboardType.Text,
                                autoCorrect = false,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    if (is2faCodeComplete(twoFactorCode)) {
                                        onVerifyTwoFactor(twoFactorCode)
                                    }
                                },
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BirdoBrand.AccentSoft.copy(alpha = 0.6f),
                                unfocusedBorderColor = BirdoWhite10,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = BirdoWhite80,
                                cursorColor = Color.White,
                                focusedContainerColor = Color.White.copy(alpha = 0.12f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.09f),
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
                            enabled = is2faCodeComplete(twoFactorCode) && !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
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
                                .minimumInteractiveComponentSize()
                        .clickable(role = Role.Button) {
                                    twoFactorCode = ""
                                    onCancelTwoFactor()
                                }
                                .testTag(TestTags.LOGIN_2FA_BACK_BUTTON),
                        )
                    }
                }
            } else {
                // ── Standard Login Form (tabbed: Email | Anonymous) ──

            // ── Auth method tabs: Email | Anonymous ──
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(BirdoMotion.Slow, delayMillis = 235, easing = BirdoMotion.Decel)) +
                    slideInVertically(initialOffsetY = { 20 }),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BirdoWhite05)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AuthTabButton(
                        label = stringResource(R.string.login_tab_email),
                        selected = activeTab == AuthTab.Email,
                        modifier = Modifier.weight(1f).testTag(TestTags.LOGIN_TAB_EMAIL),
                        onClick = { activeTab = AuthTab.Email; onClearError() },
                    )
                    AuthTabButton(
                        label = stringResource(R.string.login_tab_anonymous),
                        selected = activeTab == AuthTab.Anonymous,
                        modifier = Modifier.weight(1f).testTag(TestTags.LOGIN_TAB_ANONYMOUS),
                        onClick = { activeTab = AuthTab.Anonymous; onClearError() },
                    )
                    AuthTabButton(
                        label = stringResource(R.string.login_tab_sso),
                        selected = activeTab == AuthTab.Sso,
                        modifier = Modifier.weight(1f).testTag(TestTags.LOGIN_TAB_SSO),
                        onClick = { activeTab = AuthTab.Sso; onClearError() },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Fixed-height content well so switching Email/Anonymous/SSO does NOT
            // change the column height and jolt everything up/down. Sized to the
            // tallest tab (Anonymous); shorter tabs top-align within it.
            Column(modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
            when (activeTab) {
            AuthTab.Email -> {
            // ── Email field ──
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(BirdoMotion.Slow, delayMillis = 250, easing = BirdoMotion.Decel)) +
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
                            Text(stringResource(R.string.email_placeholder), color = BirdoWhite40)
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
                            focusedBorderColor = BirdoBrand.AccentSoft.copy(alpha = 0.6f),
                            unfocusedBorderColor = BirdoBrand.HairlineSoft,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = BirdoWhite80,
                            cursorColor = BirdoBrand.AccentSoft,
                            focusedContainerColor = Color.White.copy(alpha = 0.12f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.09f),
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
                enter = fadeIn(animationSpec = tween(BirdoMotion.Slow, delayMillis = 280, easing = BirdoMotion.Decel)) +
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
                            Text("••••••••", color = BirdoWhite40)
                        },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    stringResource(
                                        if (showPassword) R.string.cd_hide_password
                                        else R.string.cd_show_password,
                                    ),
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
                                if (email.trim().isNotEmpty() && password.isNotBlank()) {
                                    onLogin(email.trim(), password)
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BirdoBrand.AccentSoft.copy(alpha = 0.6f),
                            unfocusedBorderColor = BirdoBrand.HairlineSoft,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = BirdoWhite80,
                            cursorColor = BirdoBrand.AccentSoft,
                            focusedContainerColor = Color.White.copy(alpha = 0.12f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.09f),
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
                enter = fadeIn(animationSpec = tween(BirdoMotion.Slow, delayMillis = 300, easing = BirdoMotion.Decel)) +
                    slideInVertically(initialOffsetY = { 20 }),
            ) {
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onLogin(email.trim(), password)
                    },
                    enabled = email.trim().isNotEmpty() && password.isNotBlank() && !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
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
            }
            AuthTab.Anonymous -> {
                // ── Anonymous form (inline; replaces the old dialog) ──
                Column {
                    Text(
                        stringResource(R.string.login_anonymous_id_label),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = BirdoWhite60,
                        modifier = Modifier.padding(bottom = 6.dp, start = 4.dp),
                    )
                    OutlinedTextField(
                        value = anonId,
                        onValueChange = { next -> anonId = next.filter { it.isDigit() }.take(24); onClearError() },
                        placeholder = { Text(stringResource(R.string.login_anonymous_id_placeholder), color = BirdoWhite40, fontSize = 13.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BirdoBrand.AccentSoft.copy(alpha = 0.6f),
                            unfocusedBorderColor = BirdoBrand.HairlineSoft,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = BirdoWhite80,
                            cursorColor = BirdoBrand.AccentSoft,
                            focusedContainerColor = Color.White.copy(alpha = 0.12f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.09f),
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.LOGIN_ANONYMOUS_ID_FIELD),
                    )
                    Text(
                        stringResource(R.string.login_anonymous_caption_inline),
                        fontSize = 11.sp,
                        color = BirdoWhite40,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))

                Column {
                    Text(
                        stringResource(R.string.login_anonymous_password_label),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = BirdoWhite60,
                        modifier = Modifier.padding(bottom = 6.dp, start = 4.dp),
                    )
                    OutlinedTextField(
                        value = anonPassword,
                        onValueChange = { anonPassword = it; onClearError() },
                        placeholder = { Text("••••••••", color = BirdoWhite40) },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    stringResource(if (showPassword) R.string.cd_hide_password else R.string.cd_show_password),
                                    tint = BirdoWhite40,
                                )
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            if (anonId.length == 24) onLoginAnonymous(anonId, anonPassword.takeIf { it.isNotBlank() })
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BirdoBrand.AccentSoft.copy(alpha = 0.6f),
                            unfocusedBorderColor = BirdoBrand.HairlineSoft,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = BirdoWhite80,
                            cursorColor = BirdoBrand.AccentSoft,
                            focusedContainerColor = Color.White.copy(alpha = 0.12f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.09f),
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.LOGIN_ANONYMOUS_PASSWORD_FIELD),
                    )
                }

                Spacer(Modifier.height(28.dp))

                Button(
                    onClick = { focusManager.clearFocus(); onLoginAnonymous(anonId, anonPassword.takeIf { it.isNotBlank() }) },
                    enabled = anonId.length == 24 && !isLoading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(TestTags.LOGIN_ANONYMOUS_SUBMIT),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                        disabledContainerColor = BirdoWhite20,
                        disabledContentColor = BirdoWhite40,
                    ),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.login_connecting), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    } else {
                        Text(stringResource(R.string.login_anonymous_submit), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Create a brand-new anonymous account in-app (no email, no SSO).
                Text(
                    text = stringResource(R.string.login_anonymous_create),
                    fontSize = 13.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clickable(enabled = !isLoading, role = Role.Button) {
                            focusManager.clearFocus()
                            onClearError()
                            onCreateAnonymous()
                        }
                        .testTag(TestTags.LOGIN_ANONYMOUS_CREATE),
                )
            }
            AuthTab.Sso -> {
            // ── Continue with Google / GitHub (native SSO, no password) ──
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(BirdoMotion.Slow, delayMillis = 260, easing = BirdoMotion.Decel)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.login_sso_tab_caption),
                        fontSize = 12.sp,
                        color = BirdoWhite40,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            onClearError()
                            onSsoLogin("google")
                        },
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag(TestTags.LOGIN_SSO_GOOGLE),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.White.copy(alpha = 0.12f),
                            contentColor = Color.White,
                            disabledContentColor = BirdoWhite20,
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                    ) {
                        Text(
                            stringResource(R.string.login_sso_google),
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            onClearError()
                            onSsoLogin("github")
                        },
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag(TestTags.LOGIN_SSO_GITHUB),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.White.copy(alpha = 0.12f),
                            contentColor = Color.White,
                            disabledContentColor = BirdoWhite20,
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                    ) {
                        Text(
                            stringResource(R.string.login_sso_github),
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            }
            }
            }

            Spacer(Modifier.height(20.dp))

            // ── Sign up link ──
            // Hidden on the Anonymous tab: an anonymous account is created in-app
            // ("Create anonymous account"), not via the web "Sign Up" flow, so the
            // prompt is irrelevant (and misleading) there. Kept on Email / SSO.
            AnimatedVisibility(
                visible = visible && activeTab != AuthTab.Anonymous,
                enter = fadeIn(animationSpec = tween(BirdoMotion.Slow, delayMillis = 350, easing = BirdoMotion.Decel)),
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    // Center-align vertically: "Sign up" carries a 48dp min touch
                    // target so its box is taller than the plain prompt text; with
                    // the default (top) alignment the two didn't sit on one line.
                    verticalAlignment = Alignment.CenterVertically,
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
                            .padding(vertical = 12.dp, horizontal = 4.dp)
                            .testTag(TestTags.LOGIN_SIGN_UP),
                    )
                }
            }
            } // end else (standard login form)
    }
}

/**
 * "Save your account ID" step shown once, immediately after an anonymous
 * account is created in-app.
 *
 * The server returns the minted 24-digit ID exactly once and it is the account's
 * only credential — there is no email, no password and no reset. Android used to
 * discard it and drop the user straight into the connected Home screen, so the
 * account died with the app's tokens. Hence: no dismiss button, no tap-outside,
 * no back-press escape, and a single explicit confirm — a toast or a snackbar is
 * not enough, because the cost of missing it is a permanently lost account.
 */
@Composable
private fun AnonymousIdSavedDialog(
    anonymousId: String,
    onAcknowledge: () -> Unit,
) {
    val context = LocalContext.current
    val grouped = formatAnonymousId(anonymousId)

    AlertDialog(
        // Empty: dismissing by tapping outside or pressing back would lose the
        // ID with the account already created. Acknowledge is the only exit.
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        containerColor = BirdoSurface,
        titleContentColor = Color.White,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Key,
                    contentDescription = null,
                    tint = BirdoGreen,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.anon_created_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
            }
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.anon_created_body),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = BirdoWhite60,
                )
                Spacer(Modifier.height(14.dp))
                // Tapping the block copies the RAW digits, not the grouped
                // rendering: the login field strips non-digits anyway, but a
                // pasted value with spaces reads as damaged to a worried user.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BirdoWhite05)
                        .clickable(role = Role.Button) {
                            copySensitiveToClipboard(context, "Birdo account", anonymousId)
                            Toast.makeText(
                                context,
                                context.getString(R.string.anon_created_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
                        .testTag(TestTags.LOGIN_ANONYMOUS_CREATED_ID),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = grouped,
                        color = Color.White,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        fontFamily = FontFamily.Monospace,
                        // NO maxLines/ellipsis: all 24 digits must be readable.
                        // Wrapping onto a second line is correct here; truncating
                        // would hide part of the only credential the user has.
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.anon_created_copy_cd),
                        tint = BirdoAccentSoft,
                        modifier = Modifier.padding(8.dp).size(18.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = BirdoYellowLight,
                        modifier = Modifier.size(16.dp).padding(top = 1.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.anon_created_warning),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = BirdoWhite40,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAcknowledge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
                modifier = Modifier.testTag(TestTags.LOGIN_ANONYMOUS_ACK),
            ) {
                Text(
                    stringResource(R.string.anon_created_ack),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
        },
    )
}

/** Segmented tab button for the Email | Anonymous switcher. */
@Composable
private fun AuthTabButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected) Color.White else BirdoWhite60,
            )
        }
    }
}

