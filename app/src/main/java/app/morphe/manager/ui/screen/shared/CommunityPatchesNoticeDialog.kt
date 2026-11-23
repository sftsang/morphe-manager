/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import app.morphe.manager.R
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.util.COMMUNITY_PATCHES_URL
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Opens the community patches website straight away. The third-party notice is never shown, so
 * [onNoticeNeeded] is kept only for the callers' signature and is not called.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun rememberOpenCommunityPatches(onNoticeNeeded: () -> Unit): () -> Unit {
    val openUrl = rememberOpenUrl()
    return { openUrl(COMMUNITY_PATCHES_URL) }
}

/**
 * Opens the community patches website, saying first that nothing it lists is reviewed by Morphe.
 * Agreeing to it once is enough, see [rememberOpenCommunityPatches].
 */
@Composable
fun DialogHostScope.CommunityPatchesNoticeDialog(onDismiss: () -> Unit) {
    val prefs: PreferencesManager = koinInject()
    val scope = rememberCoroutineScope()
    val openUrl = rememberOpenUrl()
    ConfirmDialog(
        title = stringResource(R.string.sources_dialog_community_notice_title),
        message = stringResource(R.string.sources_dialog_community_notice_message),
        primaryText = stringResource(R.string.open),
        isPrimaryDestructive = false,
        onDismiss = onDismiss,
        onConfirm = {
            // Written in full even though the dialog leaves at once
            scope.launch { withContext(NonCancellable) { prefs.communityNoticeAcknowledged.update(true) } }
            onDismiss()
            openUrl(COMMUNITY_PATCHES_URL)
        }
    )
}
