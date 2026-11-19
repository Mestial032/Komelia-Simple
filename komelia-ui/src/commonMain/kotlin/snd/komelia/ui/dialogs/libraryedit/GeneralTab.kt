package snd.komelia.ui.dialogs.libraryedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.snd_r.komelia.ui.komelia_ui.generated.resources.Res
import io.github.snd_r.komelia.ui.komelia_ui.generated.resources.edit_tab_general
import io.github.snd_r.komelia.ui.komelia_ui.generated.resources.library_edit_add_folder
import io.github.snd_r.komelia.ui.komelia_ui.generated.resources.library_edit_browse
import io.github.snd_r.komelia.ui.komelia_ui.generated.resources.library_edit_name
import io.github.snd_r.komelia.ui.komelia_ui.generated.resources.library_edit_root_folder
import org.jetbrains.compose.resources.stringResource
import snd.komelia.ui.dialogs.filebrowser.FileBrowserDialogContent
import snd.komelia.ui.dialogs.tabs.DialogTab
import snd.komelia.ui.dialogs.tabs.TabItem

class GeneralTab(
    private val vm: LibraryEditDialogViewModel,
) : DialogTab {
    override fun options() = TabItem(
        title = Res.string.edit_tab_general,
        icon = Icons.Default.Category
    )

    @Composable
    override fun Content() {
        GeneralTabContent(
            folders = vm.folders,
            onNameChange = vm::setFolderName,
            onRootFolderChange = vm::setRootFolder,
            onFolderAdd = vm::addFolder,
            onFolderRemove = vm::removeFolder,
        )
    }
}

// every folder (library) of the user, each with its name and root folder
@Composable
private fun GeneralTabContent(
    folders: List<LibraryFolderState>,
    onNameChange: (LibraryFolderState, String) -> Unit,
    onRootFolderChange: (LibraryFolderState, String) -> Unit,
    onFolderAdd: () -> Unit,
    onFolderRemove: (LibraryFolderState) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        folders.forEachIndexed { index, folder ->
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp))
            FolderEntry(
                folder = folder,
                // only folders that don't exist in Komga yet can be removed from here
                canRemove = folder.library == null && index > 0,
                onNameChange = { onNameChange(folder, it) },
                onRootFolderChange = { onRootFolderChange(folder, it) },
                onRemove = { onFolderRemove(folder) },
            )
        }

        TextButton(onClick = onFolderAdd) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(stringResource(Res.string.library_edit_add_folder))
        }
    }
}

@Composable
private fun FolderEntry(
    folder: LibraryFolderState,
    canRemove: Boolean,
    onNameChange: (String) -> Unit,
    onRootFolderChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var showFileBrowserDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = folder.name,
                onValueChange = onNameChange,
                label = { Text(stringResource(Res.string.library_edit_name)) },
                isError = folder.nameError != null,
                supportingText = { folder.nameError?.let { Text(it) } },
                modifier = Modifier.weight(1f)
            )
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = null)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = folder.rootFolder,
                onValueChange = onRootFolderChange,
                label = { Text(stringResource(Res.string.library_edit_root_folder)) },
                isError = folder.rootFolderError != null,
                supportingText = { folder.rootFolderError?.let { Text(it) } },
                modifier = Modifier.weight(7f)
            )

            FilledTonalButton(
                onClick = { showFileBrowserDialog = true },
                modifier = Modifier.padding(horizontal = 10.dp),
            ) {
                Text(stringResource(Res.string.library_edit_browse))
            }
        }
    }

    if (showFileBrowserDialog) {
        FileBrowserDialogContent(
            onDismissRequest = { showFileBrowserDialog = false },
            onDirectoryChoice = onRootFolderChange
        )
    }
}
