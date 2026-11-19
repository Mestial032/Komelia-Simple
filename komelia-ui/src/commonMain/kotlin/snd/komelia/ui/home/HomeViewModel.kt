package snd.komelia.ui.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.Eagerly
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import snd.komelia.AppNotifications
import snd.komelia.homefilters.BooksHomeScreenFilter
import snd.komelia.homefilters.HomeScreenFilter
import snd.komelia.homefilters.HomeScreenFilterRepository
import snd.komelia.homefilters.SeriesHomeScreenFilter
import snd.komelia.komga.api.KomgaBookApi
import snd.komelia.komga.api.KomgaSeriesApi
import snd.komelia.offline.tasks.OfflineTaskEmitter
import snd.komelia.ui.LoadState
import snd.komelia.ui.LoadState.Uninitialized
import snd.komelia.ui.common.cards.defaultCardWidth
import snd.komelia.ui.common.menus.BookMenuActions
import snd.komelia.ui.common.menus.SeriesMenuActions
import snd.komelia.ui.home.edit.getDefaultFilters
import snd.komga.client.book.KomgaBookSearch
import snd.komga.client.book.KomgaReadStatus
import snd.komga.client.search.KomgaSearchCondition
import snd.komga.client.search.KomgaSearchOperator
import snd.komga.client.common.KomgaPageRequest
import snd.komga.client.common.KomgaSort
import snd.komga.client.series.KomgaSeriesSearch
import snd.komga.client.sse.KomgaEvent
import snd.komga.client.sse.KomgaEvent.BookEvent
import snd.komga.client.sse.KomgaEvent.ReadProgressEvent
import snd.komga.client.sse.KomgaEvent.ReadProgressSeriesEvent
import snd.komga.client.sse.KomgaEvent.SeriesEvent

private val logger = KotlinLogging.logger { }

private const val DEFAULT_PAGE_SIZE = 20
private const val GROUP_BY_SERIES_FETCH_MULTIPLIER = 5

private val createdDateSortProperty = KomgaSort.KomgaBooksSort.byCreatedDateDesc().orders.first().property

private fun KomgaSearchCondition.BookCondition.hasReadStatus(status: KomgaReadStatus): Boolean = when (this) {
    is KomgaSearchCondition.ReadStatus -> {
        val op = operator
        op is KomgaSearchOperator.Is && op.value == status
    }

    is KomgaSearchCondition.AllOfBook -> conditions.any { it.hasReadStatus(status) }
    is KomgaSearchCondition.AnyOfBook -> conditions.isNotEmpty() && conditions.all { it.hasReadStatus(status) }
    else -> false
}

/**
 * True for filters that list books with IN_PROGRESS read status (e.g. the default "Keep reading" filter).
 */
internal fun BooksHomeScreenFilter.isInProgressFilter(): Boolean =
    this is BooksHomeScreenFilter.CustomFilter && filter?.hasReadStatus(KomgaReadStatus.IN_PROGRESS) == true

/**
 * True when the filter should show only one book per series:
 * - books with IN_PROGRESS read status (default "Keep reading")
 * - books with READ read status (default "Recently read books")
 * - books sorted by most recently added first
 */
private fun BooksHomeScreenFilter.CustomFilter.shouldGroupBySeries(): Boolean {
    val condition = filter
    if (condition != null &&
        (condition.hasReadStatus(KomgaReadStatus.IN_PROGRESS) || condition.hasReadStatus(KomgaReadStatus.READ))
    ) return true

    val firstOrder = pageRequest?.sort?.orders?.firstOrNull() ?: return false
    return firstOrder.property == createdDateSortProperty && firstOrder.direction == KomgaSort.Direction.DESC
}

