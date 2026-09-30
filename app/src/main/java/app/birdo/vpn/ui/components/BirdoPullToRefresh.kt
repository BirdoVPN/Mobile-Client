package app.birdo.vpn.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Pull-to-refresh (A2-023) whose spinner belongs to the PULL.
 *
 * Bound straight to a screen's loading flag, the pull spinner also dropped in
 * for every load the user did not pull for: opening the tab, a Retry button,
 * a plan change. Those loads keep the indicators they already have.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirdoPullToRefresh(
    isLoading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading) { if (!isLoading) pulled = false }
    PullToRefreshBox(
        isRefreshing = pulled && isLoading,
        onRefresh = {
            pulled = true
            onRefresh()
        },
        modifier = modifier,
        content = content,
    )
}
