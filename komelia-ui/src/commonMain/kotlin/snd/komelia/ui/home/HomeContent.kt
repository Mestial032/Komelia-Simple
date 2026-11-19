package snd.komelia.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import snd.komelia.komga.api.model.KomeliaBook
import snd.komelia.komga.api.model.KomeliaSeries
import snd.komelia.ui.common.cards.BookImageCard
import snd.komelia.ui.common.cards.SeriesImageCard
import snd.komelia.ui.common.menus.BookMenuActions
import snd.komelia.ui.common.menus.SeriesMenuActions

@Composable
fun HomeContent(
    filters: List<HomeFilterData>,
    cardWidth: Dp,
    onSeriesClick: (KomeliaSeries) -> Unit,
    seriesMenuActions: SeriesMenuActions,
    bookMenuActions: BookMenuActions,
    onBookClick: (KomeliaBook) -> Unit,
    onBookReadClick: (KomeliaBook, Boolean) -> Unit,
) {
    val gridState = rememberLazyGridState()
    // Home only shows what is being read, so there is no filter selection bar on top
    Column {
        DisplayContent(
            filters = filters,
            gridState = gridState,
            cardWidth = cardWidth,
            onSeriesClick = onSeriesClick,
            seriesMenuActions = seriesMenuActions,
            bookMenuActions = bookMenuActions,
            onBookClick = onBookClick,
            onBookReadClick = onBookReadClick,
        )
    }
}

@Composable
private fun DisplayContent(
    filters: List<HomeFilterData>,
    gridState: LazyGridState,
    cardWidth: Dp,
    onSeriesClick: (KomeliaSeries) -> Unit,
    seriesMenuActions: SeriesMenuActions,
    bookMenuActions: BookMenuActions,
    onBookClick: (KomeliaBook) -> Unit,
    onBookReadClick: (KomeliaBook, Boolean) -> Unit,
) {
    LazyVerticalGrid(
        modifier = Modifier.padding(horizontal = 20.dp),
        state = gridState,
        columns = GridCells.Adaptive(cardWidth),
        horizontalArrangement = Arrangement.spacedBy(15.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
        contentPadding = PaddingValues(bottom = 50.dp)
    ) {
        for (data in filters) {
            when (data) {
                is BookFilterData -> BookFilterEntry(
                    label = data.filter.label,
                    books = data.books,
                    bookMenuActions = bookMenuActions,
                    // "Keep reading": clicking the card resumes reading instead of opening book details
                    onBookClick = if (data.filter.isInProgressFilter()) {
                        { book -> onBookReadClick(book, true) }
                    } else onBookClick,
                    onBookReadClick = onBookReadClick,
                )

                is SeriesFilterData -> SeriesFilterEntries(
                    label = data.filter.label,
                    series = data.series,
                    onSeriesClick = onSeriesClick,
                    seriesMenuActions = seriesMenuActions,
                )
            }
        }
    }
}

private fun LazyGridScope.BookFilterEntry(
    label: String,
    books: List<KomeliaBook>,
    bookMenuActions: BookMenuActions,
    onBookClick: (KomeliaBook) -> Unit,
    onBookReadClick: (KomeliaBook, Boolean) -> Unit,
) {
    if (books.isEmpty()) return

    item(span = { GridItemSpan(maxLineSpan) }) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            HorizontalDivider()
        }
    }
    items(books) { book ->
        BookImageCard(
            book = book,
            onBookClick = { onBookClick(book) },
            onBookReadClick = { onBookReadClick(book, it) },
            bookMenuActions = bookMenuActions,
            showSeriesTitle = true,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun LazyGridScope.SeriesFilterEntries(
    label: String,
    series: List<KomeliaSeries>,
    onSeriesClick: (KomeliaSeries) -> Unit,
    seriesMenuActions: SeriesMenuActions,
) {
    if (series.isEmpty()) return
    item(span = { GridItemSpan(maxLineSpan) }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            HorizontalDivider()
        }
    }

    items(series) {
        SeriesImageCard(
            series = it,
            onSeriesClick = { onSeriesClick(it) },
            seriesMenuActions = seriesMenuActions,
            modifier = Modifier.fillMaxSize()
        )
    }
}
