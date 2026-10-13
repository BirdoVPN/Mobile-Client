package app.birdo.vpn.ui.screen

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.R
import app.birdo.vpn.ui.components.BirdoTopBar
import app.birdo.vpn.ui.theme.BirdoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** The bundled notices; OpenSourceNoticesTest reads the same file from app/src/main/assets. */
internal const val NOTICES_ASSET = "third_party_notices.txt"

/**
 * MR-1686: the open-source licences, from Settings > About.
 *
 * The text is [NOTICES_ASSET], which ships in every APK and AAB (Play, GitHub
 * and F-Droid builds alike). It lists every bundled component with its
 * licence: Xray-core (MPL-2.0, with its source link), the WireGuard tunnel
 * library with wireguard-go, the Rust crates in librosenpass_jni.so and every
 * library on the release runtime classpath, followed by the licence texts and
 * notices. OpenSourceNoticesTest fails the build when a shipped dependency has
 * no entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val loadFailed = stringResource(R.string.licenses_load_failed)
    val paragraphs by produceState<List<String>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadNoticeParagraphs(context) } ?: listOf(loadFailed)
    }
    val palette = BirdoColors.current

    Scaffold(
        topBar = {
            BirdoTopBar(
                title = stringResource(R.string.licenses_title),
                onBack = onBack,
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        SelectionContainer {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Paragraphs rather than one Text, so the long licence texts
                // are laid out lazily.
                items(paragraphs.orEmpty()) { paragraph ->
                    Text(
                        text = paragraph,
                        color = palette.onSurfaceMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/** The bundled notices split into paragraphs, or null when the asset cannot be read. */
internal fun loadNoticeParagraphs(context: Context): List<String>? = try {
    context.assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() }
        .replace("\r\n", "\n")
        .split("\n\n")
        .filter { it.isNotBlank() }
} catch (_: IOException) {
    null
}