class HomeViewModel(
    private val seriesApi: KomgaSeriesApi,
    private val bookApi: KomgaBookApi,
    private val appNotifications: AppNotifications,
    private val komgaEvents: SharedFlow<KomgaEvent>,
    private val filterRepository: HomeScreenFilterRepository,
    private val taskEmitter: OfflineTaskEmitter?,
    cardWidthFlow: Flow<Dp>,
) : StateScreenModel<LoadState<Unit>>(Uninitialized) {
    val cardWidth = cardWidthFlow.stateIn(screenModelScope, Eagerly, defaultCardWidth.dp)

    private val reloadEventsEnabled = MutableStateFlow(true)
    private val reloadJobsFlow = MutableSharedFlow<Unit>(1, 0, DROP_OLDEST)

    val currentFilters = MutableStateFlow(emptyList<HomeFilterData>())

    suspend fun initialize() {
        if (state.value !is Uninitialized) return

        load()
        startKomgaEventListener()

        reloadJobsFlow.onEach {
            reloadEventsEnabled.first { it }
            load()
            delay(5000)
        }.launchIn(screenModelScope)
    }

    fun reload() {
        screenModelScope.launch { load() }
    }

    private suspend fun load() {
        appNotifications.runCatchingToNotifications {
            mutableState.value = LoadState.Loading

            currentFilters.value = keepReadingFilters()
                .map { screenModelScope.async { fetchFilterData(it) } }
                .awaitAll()
                .filterNotNull()

            mutableState.value = LoadState.Success(Unit)
        }.onFailure { mutableState.value = LoadState.Error(it) }
    }

    /**
     * Home only shows what the user is currently reading: the in-progress books filter(s) from the saved filters,
     * or the default "Keep reading" filter if none is saved.
     */
    private suspend fun keepReadingFilters(): List<HomeScreenFilter> {
        fun List<HomeScreenFilter>.inProgressOnly() =
            filter { it is BooksHomeScreenFilter && it.isInProgressFilter() }

        return filterRepository.getFilters().first().inProgressOnly()
            .ifEmpty { getDefaultFilters().inProgressOnly() }
    }

    private suspend fun fetchFilterData(filter: HomeScreenFilter): HomeFilterData? {
        return when (filter) {
            is BooksHomeScreenFilter.CustomFilter -> {
                if (filter.shouldGroupBySeries()) {
                    // show only the first book of each series according to the filter's sort order
                    // (e.g. the most recently read or the most recently added book)
                    val requestedSize = filter.pageRequest?.size ?: DEFAULT_PAGE_SIZE
                    val pageRequest = (filter.pageRequest ?: KomgaPageRequest())
                        .copy(size = requestedSize * GROUP_BY_SERIES_FETCH_MULTIPLIER)
                    val books = bookApi.getBookList(
                        search = KomgaBookSearch(filter.filter, filter.textSearch),
                        pageRequest = pageRequest
                    ).content
                        .distinctBy { it.seriesId }
                        .take(requestedSize)

                    BookFilterData(books = books, filter = filter)
                } else {
                    val books = bookApi.getBookList(
                        search = KomgaBookSearch(filter.filter, filter.textSearch),
                        pageRequest = filter.pageRequest
                    ).content

                    BookFilterData(books = books, filter = filter)
                }
            }

            is BooksHomeScreenFilter.OnDeck -> {
                val books = bookApi.getBooksOnDeck(pageRequest = KomgaPageRequest(size = filter.pageSize)).content
                BookFilterData(books, filter)
            }

            is SeriesHomeScreenFilter.CustomFilter -> {
                val series = seriesApi.getSeriesList(
                    search = KomgaSeriesSearch(filter.filter, filter.textSearch),
                    pageRequest = filter.pageRequest
                ).content

                SeriesFilterData(series = series, filter = filter)
            }

            is SeriesHomeScreenFilter.RecentlyAdded -> {
                val series = seriesApi.getNewSeries(
                    oneshot = false,
                    pageRequest = KomgaPageRequest(size = filter.pageSize)
                ).content
                SeriesFilterData(
                    series = series,
                    filter = filter
                )
            }

            is SeriesHomeScreenFilter.RecentlyUpdated -> {
                val series = seriesApi.getUpdatedSeries(
                    oneshot = false,
                    pageRequest = KomgaPageRequest(size = filter.pageSize)
                ).content
                SeriesFilterData(
                    series = series,
                    filter = filter
                )
            }
        }

    }

    fun seriesMenuActions() = SeriesMenuActions(seriesApi, appNotifications, taskEmitter, screenModelScope)
    fun bookMenuActions() = BookMenuActions(bookApi, appNotifications, screenModelScope, taskEmitter)

    fun stopKomgaEventsHandler() {
        reloadEventsEnabled.value = false
    }

    fun startKomgaEventsHandler() {
        reloadEventsEnabled.value = true
    }

    private fun startKomgaEventListener() {
        komgaEvents.onEach { event ->
            when (event) {
                is BookEvent,
                is SeriesEvent,
                is ReadProgressEvent,
                is ReadProgressSeriesEvent -> reloadJobsFlow.tryEmit(Unit)

                else -> {}
            }
        }.launchIn(screenModelScope)
    }


}
