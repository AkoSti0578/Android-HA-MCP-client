package nl.ako.hachat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nl.ako.hachat.data.db.ConversationEntity
import nl.ako.hachat.data.mcp.McpState
import nl.ako.hachat.domain.Notice
import nl.ako.hachat.ui.components.ApprovalCard
import nl.ako.hachat.ui.components.EmptyState
import nl.ako.hachat.ui.components.FallbackRow
import nl.ako.hachat.ui.components.NoticeRow
import nl.ako.hachat.ui.components.ThinkingCard
import nl.ako.hachat.ui.components.ToolCallCard
import nl.ako.hachat.ui.components.UsageRow
import nl.ako.hachat.ui.components.UserBubble
import nl.ako.hachat.ui.markdown.MarkdownView
import nl.ako.hachat.ui.theme.LocalExtraColors
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onOpenSettings: () -> Unit, vm: ChatViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf<ConversationEntity?>(null) }

    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Gesprek verwijderen?") },
            text = { Text("\"${c.title}\" wordt definitief verwijderd.") },
            confirmButton = { TextButton(onClick = { vm.deleteConversation(c.id); confirmDelete = null }) { Text("Verwijderen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annuleren") } },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ConversationDrawer(
                conversations = state.conversations,
                currentId = state.currentId,
                onNew = { vm.newConversation(); scope.launch { drawer.close() } },
                onSelect = { vm.selectConversation(it); scope.launch { drawer.close() } },
                onDelete = { confirmDelete = it },
            )
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                state.conversations.firstOrNull { it.id == state.currentId }?.title ?: "AKO HA Chat",
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            McpStatusLine(state.mcp, state.settings.readOnlyMode, onClick = vm::reconnect)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "Gesprekken") }
                    },
                    actions = {
                        IconButton(onClick = vm::newConversation, enabled = !state.busy) { Icon(Icons.Outlined.Add, "Nieuw gesprek") }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Instellingen") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                val listState = rememberLazyListState()
                val lastKey = state.items.lastOrNull()?.key
                val lastLen = (state.items.lastOrNull() as? ChatItem.AssistantText)?.markdown?.length ?: 0
                LaunchedEffect(state.items.size, lastKey, lastLen, state.pending) {
                    val total = listState.layoutInfo.totalItemsCount
                    if (total > 0) listState.animateScrollToItem(total - 1)
                }
                Box(Modifier.weight(1f)) {
                    if (state.items.isEmpty() && !state.busy) {
                        EmptyState(Modifier.align(Alignment.Center)) { vm.send(it) }
                    }
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.items, key = { it.key }) { item -> ChatItemView(item, state.canRetry && item == state.items.lastOrNull(), vm::retry) }
                        state.pending?.let { p ->
                            item(key = "approval-${p.call.id}") {
                                ApprovalCard(p.call.mcpName, p.call.input, onApprove = { vm.approve(true) }, onReject = { vm.approve(false) })
                            }
                        }
                        if (state.busy && state.status != null) {
                            item(key = "status") { StatusLine(state.status!!) }
                        }
                    }
                }
                InputBar(
                    text = input,
                    onText = vm::setInput,
                    busy = state.busy,
                    onSend = { vm.send() },
                    onStop = vm::stop,
                )
            }
        }
    }
}

@Composable
private fun ChatItemView(item: ChatItem, showRetry: Boolean, onRetry: () -> Unit) {
    when (item) {
        is ChatItem.UserText -> UserBubble(item.text)
        is ChatItem.AssistantText -> MarkdownView(item.markdown + if (item.streaming) " ▍" else "")
        is ChatItem.Thinking -> ThinkingCard(item)
        is ChatItem.ToolCallItem -> ToolCallCard(item)
        is ChatItem.FallbackSwitch -> FallbackRow(item.from, item.to)
        is ChatItem.NoticeItem -> NoticeRow(item.kind, item.text, onRetry.takeIf { showRetry && item.kind == Notice.Kind.ERROR })
        is ChatItem.TurnUsage -> UsageRow(item.usage, item.steps, item.model)
    }
}

@Composable
private fun StatusLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun McpStatusLine(state: McpState, readOnly: Boolean, onClick: () -> Unit) {
    val extra = LocalExtraColors.current
    val (color, text) = when (state) {
        is McpState.Connected -> extra.success to "${state.server.name} · ${state.tools.size} tools"
        McpState.Connecting -> MaterialTheme.colorScheme.tertiary to "verbinden…"
        is McpState.Failed -> MaterialTheme.colorScheme.error to "offline — tik om opnieuw te verbinden"
        McpState.NotConfigured -> MaterialTheme.colorScheme.outline to "MCP-server niet ingesteld"
        McpState.Idle -> MaterialTheme.colorScheme.outline to "niet verbonden"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onClick)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (readOnly) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.Lock, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.tertiary)
            Text(" lees-modus", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun InputBar(text: String, onText: (String) -> Unit, busy: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding(),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                placeholder = { Text("Vraag iets over je huis…") },
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            AnimatedVisibility(busy) {
                FilledIconButton(
                    onClick = onStop,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.size(52.dp),
                ) { Icon(Icons.Outlined.Stop, "Stoppen") }
            }
            AnimatedVisibility(!busy) {
                FilledIconButton(onClick = onSend, enabled = text.isNotBlank(), modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Send, "Versturen")
                }
            }
        }
    }
}

@Composable
private fun ConversationDrawer(
    conversations: List<ConversationEntity>,
    currentId: Long?,
    onNew: () -> Unit,
    onSelect: (Long) -> Unit,
    onDelete: (ConversationEntity) -> Unit,
) {
    ModalDrawerSheet {
        Text("Gesprekken", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 12.dp))
        NavigationDrawerItem(
            label = { Text("Nieuw gesprek") },
            icon = { Icon(Icons.Outlined.Add, null) },
            selected = false,
            onClick = onNew,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (conversations.isEmpty()) {
            Text("Nog geen gesprekken.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(24.dp))
        }
        LazyColumn {
            items(conversations, key = { it.id }) { c ->
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.updatedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    },
                    icon = { Icon(Icons.AutoMirrored.Outlined.Chat, null) },
                    badge = {
                        IconButton(onClick = { onDelete(c) }) {
                            Icon(Icons.Outlined.DeleteOutline, "Verwijderen", tint = MaterialTheme.colorScheme.outline)
                        }
                    },
                    selected = c.id == currentId,
                    onClick = { onSelect(c.id) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }
    }
}

