package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import edu.ucsd.sysnet.cluetoothscanner.api.core.Identification
import edu.ucsd.sysnet.cluetoothscanner.api.core.ProductDetail
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Concentric
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Format
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.CategoryIcon
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Checkbox
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Icon
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.IconButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.ListItem
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.SectionLabel
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Segmented
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Switch
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.Text
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.TextButton
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.plainClickable
import edu.ucsd.sysnet.cluetoothscanner.designsystem.sp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyphs
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

internal object PushedTags {
    const val CLOSE = "sheet-close"
    const val TITLE = "sheet-title"
    const val WIKI = "product-wiki"
    fun category(id: String) = "filter-category-$id"
    fun preset(name: String) = "filter-preset-$name"
    const val CLEAR = "filter-clear"
}

/** S-3, S-2: a pushed sheet's header: [content] (a title) and the X on the corner arc. */
@Composable
internal fun PushedHeader(state: SheetsState, content: @Composable () -> Unit) {
    val inset = Concentric.cornerButton(state.stack.tablet)
    Box(Modifier.fillMaxWidth().padding(end = inset)) {
        Box(Modifier.fillMaxWidth().padding(end = Tokens.iconBtn)) { content() }
        IconButton(
            Glyphs.close, Strings.Close,
            onClick = { state.stack.pop(PopReason.Close) },
            modifier = Modifier.align(Alignment.TopEnd).testTag(PushedTags.CLOSE),
        )
    }
}

/** S-3: a title on the header line (`.sheet-kicker`). */
@Composable
internal fun Kicker(text: String) {
    Box(Modifier.height(Css.SheetKicker.height).padding(horizontal = Css.SheetKicker.padding.left), contentAlignment = Alignment.CenterStart) {
        Text(
            text,
            Modifier.testTag(PushedTags.TITLE),
            style = CluetoothTheme.type.title.copy(fontSize = Css.SheetKicker.fontSize.sp, fontWeight = FontWeight(Css.SheetKicker.fontWeight.toInt())),
            maxLines = 1,
        )
    }
}

// ---- Product sheet (D-6..D-8) ----

@Composable
internal fun ProductSheet(state: SheetsState, entry: SheetEntry, pageId: String?) {
    var content by remember { mutableStateOf(ProductContent()) }
    val filter = state.productFilter(pageId)
    LaunchedEffect(filter) {
        val detail = state.load(SheetQuery.Product(pageId, filter))
        // D-6: the category's display label (never its id), learned if this view has not seen it yet
        val title = when (val p = (detail as? Outcome.Ok)?.value) {
            null -> ""
            else -> if (p.page == null) Strings.Unidentified else p.categories.firstOrNull()?.let { state.labelOf(it, pageId) }.orEmpty()
        }
        content = content.withDetail(detail).withTitle(title)
    }
    // D-7.6: sorted once by RSSI (the session order), filtered by This area; G-9: live devices of the product
    // are appended as they arrive (the order only grows)
    LaunchedEffect(filter, state.discover.liveAppends, state.opened) { content = content.withKeys(state.discover.keysFor(filter)) }
    val tablet = state.stack.tablet
    val detail = content.detail
    SheetFrame(state.stack, entry, state.metrics, header = {
        // D-6, G-11: the category, not the product name the body shows
        PushedHeader(state) { Kicker(content.title) }
    }) {
        when (detail) {
            Loaded.Pending, Loaded.Missing -> Unit
            is Loaded.Failed -> edu.ucsd.sysnet.cluetoothscanner.designsystem.components.EmptyText(detail.message)
            is Loaded.Found -> DeviceList(state, entry, content.keys, SheetEntry.MAIN_LIST, empty = {
                edu.ucsd.sysnet.cluetoothscanner.designsystem.components.EmptyText(Strings.NoMatchingDevices)
            }, header = { ProductHead(state, detail.value, tablet) })
        }
    }
}

