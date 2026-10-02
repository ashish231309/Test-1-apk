package com.ashishkumar.nivara.ui.vault

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ashishkumar.nivara.domain.security.session.SessionManager
import com.ashishkumar.nivara.domain.security.session.SessionState
import com.ashishkumar.nivara.domain.vault.VaultFormatComponent
import com.ashishkumar.nivara.domain.vault.VaultRepository
import com.ashishkumar.nivara.domain.vault.VaultRecoveryCodeCodec
import com.ashishkumar.nivara.domain.vault.VaultRecoveryRepository
import com.ashishkumar.nivara.domain.vault.VaultRootSelectionResult
import com.ashishkumar.nivara.domain.vault.VaultStatus
import com.ashishkumar.nivara.domain.vault.content.VaultImportRepository
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRead
import com.ashishkumar.nivara.domain.vault.content.VaultIndexRepository
import com.ashishkumar.nivara.domain.vault.content.VaultContentDiagnostics
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationRead
import com.ashishkumar.nivara.domain.vault.content.VaultOrganizationRepository
import com.ashishkumar.nivara.domain.vault.content.VaultAlbum
import com.ashishkumar.nivara.domain.vault.content.VaultAlbumId
import com.ashishkumar.nivara.domain.vault.content.VaultAlbumMember
import com.ashishkumar.nivara.domain.vault.content.VaultAlbumMembershipResolver
import com.ashishkumar.nivara.domain.vault.content.VaultAlbumOrdering
import com.ashishkumar.nivara.domain.vault.content.VaultItemSearch
import com.ashishkumar.nivara.domain.vault.content.VaultSearchState
import com.ashishkumar.nivara.domain.vault.content.VaultItemSort
import com.ashishkumar.nivara.domain.vault.content.VaultContentPresentationGateway
import com.ashishkumar.nivara.domain.vault.content.VaultContentClassifier
import com.ashishkumar.nivara.domain.vault.content.VaultContentCategory
import com.ashishkumar.nivara.domain.vault.content.VaultContentClassification
import com.ashishkumar.nivara.domain.vault.content.VaultImagePixels
import com.ashishkumar.nivara.domain.vault.content.VaultItem
import com.ashishkumar.nivara.domain.vault.content.VaultItemId
import com.ashishkumar.nivara.domain.vault.content.VaultItemOpenState
import com.ashishkumar.nivara.domain.vault.content.VaultSourceSelectionResult
import com.ashishkumar.nivara.ui.components.NivaraPageHeader
import com.ashishkumar.nivara.ui.components.NivaraSpacing
import com.ashishkumar.nivara.ui.security.SecureScreenEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
fun VaultScreen(
    repository: VaultRepository,
    recoveryRepository: VaultRecoveryRepository? = null,
    indexRepository: VaultIndexRepository,
    organizationRepository: VaultOrganizationRepository,
    importRepository: VaultImportRepository,
    contentPresentationGateway: VaultContentPresentationGateway? = null,
    sessionManager: SessionManager,
    rootSelectionResult: VaultRootSelectionResult?,
    sourceSelectionResult: VaultSourceSelectionResult?,
    onConsumeRootSelectionResult: () -> Unit,
    onConsumeSourceSelectionResult: () -> Unit,
    onConfigureRoot: () -> Unit,
    onChooseSource: () -> Unit,
    onBack: () -> Unit,
    onReturnHomeForAuthentication: () -> Unit,
) {
    SecureScreenEffect()
    val viewModel: VaultViewModel = viewModel(
        factory = VaultViewModel.Factory(
            repository, indexRepository, importRepository, sessionManager, organizationRepository, recoveryRepository,
        ),
    )
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
    var selectedItemId by remember { mutableStateOf<VaultItemId?>(null) }
    BackHandler(enabled = selectedItemId != null || uiState.activeAlbumId != null) {
        if (selectedItemId != null) selectedItemId = null else viewModel.showAlbums()
    }

    LaunchedEffect(sessionState) {
        if (sessionState !is SessionState.Authenticated) selectedItemId = null
        viewModel.refresh()
    }
    LaunchedEffect(rootSelectionResult) {
        rootSelectionResult?.let {
            viewModel.onRootSelectionResult(it)
            onConsumeRootSelectionResult()
        }
    }
    LaunchedEffect(sourceSelectionResult) {
        sourceSelectionResult?.let {
            viewModel.onSourceSelectionResult(it)
            onConsumeSourceSelectionResult()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val recoverySetupId = uiState.recoverySetupPreview?.setupId
    DisposableEffect(lifecycleOwner, recoverySetupId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && recoverySetupId != null) {
                viewModel.cancelRecoverySetup(recoverySetupId)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            recoverySetupId?.let(viewModel::cancelRecoverySetup)
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(NivaraSpacing.large),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NivaraPageHeader(
                title = "External encrypted vault",
                supportingText = "Import documents through Android's picker. Nivara stores encrypted objects and an authenticated index in the selected vault.",
            )
            when {
                uiState.checking -> CircularProgressIndicator()
                uiState.authenticationRequired || sessionState !is SessionState.Authenticated -> {
                    Text("Authenticate from Nivara Home before inspecting or changing vault storage.")
                    Button(modifier = Modifier.padding(top = 12.dp), onClick = onReturnHomeForAuthentication) {
                        Text("Return to Nivara Home")
                    }
                }
                uiState.initializing -> {
                    CircularProgressIndicator()
                    Text("Initializing the selected external folder…", Modifier.padding(top = 12.dp))
                }
                else -> {
                    val ready = uiState.status as? VaultStatus.Ready
                    val opened = (uiState.indexState as? VaultIndexRead.Ready)?.items
                        ?.firstOrNull { it.id == selectedItemId && it.lifecycle == com.ashishkumar.nivara.domain.vault.content.VaultItemLifecycle.ACTIVE }
                    if (ready != null && selectedItemId != null && opened != null && contentPresentationGateway != null) {
                        VaultItemViewerContent(
                            vaultId = ready.vaultId,
                            item = opened,
                            gateway = contentPresentationGateway,
                            sessionManager = sessionManager,
                            onBack = { selectedItemId = null },
                        )
                    } else {
                        VaultStatusContent(
                            state = uiState,
                            viewModel = viewModel,
                            onRefresh = viewModel::refresh,
                            onInitialize = viewModel::initialize,
                            onInitializeIndex = viewModel::initializeIndex,
                            onConfigureRoot = onConfigureRoot,
                            onChooseSource = onChooseSource,
                            onOpenItem = { if (contentPresentationGateway != null) selectedItemId = it.id },
                        )
                    }
                }
            }
            OutlinedButton(modifier = Modifier.padding(top = 24.dp), onClick = onBack) { Text("Back") }
        }
    }
}

@Composable
private fun VaultCollectionContent(
    state: VaultUiState,
    index: VaultIndexRead.Ready,
    viewModel: VaultViewModel,
    onOpenItem: (VaultItem) -> Unit,
) {
    var createAlbumDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<VaultAlbum?>(null) }
    var deleteTarget by remember { mutableStateOf<VaultAlbum?>(null) }
    var membershipItem by remember { mutableStateOf<VaultItemId?>(null) }
    var trashTarget by remember { mutableStateOf<VaultItem?>(null) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var visibleItemLimit by remember(index.generation, state.activeAlbumId, state.searchQuery, state.trashSearchQuery, state.itemSort, state.trashSort) {
        mutableIntStateOf(100)
    }

    TabRow(selectedTabIndex = when (state.collectionMode) {
        VaultCollectionMode.ALL_ITEMS -> 0
        VaultCollectionMode.ALBUMS -> 1
        VaultCollectionMode.SEARCH -> 2
        VaultCollectionMode.TRASH -> 3
    }) {
        Tab(state.collectionMode == VaultCollectionMode.ALL_ITEMS, onClick = viewModel::showAllItems, text = { Text("All Items") })
        Tab(state.collectionMode == VaultCollectionMode.ALBUMS, onClick = viewModel::showAlbums, text = { Text("Albums") })
        Tab(state.collectionMode == VaultCollectionMode.SEARCH, onClick = viewModel::showSearch, text = { Text("Search") })
        Tab(state.collectionMode == VaultCollectionMode.TRASH, onClick = viewModel::showTrash, text = { Text("Trash") })
    }
    if (state.collectionMode != VaultCollectionMode.ALBUMS && state.collectionMode != VaultCollectionMode.TRASH && state.organizationState != null &&
        state.organizationState !is VaultOrganizationRead.Ready
    ) {
        OrganizationFailure(state.organizationState)
    }

    val activeAlbum = if (state.collectionMode == VaultCollectionMode.ALBUMS) {
        (state.organizationState as? VaultOrganizationRead.Ready)?.snapshot?.albums?.firstOrNull { it.id == state.activeAlbumId }
    } else null
    if (state.collectionMode == VaultCollectionMode.SEARCH || state.collectionMode == VaultCollectionMode.TRASH || activeAlbum != null) {
        OutlinedTextField(
            value = if (state.collectionMode == VaultCollectionMode.TRASH) state.trashSearchQuery else state.searchQuery,
            onValueChange = if (state.collectionMode == VaultCollectionMode.TRASH) viewModel::setTrashSearchQuery else viewModel::setSearchQuery,
            label = { Text(when {
                state.collectionMode == VaultCollectionMode.TRASH -> "Search Trash by name, MIME, or type"
                activeAlbum == null -> "Search name, MIME, or type"
                else -> "Search this album"
            }) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }

    if (state.collectionMode == VaultCollectionMode.ALBUMS && state.activeAlbumId == null) {
        when (val organization = state.organizationState) {
            null -> CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
            is VaultOrganizationRead.Ready -> {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${organization.snapshot.albums.size} album${if (organization.snapshot.albums.size == 1) "" else "s"}")
                    Button(enabled = !state.organizationMutationBusy, onClick = { createAlbumDialog = true }) { Text("Create album") }
                }
                if (organization.snapshot.albums.isEmpty()) {
                    Text("No albums yet. Albums contain ordered item references only; items stay in the vault.",
                        modifier = Modifier.padding(top = 8.dp))
                }
                VaultAlbumOrdering.ordered(organization.snapshot.albums).forEach { album ->
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { viewModel.openAlbum(album.id) }) {
                            Text("${album.name}  ·  ${album.memberItemIds.size} reference${if (album.memberItemIds.size == 1) "" else "s"}")
                        }
                        Row {
                            TextButton(enabled = !state.organizationMutationBusy, onClick = { renameTarget = album }) { Text("Rename") }
                            TextButton(enabled = !state.organizationMutationBusy, onClick = { deleteTarget = album }) { Text("Delete album") }
                        }
                    }
                }
            }
            else -> OrganizationFailure(state.organizationState)
        }
    } else if (state.collectionMode == VaultCollectionMode.ALBUMS && state.activeAlbumId != null) {
        when (val organization = state.organizationState) {
            is VaultOrganizationRead.Ready -> {
                if (activeAlbum == null) {
                    Text("The selected album is no longer available.", modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::showAlbums) { Text("Back to albums") }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = viewModel::showAlbums) { Text("‹ Albums") }
                        Text(activeAlbum.name, style = MaterialTheme.typography.titleMedium)
                    }
                    when (val resolved = VaultAlbumMembershipResolver.resolve(activeAlbum, index)) {
                        null -> Text("The authenticated index is unavailable; membership status is not shown.", color = MaterialTheme.colorScheme.error)
                        else -> {
                            val resolvedItems = resolved.filterIsInstance<VaultAlbumMember.Resolved>().map { it.item }
                            val trashedMembers = resolved.filterIsInstance<VaultAlbumMember.Trashed>()
                            val searchState = VaultItemSearch.search(resolvedItems, state.searchQuery)
                            val visibleItems = when (searchState) {
                                is VaultSearchState.EmptyQuery -> searchState.items
                                is VaultSearchState.Matches -> searchState.items
                                is VaultSearchState.NoMatches -> emptyList()
                                else -> emptyList()
                            }
                            val orderedItems = state.itemSort.apply(visibleItems)
                            SortControls(state, viewModel) { sortMenuExpanded = true }
                            if (sortMenuExpanded) {
                                DropdownMenu(expanded = true, onDismissRequest = { sortMenuExpanded = false }) {
                                    VaultItemSort.Field.values().filter { it != VaultItemSort.Field.DEFAULT && it != VaultItemSort.Field.TRASH_TIME }.forEach { field ->
                                        DropdownMenuItem(text = { Text("Sort by ${field.label()}") }, onClick = {
                                            viewModel.setItemSort(state.itemSort.copy(field = field))
                                            sortMenuExpanded = false
                                        })
                                    }
                                    DropdownMenuItem(text = { Text("Use index order (default)") }, onClick = {
                                        viewModel.setItemSort(VaultItemSort())
                                        sortMenuExpanded = false
                                    })
                                }
                            }
                            val stale = resolved.filterIsInstance<VaultAlbumMember.Stale>()
                            Text("${resolvedItems.size} active member${if (resolvedItems.size == 1) "" else "s"}; ${trashedMembers.size} trashed reference${if (trashedMembers.size == 1) "" else "s"} preserved; ${stale.size} stale reference${if (stale.size == 1) "" else "s"}. Stale IDs are retained until explicitly removed.",
                                modifier = Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                            SearchStatus(searchState)
                            orderedItems.take(visibleItemLimit).forEach { item ->
                                VaultItemRow(item, state, onOpenItem, onManageMembership = null,
                                    onRemoveMembership = { viewModel.removeMembership(activeAlbum.id, item.id) },
                                    onMoveToTrash = { trashTarget = item })
                            }
                            stale.take(visibleItemLimit).forEach { member ->
                                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Missing indexed item · ${member.itemId.value}", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                                    TextButton(enabled = !state.organizationMutationBusy,
                                        onClick = { viewModel.removeMembership(activeAlbum.id, member.itemId) }) { Text("Remove reference") }
                                }
                            }
                            if (orderedItems.size > visibleItemLimit || stale.size > visibleItemLimit) {
                                Text("Showing up to $visibleItemLimit indexed members and stale references.")
                                OutlinedButton(onClick = { visibleItemLimit += 100 }) { Text("Load next 100") }
                            }
                        }
                    }
                }
            }
            null -> CircularProgressIndicator()
            else -> OrganizationFailure(organization)
        }
    } else if (state.collectionMode == VaultCollectionMode.TRASH) {
        Text("Trash keeps encrypted content in place. Restore returns the same item ID and album memberships; moving here does not permanently delete an item.",
            modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        val trashState = state.trashState
        when (trashState) {
            null -> CircularProgressIndicator()
            VaultTrashState.Locked -> Text("Session locked. Authenticate through Nivara Home to inspect Trash.", color = MaterialTheme.colorScheme.error)
            VaultTrashState.VaultUnavailable -> Text("Vault state is unavailable; Trash was not treated as empty.", color = MaterialTheme.colorScheme.error)
            VaultTrashState.IndexMissing -> Text("The authenticated item index is missing. Trash state is unavailable, not empty.", color = MaterialTheme.colorScheme.error)
            VaultTrashState.Unavailable -> Text("Trash metadata is unavailable; it was not treated as empty.", color = MaterialTheme.colorScheme.error)
            VaultTrashState.Unreadable -> Text("Trash metadata/index is unreadable; it was not treated as empty.", color = MaterialTheme.colorScheme.error)
            VaultTrashState.AccessDenied -> Text("Android denied access to the authenticated Trash state.", color = MaterialTheme.colorScheme.error)
            is VaultTrashState.UnsupportedVersion -> Text("Trash index version ${trashState.version} is unsupported.", color = MaterialTheme.colorScheme.error)
            VaultTrashState.Empty -> Text("Trash is empty.", modifier = Modifier.padding(top = 8.dp))
            is VaultTrashState.Populated -> {
                val searchState = VaultItemSearch.searchTrash(index, state.trashSearchQuery)
                val filtered = when (searchState) {
                    is VaultSearchState.EmptyQuery -> searchState.items
                    is VaultSearchState.Matches -> searchState.items
                    is VaultSearchState.NoMatches -> emptyList()
                    else -> emptyList()
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { sortMenuExpanded = true }) { Text("Sort Trash: ${state.trashSort.field.label()}") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        viewModel.setTrashSort(state.trashSort.copy(direction = if (state.trashSort.direction == VaultItemSort.Direction.ASCENDING)
                            VaultItemSort.Direction.DESCENDING else VaultItemSort.Direction.ASCENDING))
                    }) { Text(if (state.trashSort.direction == VaultItemSort.Direction.ASCENDING) "Ascending ↑" else "Descending ↓") }
                }
                if (sortMenuExpanded) {
                    DropdownMenu(expanded = true, onDismissRequest = { sortMenuExpanded = false }) {
                        listOf(VaultItemSort.Field.TRASH_TIME, VaultItemSort.Field.NAME, VaultItemSort.Field.SIZE,
                            VaultItemSort.Field.IMPORT_TIME, VaultItemSort.Field.TYPE).forEach { field ->
                            DropdownMenuItem(text = { Text("Sort by ${field.label()}") }, onClick = {
                                viewModel.setTrashSort(state.trashSort.copy(field = field))
                                sortMenuExpanded = false
                            })
                        }
                    }
                }
                if (state.trashSearchQuery.isNotEmpty()) SearchStatus(searchState)
                state.trashSort.apply(filtered).take(visibleItemLimit).forEach { item ->
                    TrashItemRow(
                        item = item,
                        contentAvailable = trashContentAvailability(index.contentDiagnostics, item.id),
                        mutationBusy = state.trashMutationBusy,
                        onRestore = { viewModel.restoreFromTrash(item.id) },
                    )
                }
                if (filtered.size > visibleItemLimit) {
                    Text("Showing ${visibleItemLimit.coerceAtMost(filtered.size)} of ${filtered.size} trashed items.")
                    OutlinedButton(onClick = { visibleItemLimit = (visibleItemLimit + 100).coerceAtMost(filtered.size) }) {
                        Text("Load next 100")
                    }
                }
            }
        }
    } else {
        val searchState = VaultItemSearch.search(index, if (state.collectionMode == VaultCollectionMode.SEARCH) state.searchQuery else "")
        val sourceItems = when (searchState) {
            is VaultSearchState.EmptyQuery -> searchState.items
            is VaultSearchState.Matches -> searchState.items
            is VaultSearchState.NoMatches -> emptyList()
            else -> emptyList()
        }
        val items = state.itemSort.apply(sourceItems)
        SortControls(state, viewModel) { sortMenuExpanded = true }
        if (sortMenuExpanded) {
            DropdownMenu(expanded = true, onDismissRequest = { sortMenuExpanded = false }) {
                VaultItemSort.Field.values().filter { it != VaultItemSort.Field.DEFAULT && it != VaultItemSort.Field.TRASH_TIME }.forEach { field ->
                    DropdownMenuItem(
                        text = { Text("Sort by ${field.label()}") },
                        onClick = {
                            viewModel.setItemSort(state.itemSort.copy(field = field))
                            sortMenuExpanded = false
                        },
                    )
                }
                DropdownMenuItem(text = { Text("Use index order (default)") }, onClick = {
                    viewModel.setItemSort(VaultItemSort())
                    sortMenuExpanded = false
                })
            }
        }
        if (state.collectionMode == VaultCollectionMode.SEARCH) SearchStatus(searchState)
        if (items.isEmpty() && state.collectionMode != VaultCollectionMode.SEARCH) {
            Text("No active items. Items in Trash remain restorable.", modifier = Modifier.padding(top = 8.dp))
        }
        items.take(visibleItemLimit).forEach { item ->
            VaultItemRow(item, state, onOpenItem,
                onManageMembership = { membershipItem = item.id }, onRemoveMembership = null,
                onMoveToTrash = { trashTarget = item })
        }
        if (items.size > visibleItemLimit) {
            Text("Showing ${visibleItemLimit.coerceAtMost(items.size)} of ${items.size} items.")
            OutlinedButton(onClick = { visibleItemLimit = (visibleItemLimit + 100).coerceAtMost(items.size) }) {
                Text("Load next 100 items")
            }
        }
    }

    if (createAlbumDialog || renameTarget != null) {
        val target = renameTarget
        var name by remember(target?.id) { mutableStateOf(target?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { createAlbumDialog = false; renameTarget = null },
            title = { Text(if (target == null) "Create album" else "Rename album") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Album name") }, singleLine = true)
                    Text("Names are trimmed, NFC-normalized, and limited to 100 Unicode characters.",
                        modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = !state.organizationMutationBusy, onClick = {
                    if (target == null) viewModel.createAlbum(name) else viewModel.renameAlbum(target.id, name)
                    createAlbumDialog = false
                    renameTarget = null
                }) { Text(if (target == null) "Create" else "Save") }
            },
            dismissButton = { TextButton(onClick = { createAlbumDialog = false; renameTarget = null }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { album ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete album?") },
            text = { Text("This deletes only the album metadata and its item-ID references. No vault item or encrypted object will be deleted.") },
            confirmButton = { TextButton(enabled = !state.organizationMutationBusy, onClick = {
                viewModel.deleteAlbum(album.id)
                deleteTarget = null
            }) { Text("Delete album") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    trashTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { trashTarget = null },
            title = { Text("Move item to Trash?") },
            text = { Text("${item.originalFilename} will leave active browsing. Its encrypted content, item ID, metadata, and album memberships remain unchanged and can be restored. This does not permanently delete anything.") },
            confirmButton = { TextButton(enabled = !state.trashMutationBusy, onClick = {
                viewModel.moveToTrash(item.id)
                trashTarget = null
            }) { Text("Move to Trash") } },
            dismissButton = { TextButton(onClick = { trashTarget = null }) { Text("Cancel") } },
        )
    }

    membershipItem?.let { itemId ->
        AlertDialog(
            onDismissRequest = { membershipItem = null },
            title = { Text("Album membership") },
            text = {
                Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("Membership changes affect only album metadata. An item may belong to multiple albums.")
                    when (val organization = state.organizationState) {
                        is VaultOrganizationRead.Ready -> {
                            if (organization.snapshot.albums.isEmpty()) Text("Create an album first.", modifier = Modifier.padding(top = 8.dp))
                            VaultAlbumOrdering.ordered(organization.snapshot.albums).forEach { album ->
                                val member = itemId in album.memberItemIds
                                TextButton(enabled = !state.organizationMutationBusy, onClick = {
                                    if (member) viewModel.removeMembership(album.id, itemId) else viewModel.addMembership(album.id, itemId)
                                    membershipItem = null
                                }) { Text("${if (member) "Remove from" else "Add to"} ${album.name}") }
                            }
                        }
                        null -> Text("Album metadata is still loading.")
                        else -> OrganizationFailure(organization)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { membershipItem = null }) { Text("Done") } },
        )
    }
}

@Composable
private fun SortControls(state: VaultUiState, viewModel: VaultViewModel, openMenu: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = openMenu) { Text("Sort: ${state.itemSort.field.label()}") }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = {
            viewModel.setItemSort(state.itemSort.copy(
                direction = if (state.itemSort.direction == VaultItemSort.Direction.ASCENDING)
                    VaultItemSort.Direction.DESCENDING else VaultItemSort.Direction.ASCENDING,
            ))
        }) { Text(if (state.itemSort.direction == VaultItemSort.Direction.ASCENDING) "Ascending ↑" else "Descending ↓") }
    }
}

@Composable
private fun SearchStatus(state: VaultSearchState) {
    val (message, error) = when (state) {
        is VaultSearchState.EmptyQuery -> "${state.items.size} authenticated items (empty query)." to false
        is VaultSearchState.Matches -> "${state.items.size} search match${if (state.items.size == 1) "" else "es"}." to false
        is VaultSearchState.NoMatches -> "No items match this query." to false
        VaultSearchState.QueryTooLong -> "Search query is invalid or longer than 256 Unicode characters." to true
        VaultSearchState.IndexMissing -> "No authenticated item index is available." to true
        VaultSearchState.IndexUnavailable -> "The authenticated index is unavailable; this is not zero results." to true
        VaultSearchState.IndexUnreadable -> "The authenticated index is unreadable; this is not zero results." to true
        is VaultSearchState.UnsupportedIndex -> "Unsupported index version ${state.version}; search is unavailable." to true
        VaultSearchState.VaultUnavailable -> "The vault key or metadata is unavailable; search is unavailable." to true
    }
    Text(message, modifier = Modifier.padding(vertical = 6.dp),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OrganizationFailure(state: VaultOrganizationRead) {
    val message = when (state) {
        VaultOrganizationRead.Corrupt -> "Album metadata is corrupt or failed authentication. It was not shown as empty."
        is VaultOrganizationRead.UnsupportedVersion -> "Album metadata version ${state.version ?: "unknown"} is unsupported."
        VaultOrganizationRead.Unavailable -> "Album metadata is unavailable; it was not treated as empty."
        VaultOrganizationRead.AccessDenied -> "Android denied access to album metadata."
        VaultOrganizationRead.VaultUnavailable -> "The vault key or metadata is unavailable for albums."
        is VaultOrganizationRead.Ready -> return
    }
    Text(message, modifier = Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun VaultItemRow(
    item: VaultItem,
    state: VaultUiState,
    onOpenItem: (VaultItem) -> Unit,
    onManageMembership: (() -> Unit)?,
    onRemoveMembership: (() -> Unit)?,
    onMoveToTrash: (() -> Unit)? = null,
) {
    val classification = VaultContentClassifier.classify(item)
    val contentMissing = item.id in ((state.indexState as? VaultIndexRead.Ready)?.contentDiagnostics?.missingItemIds.orEmpty())
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { onOpenItem(item) }) {
            Text("${categoryIcon(classification.category)}  ${item.originalFilename}  ·  ${classification.category.name.lowercase()}  ·  ${humanSize(item.originalSizeBytes)}  ·  ${formatImportedAt(item.importedAtEpochMillis)}")
        }
        if (contentMissing) Text("Encrypted content is missing; opening will retain the existing missing-content state.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Row {
            onManageMembership?.let { action ->
                TextButton(enabled = !state.organizationMutationBusy, onClick = action) { Text("Add / remove album membership") }
            }
            onRemoveMembership?.let { action ->
                TextButton(enabled = !state.organizationMutationBusy && !state.trashMutationBusy, onClick = action) { Text("Remove from album") }
            }
            onMoveToTrash?.let { action ->
                TextButton(enabled = !state.trashMutationBusy && !state.importing, onClick = action) { Text("Move to Trash") }
            }
        }
    }
}


private fun trashContentAvailability(diagnostics: VaultContentDiagnostics?, itemId: VaultItemId): Boolean? {
    diagnostics ?: return null
    if (itemId in diagnostics.missingItemIds) return false
    if (diagnostics.missingItemIds.size < diagnostics.missingContent) return null
    return true
}

@Composable
private fun TrashItemRow(
    item: VaultItem,
    contentAvailable: Boolean?,
    mutationBusy: Boolean,
    onRestore: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("In Trash · ${item.originalFilename}", style = MaterialTheme.typography.titleSmall)
        Text("${item.originalMimeType ?: "Unknown type"} · ${humanSize(item.originalSizeBytes)} · Imported ${formatImportedAt(item.importedAtEpochMillis)} · Trashed ${formatImportedAt(item.trashedAtEpochMillis ?: item.importedAtEpochMillis)}")
        val contentMessage = when (contentAvailable) {
            true -> "Encrypted content remains stored under this item ID."
            false -> "Encrypted content is missing; metadata remains in Trash and restore will not recreate content."
            null -> "Content availability could not be checked; Trash metadata remains represented."
        }
        Text(contentMessage,
            color = if (contentAvailable == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !mutationBusy, onClick = onRestore) { Text("Restore") }
    }
}

private fun VaultItemSort.Field.label(): String = when (this) {
    VaultItemSort.Field.DEFAULT -> "Index order"
    VaultItemSort.Field.NAME -> "Name"
    VaultItemSort.Field.SIZE -> "Size"
    VaultItemSort.Field.IMPORT_TIME -> "Import time"
    VaultItemSort.Field.TYPE -> "Type"
    VaultItemSort.Field.TRASH_TIME -> "Trashed time"
}

@Composable
private fun VaultStatusContent(
    state: VaultUiState,
    viewModel: VaultViewModel,
    onRefresh: () -> Unit,
    onInitialize: () -> Unit,
    onInitializeIndex: () -> Unit,
    onConfigureRoot: () -> Unit,
    onChooseSource: () -> Unit,
    onOpenItem: (VaultItem) -> Unit,
) {
    state.message?.let {
        Text(it, modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.primary)
    }
    when (val status = state.status) {
        null -> CircularProgressIndicator()
        VaultStatus.RootNotSelected -> {
            Text("No external vault folder has been selected.")
            Button(modifier = Modifier.padding(top = 12.dp), onClick = onConfigureRoot) { Text("Choose external folder") }
        }
        VaultStatus.NotInitialized -> {
            Text("The selected folder contains no Nivara vault. Initialization creates metadata and an empty data folder there.")
            Button(modifier = Modifier.padding(top = 12.dp), onClick = onInitialize) { Text("Initialize vault here") }
            OutlinedButton(modifier = Modifier.padding(top = 8.dp), onClick = onConfigureRoot) { Text("Reconnect selected folder") }
        }
        is VaultStatus.Ready -> {
            Text("Vault metadata and root structure are authenticated and ready.", color = MaterialTheme.colorScheme.primary)
            when (val index = state.indexState) {
                null -> CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
                VaultIndexRead.Missing -> {
                    Text("No authenticated content index exists yet.", modifier = Modifier.padding(top = 12.dp))
                    Button(modifier = Modifier.padding(top = 8.dp), onClick = onInitializeIndex) {
                        if (state.indexInitializing) CircularProgressIndicator() else Text("Initialize empty index")
                    }
                }
                is VaultIndexRead.Ready -> {
                    Text("${index.items.size} authenticated item${if (index.items.size == 1) "" else "s"}",
                        modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                    if (!state.importing) {
                        Button(modifier = Modifier.padding(top = 8.dp), onClick = onChooseSource) { Text("Import a file") }
                    } else {
                        CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                        Text("Encrypting ${state.progressBytes} bytes${state.progressTotalBytes?.let { " of $it" } ?: ""}…",
                            modifier = Modifier.padding(top = 8.dp))
                        state.progressTotalBytes?.takeIf { it > 0 }?.let { total ->
                            LinearProgressIndicator(
                                progress = { (state.progressBytes.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    index.contentDiagnostics?.let { diagnostics ->
                        Text(
                            "Content checks: ${diagnostics.missingContent} missing, ${diagnostics.unindexedObjects} unindexed, ${diagnostics.unfinishedObjects} unfinished. Nothing was repaired or deleted.",
                            modifier = Modifier.padding(top = 8.dp),
                            color = if (diagnostics.missingContent + diagnostics.unindexedObjects + diagnostics.unfinishedObjects > 0)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } ?: Text("Content diagnostics are unavailable; indexed items remain listed.", modifier = Modifier.padding(top = 8.dp))
                    VaultCollectionContent(
                        state = state,
                        index = index,
                        viewModel = viewModel,
                        onOpenItem = onOpenItem,
                    )
                }
                VaultIndexRead.Corrupt -> Text("The content index is corrupt or failed authentication. It was not treated as empty.",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                is VaultIndexRead.UnsupportedVersion -> Text("Unsupported content index version ${index.version}.",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                VaultIndexRead.Unavailable -> Text("The content index is unavailable; no empty state was assumed.",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                VaultIndexRead.AccessDenied -> Text("Android denied access to the content index.",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                VaultIndexRead.ObjectsWithoutIndex -> Text("Encrypted objects exist without an authenticated index. Counts are unavailable; nothing was reconstructed or deleted.",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                is VaultIndexRead.ContentWithoutIndex -> Text(
                    "No authenticated index exists. ${index.unindexedObjects} unindexed objects and ${index.unfinishedObjects} unfinished objects remain untouched.",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp),
                )
                is VaultIndexRead.VaultUnavailable -> Text("The vault key or metadata is unavailable.",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }
            VaultRecoverySetupContent(state, viewModel)
            OutlinedButton(modifier = Modifier.padding(top = 12.dp), onClick = onRefresh) { Text("Inspect again") }
            OutlinedButton(modifier = Modifier.padding(top = 8.dp), onClick = onConfigureRoot) { Text("Configure or reconnect folder") }
        }
        is VaultStatus.Unavailable -> {
            val message = when (status.reason) {
                com.ashishkumar.nivara.domain.vault.VaultUnavailableReason.EXTERNAL_STORAGE ->
                    "Selected external storage is unavailable. No alternate location was selected."
                com.ashishkumar.nivara.domain.vault.VaultUnavailableReason.DEVICE_KEY_MISSING ->
                    "This installation's local vault key is missing, and this vault has no usable recovery record. Recovery was not configured before access was lost."
                com.ashishkumar.nivara.domain.vault.VaultUnavailableReason.DEVICE_KEY_INVALIDATED ->
                    "This installation's local vault key was invalidated, and this vault has no usable recovery record. Recovery was not configured before access was lost."
                com.ashishkumar.nivara.domain.vault.VaultUnavailableReason.CRYPTOGRAPHIC_SERVICE ->
                    "The local cryptographic service is unavailable. No vault records were changed."
            }
            Text(message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(modifier = Modifier.padding(top = 12.dp), onClick = onConfigureRoot) { Text("Re-select the same folder") }
        }
        VaultStatus.AccessDenied -> {
            Text("Android access to the selected folder is missing or revoked. The vault was not treated as empty.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(modifier = Modifier.padding(top = 12.dp), onClick = onConfigureRoot) { Text("Reconnect the same folder") }
        }
        is VaultStatus.RecoveryRequired -> {
            Text(
                "This existing vault needs its recovery code before it can be reconnected. Recovery verifies the existing vault and content key; it does not recover your primary PIN, password, pattern, biometrics, or app session.",
                color = MaterialTheme.colorScheme.error,
            )
            RecoveryCodeEntry(state, viewModel)
            OutlinedButton(modifier = Modifier.padding(top = 8.dp), onClick = onConfigureRoot) {
                Text("Re-select the original SAF folder")
            }
        }
        VaultStatus.NotAVault -> {
            Text("The selected folder is not an initialized Nivara vault. It will not be initialized or modified.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(modifier = Modifier.padding(top = 12.dp), onClick = onConfigureRoot) { Text("Choose or re-select a folder") }
        }
        VaultStatus.CorruptMetadata -> {
            Text("Vault metadata is unreadable, malformed, or failed authentication. It was not replaced.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(modifier = Modifier.padding(top = 12.dp), onClick = onRefresh) { Text("Recheck") }
        }
        is VaultStatus.UnsupportedVersion -> {
            Text("This vault uses an unsupported ${status.component.displayName()} format${status.version?.let { " version $it" } ?: ""}. No migration was attempted.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(modifier = Modifier.padding(top = 12.dp), onClick = onRefresh) { Text("Recheck") }
        }
        VaultStatus.InvalidStructure -> {
            Text("The selected folder does not have the expected Nivara vault structure. It was not overwritten.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(modifier = Modifier.padding(top = 12.dp), onClick = onRefresh) { Text("Recheck") }
        }
        is VaultStatus.InitializationFailed -> {
            Text("Vault initialization failed (${status.reason}). It was not reported as empty or ready.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(modifier = Modifier.padding(top = 12.dp), onClick = onRefresh) { Text("Inspect again") }
        }
    }
}

@Composable
private fun VaultItemViewerContent(
    vaultId: com.ashishkumar.nivara.domain.vault.VaultId,
    item: VaultItem,
    gateway: VaultContentPresentationGateway,
    sessionManager: SessionManager,
    onBack: () -> Unit,
) {
    val model: VaultItemViewerViewModel = viewModel(
        key = "vault-item-${item.id.value}",
        factory = VaultItemViewerViewModel.Factory(vaultId, item, gateway, sessionManager),
    )
    val state by model.state.collectAsStateWithLifecycle()
    val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(item.id) { model.open() }
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) model.onActivityPaused()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            model.close()
        }
    }
    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedButton(onClick = onBack) { Text("Close viewer") }
        Text(item.originalFilename, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text("${item.originalMimeType ?: "Unknown type"}  ·  ${humanSize(item.originalSizeBytes)}  ·  ${formatImportedAt(item.importedAtEpochMillis)}")
        when (state.openState) {
            VaultItemOpenState.Idle,
            VaultItemOpenState.Authorizing,
            VaultItemOpenState.Opening,
            VaultItemOpenState.Decrypting -> {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                Text("Opening and authenticating encrypted content…")
            }
            VaultItemOpenState.Rendering -> {
                when (state.previewKind) {
                    VaultContentClassification.PreviewKind.IMAGE -> ImagePreview(gateway, state.handle)
                    VaultContentClassification.PreviewKind.TEXT -> TextPreview(gateway, state.handle)
                    VaultContentClassification.PreviewKind.PDF,
                    VaultContentClassification.PreviewKind.AUDIO,
                    VaultContentClassification.PreviewKind.VIDEO,
                    VaultContentClassification.PreviewKind.UNSUPPORTED,
                    null -> UnsupportedContent()
                }
            }
            VaultItemOpenState.Unsupported -> UnsupportedContent()
            VaultItemOpenState.UnsupportedVersion -> ViewerMessage("This encrypted item uses an unsupported format version; no content was presented.")
            VaultItemOpenState.Missing -> ViewerMessage("This indexed item has no encrypted object. It was not removed from the index.")
            VaultItemOpenState.Unreadable -> ViewerMessage("The encrypted object could not be read. Other indexed items remain available.")
            VaultItemOpenState.AuthenticationFailed -> ViewerMessage("Content authentication failed. No content was presented.")
            VaultItemOpenState.CorruptFormat -> ViewerMessage("The authenticated file is malformed or cannot be rendered.")
            VaultItemOpenState.Failed -> ViewerMessage("The viewer could not open this item.")
            VaultItemOpenState.Locked -> ViewerMessage("The Nivara session expired or was locked. Content resources were closed.")
            VaultItemOpenState.Closed -> {
                ViewerMessage("The viewer closed its resources while the app was paused.")
                Button(onClick = model::open, modifier = Modifier.padding(top = 8.dp)) { Text("Reopen") }
            }
        }
        if (sessionState !is SessionState.Authenticated) {
            Text("Return to Nivara Home and use the existing authentication flow before reopening.", modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun ImagePreview(gateway: VaultContentPresentationGateway, handle: com.ashishkumar.nivara.domain.vault.content.VaultPresentationHandle?) {
    var image by remember(handle) { mutableStateOf<ImageBitmap?>(null) }
    var invalid by remember(handle) { mutableStateOf(false) }
    LaunchedEffect(handle) {
        val pixels = handle?.let { gateway.image(it) }
        currentCoroutineContext().ensureActive()
        if (pixels == null) invalid = true
        else {
            var rendered: ImageBitmap? = null
            try {
                rendered = pixels.toImageBitmap()
                currentCoroutineContext().ensureActive()
                image = rendered
                rendered = null
            } catch (failure: CancellationException) {
                rendered?.let(::clearImageBitmap)
                throw failure
            } catch (_: Exception) {
                rendered?.let(::clearImageBitmap)
                invalid = true
            } finally {
                pixels.clear()
            }
        }
    }
    DisposableEffect(image) { onDispose { image?.let(::clearImageBitmap) } }
    if (invalid) ViewerMessage("The image could not be decoded safely.")
    else image?.let { Image(it, contentDescription = "Authenticated vault image", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) }
        ?: CircularProgressIndicator(modifier = Modifier.padding(16.dp))
}

@Composable
private fun TextPreview(gateway: VaultContentPresentationGateway, handle: com.ashishkumar.nivara.domain.vault.content.VaultPresentationHandle?) {
    var text by remember(handle) { mutableStateOf<String?>(null) }
    var invalid by remember(handle) { mutableStateOf(false) }
    LaunchedEffect(handle) {
        val loadedText = handle?.let { gateway.text(it) }
        currentCoroutineContext().ensureActive()
        text = loadedText
        if (loadedText == null) invalid = true
    }
    if (invalid) ViewerMessage("The text preview is unavailable or malformed.")
    else text?.let { Text(it, modifier = Modifier.fillMaxWidth().padding(12.dp)) }
        ?: CircularProgressIndicator(modifier = Modifier.padding(16.dp))
}

@Composable
private fun UnsupportedContent() {
    Text("Preview is not supported for this file type. The authenticated item remains in the vault.", modifier = Modifier.padding(12.dp))
}

@Composable
private fun ViewerMessage(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
}

private fun clearImageBitmap(image: ImageBitmap) {
    runCatching {
        val bitmap = image.asAndroidBitmap()
        bitmap.eraseColor(0)
        bitmap.recycle()
    }
}

private fun VaultImagePixels.toImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

private fun categoryIcon(category: VaultContentCategory): String = when (category) {
    VaultContentCategory.IMAGE -> "▧"
    VaultContentCategory.VIDEO -> "▶"
    VaultContentCategory.AUDIO -> "♫"
    VaultContentCategory.DOCUMENT -> "▤"
    VaultContentCategory.OTHER -> "▪"
}

private fun humanSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024L * 1024 -> "%.1f KiB".format(size / 1024.0)
    size < 1024L * 1024 * 1024 -> "%.1f MiB".format(size / (1024.0 * 1024))
    else -> "%.2f GiB".format(size / (1024.0 * 1024 * 1024))
}

private fun formatImportedAt(epochMillis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(epochMillis))



private fun VaultFormatComponent.displayName(): String = when (this) {
    VaultFormatComponent.METADATA -> "metadata"
    VaultFormatComponent.KEY_ENVELOPE -> "key envelope"
    VaultFormatComponent.ENCRYPTED_HEADER -> "encrypted header"
    VaultFormatComponent.RECOVERY -> "recovery envelope"
}
