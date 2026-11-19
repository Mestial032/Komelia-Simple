package snd.komelia.ui.dialogs.libraryedit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import snd.komelia.AppNotifications
import snd.komelia.komga.api.KomgaLibraryApi
import snd.komelia.ui.dialogs.tabs.DialogTab
import snd.komga.client.common.PatchValue
import snd.komga.client.library.KomgaLibrary
import snd.komga.client.library.KomgaLibraryCreateRequest
import snd.komga.client.library.KomgaLibraryId
import snd.komga.client.library.KomgaLibraryUpdateRequest
import snd.komga.client.library.ScanInterval
import snd.komga.client.library.SeriesCover

private const val DEFAULT_LIBRARY_NAME = "Library"

/**
 * One folder (Komga library) shown in the General tab: its name and root folder.
 * [library] is null for a folder added in this dialog that doesn't exist in Komga yet.
 */
class LibraryFolderState(val library: KomgaLibrary?) {
    var name by mutableStateOf(library?.name ?: "")
    var nameError by mutableStateOf<String?>(null)
    var rootFolder by mutableStateOf(library?.root ?: "")
    var rootFolderError by mutableStateOf<String?>(null)

    // a new folder is named after its directory until the user types a name
    var nameEditedByUser = library != null

    val isChanged get() = library == null || library.name != name || library.root != rootFolder
}

/**
 * @param library library whose settings are edited (the Scanner/Options/Metadata tabs), null to add a new one
 * @param allLibraries every library the user has; their names and folders are listed in the General tab
 * @param foldersOnly only show the General tab (names and folders of every library), used when no single library
 * is selected
 */
