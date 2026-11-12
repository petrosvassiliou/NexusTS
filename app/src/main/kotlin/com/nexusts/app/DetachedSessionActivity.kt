package com.nexusts.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.theme.NexusTSTheme
import com.nexusts.app.core.ui.workspace.GooglebookWindowController
import com.nexusts.app.core.ui.workspace.WorkspaceSessionTab
import com.nexusts.app.feature.workspace.ActiveProtocolSessionDispatcher
import com.nexusts.app.feature.workspace.NexusWorkspaceViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.Json

/**
 * Native Googlebook OS Freeform Multi-Window Session Host (`documentLaunchMode="always"`).
 * Hosts detached RDP, SSH, SFTP, VNC, Web, or Hypervisor tabs on secondary or external displays.
 */
@AndroidEntryPoint
class DetachedSessionActivity : AppCompatActivity() {

    private val viewModel: NexusWorkspaceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val payload = intent.getStringExtra(GooglebookWindowController.EXTRA_DETACHED_TAB_PAYLOAD)
        val detachedTab = payload?.let {
            runCatching { Json.decodeFromString(WorkspaceSessionTab.serializer(), it) }.getOrNull()
        }

        if (detachedTab == null) {
            finish()
            return
        }

        title = "${detachedTab.title} — ${getString(R.string.app_name)}"

        setContent {
            val strings = rememberLocalizedWorkspaceStrings()
            NexusTSTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = detachedTab.title,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "• ${detachedTab.subtitleHost} (${detachedTab.protocol.name})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = strings.closeTab
                                    )
                                }
                            }
                        }

                        ActiveProtocolSessionDispatcher(
                            tab = detachedTab,
                            isFocused = true,
                            viewModel = viewModel,
                            strings = strings
                        )
                    }
                }
            }
        }
    }
}
