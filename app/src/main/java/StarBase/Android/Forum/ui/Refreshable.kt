package StarBase.Android.Forum.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Pull down to re-fetch. Wraps the one experimental API the app uses so the
 * screens themselves stay free of opt-ins.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                color = MaterialTheme.colorScheme.primary
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

/**
 * Runs [onResume] each time the app comes back to the foreground, and once when
 * the screen first appears.
 *
 * This is how a screen you left sitting catches up. The callback is expected to
 * check staleness itself (see [Freshness]) so that a quick trip to another app
 * does not fire a request.
 */
@Composable
fun OnReturnToForeground(key: Any? = Unit, onResume: () -> Unit) {
    LifecycleResumeEffect(key) {
        onResume()
        onPauseOrDispose { }
    }
}
