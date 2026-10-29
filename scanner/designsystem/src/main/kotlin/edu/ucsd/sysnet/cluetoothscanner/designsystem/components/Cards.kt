package edu.ucsd.sysnet.cluetoothscanner.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Concentric
import edu.ucsd.sysnet.cluetoothscanner.designsystem.sp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css

/**
 * C-7 ProductCard: 148 dp wide, 12 dp padding, an 8 dp gap, `--fill2`, no shadow, radius `--card-r` (concentric with
 * the sheet: phone 18, tablet 6; [Concentric.card]). A 36 dp `--bg` circle holds the category icon (`--t3` when
 * [unidentified]); the name is weight 600 with a 20 dp line, two lines at most (minimum 40); the [status] line
 * ([Strings.productStatus]) is 13/20 `--t2`, one line.
 */
@Composable
fun ProductCard(
    title: String,
    status: String,
    categoryId: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pageIcon: String? = null,
    unidentified: Boolean = categoryId == null,
    radius: Dp = Concentric.card(tablet = false),
) {
    val c = CluetoothTheme.colors
    val t = CluetoothTheme.type
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .width(Css.Pcard.width)
            .clip(shape)
            .background(c.fill2)
            .plainClickable(source, onClick = onClick)
            .focusRing(source, shape)
            .padding(Css.Pcard.padding.left),
        verticalArrangement = Arrangement.spacedBy(Css.Pcard.gap),
    ) {
        Box(Modifier.size(Css.PcardIcon.width).clip(CircleShape).background(c.bg), contentAlignment = Alignment.Center) {
            CategoryIcon(if (unidentified) null else categoryId, if (unidentified) null else pageIcon, tint = if (unidentified) c.t3 else c.t1)
        }
        ClampedText(
            title,
            Css.PcardName.webkitLineClamp.toInt(),
            Modifier.fillMaxWidth().defaultMinSize(minHeight = Css.PcardName.minHeight),
            style = t.body.copy(fontWeight = FontWeight(Css.PcardName.fontWeight.toInt()), lineHeight = Css.PcardName.lineHeight.sp),
        )
        Text(status, style = t.sub, color = c.t2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * C-8 CategorySection: a header (17/650 label and a `--t3` "›") over a horizontally scrolling row of cards with a
 * 10 dp gap. [onHeaderClick] makes the header tappable.
 */
@Composable
fun CategorySection(
    label: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = Css.Cards.padding.left,
        top = Css.Cards.padding.top,
        end = Css.Cards.padding.right,
        bottom = Css.Cards.padding.bottom,
    ),
    onHeaderClick: (() -> Unit)? = null,
    cards: @Composable RowScope.() -> Unit,
) {
    val c = CluetoothTheme.colors
    Column(modifier.fillMaxWidth().padding(top = Css.Cat.padding.top, bottom = Css.Cat.padding.bottom)) {
        val source = remember { MutableInteractionSource() }
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onHeaderClick != null) Modifier.plainClickable(source, onClick = onHeaderClick) else Modifier)
                .padding(horizontal = Css.CatHead.padding.left, vertical = Css.CatHead.padding.top),
            horizontalArrangement = Arrangement.spacedBy(Css.CatHead.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = CluetoothTheme.type.title)
            Text("›", style = CluetoothTheme.type.title.copy(fontWeight = FontWeight(Css.CatHead_Chev.fontWeight.toInt())), color = c.t3)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(Css.Cards.gap),
            content = cards,
        )
    }
}