@Composable
private fun ProductHead(state: SheetsState, p: ProductDetail, tablet: Boolean) {
    val c = CluetoothTheme.colors
    val t = CluetoothTheme.type
    Column {
        // D-7.1: the hero, structured so a product image can replace the icon later
        Box(
            Modifier
                .padding(horizontal = Tokens.edge)
                .fillMaxWidth()
                .height(Css.Hero.height)
                .clip(RoundedCornerShape(Concentric.hero(tablet)))
                .background(c.fill2),
            contentAlignment = Alignment.Center,
        ) {
            CategoryIcon(p.categories.firstOrNull(), p.icon, size = Css.Hero_Icon.width, tint = c.t2, strokeWidth = Css.Hero_Icon_Svg.strokeWidth)
        }
        Column(Modifier.padding(start = Css.ProdBody.padding.left, top = Css.ProdBody.padding.top, end = Css.ProdBody.padding.right)) {
            Text(if (p.page == null) Strings.UnidentifiedDevices else p.title, style = t.head) // D-7.2: never truncated
            val description = if (p.page == null) Strings.UnidentifiedDescription else p.oneLine
            if (description != null) Text(description, Modifier.padding(top = Css.ProdDesc.margin.top), color = c.t2)
            Text(
                Strings.seenAt(p.places, p.lastSeenMs?.let { Format.ago(it, state.nowMs()) } ?: ""),
                Modifier.padding(top = Css.ProdMeta.margin.top, bottom = Css.ProdMeta.margin.bottom),
                style = t.sub, color = c.t2,
            )
            p.wikiUrl?.let { WikiLink(Strings.OpenInWiki, it, Modifier.padding(bottom = Css.ProdMeta.margin.bottom)) }
        }
        SectionLabel(Strings.Devices)
    }
}

/** D-7.5, D-9: a `--blue` link with ↗ that opens the wiki page in the external browser. */
@Composable
internal fun WikiLink(label: String, url: String, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val c = CluetoothTheme.colors
    Row(
        modifier.testTag(PushedTags.WIKI).plainClickable(remember { MutableInteractionSource() }) { uri.openUri(url) },
        horizontalArrangement = Arrangement.spacedBy(Css.DevProduct.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = CluetoothTheme.type.sub, color = c.blue)
        Icon(Glyphs.external, null, tint = c.blue, size = Css.DevProduct_Icon.width)
    }
}

// ---- Filter sheet (D-3a) ----

@Composable
internal fun FilterSheet(state: SheetsState, entry: SheetEntry) {
    val d = state.discover
    var content by remember { mutableStateOf<FilterRows>(FilterRows.Pending) }
    // faceted counts: every constraint but the categories facet, with Nearby and This area (D-3a)
    val countsFilter = d.queryFilter()
    LaunchedEffect(countsFilter) {
        when (val counts = state.load(SheetQuery.Counts(countsFilter))) {
            is Outcome.Ok -> {
                state.learnLabels(counts.value.map { it.category to it.label })
                content = content.withCounts(counts.value, d.filter.categories) { state.categoryLabels[it] ?: it }
            }
            is Outcome.Failed -> Unit // the rows keep their last counts (or none yet); the next filter change asks again
        }
    }
    SheetFrame(state.stack, entry, state.metrics, header = { PushedHeader(state) { Kicker(Strings.Filter) } }) {
        val f = d.filter
        val set = { next: edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter -> state.setFilter(next) }
        val list = rememberSheetListState(entry)
        LazyColumn(state = list) {
            item {
                PresetRow(Strings.Readable, f.readable) { set(f.copy(readable = it)) }
                PresetRow(Strings.Trackers, f.trackers) { set(f.copy(trackers = it)) }
                PresetRow(Strings.Vulnerable, f.vulnerable) { set(f.copy(vulnerable = it)) }
                SectionLabel(Strings.Identification)
                val idents = Identification.entries
                Segmented(
                    listOf(Strings.Any, Strings.Identified, Strings.Unidentified),
                    idents.indexOf(f.identification),
                    onSelect = { set(f.copy(identification = idents[it])) },
                    modifier = Modifier.fillMaxWidth().padding(
                        start = Css.FilterSeg.padding.left, top = Css.FilterSeg.padding.top,
                        end = Css.FilterSeg.padding.right, bottom = Css.FilterSeg.padding.bottom,
                    ),
                )
                SectionLabel(Strings.Categories)
            }
            val rows = (content as? FilterRows.Shown)?.content
            if (rows != null) items(rows.rowOrder, key = { it }) { id ->
                val checked = id in f.categories
                ListItem(
                    title = rows.labels[id] ?: id,
                    modifier = Modifier.alpha(if (rows.dimmed(id)) Css.FilterItemZero.opacity else 1f).testTag(PushedTags.category(id)),
                    end = rows.count(id).toString(),
                    onClick = { set(f.toggleCategory(id)) },
                    leading = { Checkbox(checked) },
                )
            }
            item {
                Box(Modifier.padding(start = Css.FilterFoot.padding.left, top = Css.FilterFoot.padding.top, bottom = Css.FilterFoot.padding.bottom)) {
                    TextButton(Strings.Clear, onClick = { set(f.clearSheetFilters()) }, modifier = Modifier.testTag(PushedTags.CLEAR))
                }
            }
        }
    }
}

@Composable
private fun PresetRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        title = label,
        modifier = Modifier.testTag(PushedTags.preset(label)),
        trailing = { Switch(checked, onChange, label = label) },
    )
}