class LibraryEditDialogViewModel(
    val library: KomgaLibrary?,
    val onDialogDismiss: () -> Unit,
    private val libraryApi: KomgaLibraryApi,
    private val appNotifications: AppNotifications,
    allLibraries: List<KomgaLibrary>,
    val foldersOnly: Boolean = false,
) {

    // the library being edited (or added) goes first, then the rest of the user's libraries
    val folders = mutableStateListOf<LibraryFolderState>().apply {
        if (!foldersOnly) add(LibraryFolderState(library))
        allLibraries.filter { it.id != library?.id }.forEach { add(LibraryFolderState(it)) }
        if (isEmpty()) add(LibraryFolderState(null))
    }

    // folder whose settings are edited in the Scanner/Options/Metadata tabs
    private val mainFolder: LibraryFolderState? = if (foldersOnly) null else folders.first()

    // new libraries clean up removed files automatically after every scan (like Plex's "empty trash after scan")
    var emptyTrashAfterScan by mutableStateOf(library?.emptyTrashAfterScan ?: true)
    var scanForceModifiedTime by mutableStateOf(library?.scanForceModifiedTime ?: false)
    var scanOnStartup by mutableStateOf(library?.scanOnStartup ?: false)
    var oneshotsDirectory by mutableStateOf(library?.oneshotsDirectory ?: "")
    var scanCbx by mutableStateOf(library?.scanCbx ?: true)
    var scanEpub by mutableStateOf(library?.scanEpub ?: true)
    var scanPdf by mutableStateOf(library?.scanPdf ?: true)
    var scanInterval by mutableStateOf(library?.scanInterval ?: ScanInterval.EVERY_6H)
    var scanDirectoryExclusions by mutableStateOf(
        library?.scanDirectoryExclusions ?: listOf(
            "#recycle",
            "@eaDir",
            "@Recycle"
        )
    )

    var hashFiles by mutableStateOf(library?.hashFiles ?: true)
    var hashPages by mutableStateOf(library?.hashPages ?: false)
    var analyzeDimensions by mutableStateOf(library?.analyzeDimensions ?: true)
    var repairExtensions by mutableStateOf(library?.repairExtensions ?: false)
    var convertToCbz by mutableStateOf(library?.convertToCbz ?: false)
    var seriesCover by mutableStateOf(library?.seriesCover ?: SeriesCover.FIRST)


    var importComicInfoBook by mutableStateOf(library?.importComicInfoBook ?: true)
    var importComicInfoSeries by mutableStateOf(library?.importComicInfoSeries ?: true)
    var importComicInfoSeriesAppendVolume by mutableStateOf(library?.importComicInfoSeriesAppendVolume ?: true)
    var importComicInfoCollection by mutableStateOf(library?.importComicInfoCollection ?: true)
    var importComicInfoReadList by mutableStateOf(library?.importComicInfoReadList ?: true)
    var importEpubBook by mutableStateOf(library?.importEpubBook ?: true)
    var importEpubSeries by mutableStateOf(library?.importEpubSeries ?: true)
    var importMylarSeries by mutableStateOf(library?.importMylarSeries ?: true)
    var importLocalArtwork by mutableStateOf(library?.importLocalArtwork ?: true)
    var importBarcodeIsbn by mutableStateOf(library?.importBarcodeIsbn ?: false)


    private val generalTab = GeneralTab(this)
    private val scannerTab = ScannerTab(this)
    private val optionsTab = OptionsTab(this)
    private val metadataTab = MetadataTab(this)

    var currentTab by mutableStateOf<DialogTab>(generalTab)

    fun tabs(): List<DialogTab> =
        if (foldersOnly) listOf(generalTab)
        else listOf(generalTab, scannerTab, optionsTab, metadataTab)

    suspend fun onNextTabSwitch() {
        if (foldersOnly || currentTab == metadataTab || library != null) {
            onConfirmEdit()
        } else {
            val tabs = tabs()
            val currentIndex = tabs.indexOf(currentTab)
            currentTab = tabs[currentIndex + 1]
        }
    }

    fun setFolderName(folder: LibraryFolderState, name: String) {
        folder.name = name
        folder.nameEditedByUser = true
        folder.nameError = if (name.isBlank()) "Required" else null
    }

    fun setRootFolder(folder: LibraryFolderState, path: String) {
        folder.rootFolderError =
            if (path.isBlank()) "Required"
            else null

        folder.rootFolder = path
        if (!folder.nameEditedByUser) folder.name = libraryNameFromFolder(path)
    }

    fun addFolder() {
        folders.add(LibraryFolderState(null))
    }

    // only folders that don't exist in Komga yet can be removed here; libraries are deleted from their own menu
    fun removeFolder(folder: LibraryFolderState) {
        if (folder.library == null && folder != mainFolder) folders.remove(folder)
    }

    private fun stateIsValid(): Boolean {
        var valid = true
        folders.forEach { folder ->
            folder.rootFolderError = if (folder.rootFolder.isBlank()) "Required" else null
            folder.nameError = when {
                folder.name.isBlank() -> "Required"
                // Komga doesn't allow two libraries with the same name
                folders.count { it.name.trim().equals(folder.name.trim(), ignoreCase = true) } > 1 -> "Name already used"
                else -> null
            }
            if (folder.rootFolderError != null || folder.nameError != null) valid = false
        }
        return valid
    }

    private fun libraryNameFromFolder(path: String): String {
        val folderName = path.trimEnd('/', '\\')
            .substringAfterLast('/')
            .substringAfterLast('\\')
        // a drive root such as "D:\" has no folder name
        return if (folderName.isBlank() || folderName.endsWith(':')) DEFAULT_LIBRARY_NAME else folderName
    }


    suspend fun onConfirmEdit() {
        if (!stateIsValid()) {
            currentTab = generalTab
            return
        }

        appNotifications.runCatchingToNotifications {
            folders.forEach { folder ->
                val existing = folder.library
                when {
                    // the edited library also saves the settings of the other tabs
                    folder == mainFolder && existing != null -> editLibrary(existing.id, folder)
                    existing != null -> {
                        if (folder.isChanged) libraryApi.patchOne(
                            existing.id,
                            KomgaLibraryUpdateRequest(name = folder.name, root = folder.rootFolder)
                        )
                    }
                    // new folders get the settings of the dialog (defaults, or those of the edited library)
                    else -> addLibrary(folder)
                }
            }

            onDialogDismiss()
        }
    }

    private suspend fun addLibrary(folder: LibraryFolderState) {
        libraryApi.addOne(
            KomgaLibraryCreateRequest(
                name = folder.name,
                root = folder.rootFolder,
                importComicInfoBook = importComicInfoBook,
                importComicInfoSeries = importComicInfoSeries,
                importComicInfoSeriesAppendVolume = importComicInfoSeriesAppendVolume,
                importComicInfoCollection = importComicInfoCollection,
                importComicInfoReadList = importComicInfoReadList,
                importEpubBook = importEpubBook,
                importEpubSeries = importEpubSeries,
                importMylarSeries = importMylarSeries,
                importLocalArtwork = importLocalArtwork,
                importBarcodeIsbn = importBarcodeIsbn,
                scanForceModifiedTime = scanForceModifiedTime,
                repairExtensions = repairExtensions,
                convertToCbz = convertToCbz,
                emptyTrashAfterScan = emptyTrashAfterScan,
                seriesCover = seriesCover,
                hashFiles = hashFiles,
                hashPages = hashPages,
                analyzeDimensions = analyzeDimensions,
                scanOnStartup = scanOnStartup,
                oneshotsDirectory = oneshotsDirectory,
                scanCbx = scanCbx,
                scanEpub = scanEpub,
                scanPdf = scanPdf,
                scanInterval = scanInterval,
                scanDirectoryExclusions = scanDirectoryExclusions,
            )
        )
    }

    private suspend fun editLibrary(libraryId: KomgaLibraryId, folder: LibraryFolderState) {
        libraryApi.patchOne(
            libraryId, KomgaLibraryUpdateRequest(
                name = folder.name,
                root = folder.rootFolder,
                importComicInfoBook = importComicInfoBook,
                importComicInfoSeries = importComicInfoSeries,
                importComicInfoSeriesAppendVolume = importComicInfoSeriesAppendVolume,
                importComicInfoCollection = importComicInfoCollection,
                importComicInfoReadList = importComicInfoReadList,
                importEpubBook = importEpubBook,
                importEpubSeries = importEpubSeries,
                importMylarSeries = importMylarSeries,
                importLocalArtwork = importLocalArtwork,
                importBarcodeIsbn = importBarcodeIsbn,
                scanForceModifiedTime = scanForceModifiedTime,
                repairExtensions = repairExtensions,
                convertToCbz = convertToCbz,
                emptyTrashAfterScan = emptyTrashAfterScan,
                seriesCover = seriesCover,
                hashFiles = hashFiles,
                hashPages = hashPages,
                analyzeDimensions = analyzeDimensions,
                scanOnStartup = scanOnStartup,
                scanCbx = scanCbx,
                scanEpub = scanEpub,
                scanPdf = scanPdf,
                scanInterval = scanInterval,
                oneshotsDirectory = PatchValue.Some(oneshotsDirectory),
                scanDirectoryExclusions = PatchValue.Some(scanDirectoryExclusions)
            )
        )
    }
}
