package edu.ucsd.sysnet.cluetoothscanner.designsystem.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.SpecValues
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Weights
import edu.ucsd.sysnet.cluetoothscanner.designsystem.sp
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokenShadow
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Css
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Glyphs
import edu.ucsd.sysnet.cluetoothscanner.designsystem.tokens.Tokens

/**
 * C-3 Chip: a 34 dp pill, 14 dp padding, 14 sp. Unselected `--chip-bg`/`--chip-fg`; selected `--chip-on-bg`/
 * `--chip-on-fg` at weight 500. [floating] adds `--shadow-float` ("Search this area", Q-6).
 */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, floating: Boolean = false) {
    val c = CluetoothTheme.colors
    val m = CluetoothTheme.motion
    val bg by animateColorAsState(if (selected) c.chipOnBg else c.chipBg, m.fast(), label = "chipBg")
    val fg by animateColorAsState(if (selected) c.chipOnFg else c.chipFg, m.fast(), label = "chipFg")
    val shape = RoundedCornerShape(Css.Chip.borderRadius)
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .then(if (floating) Modifier.tokenShadow(CluetoothTheme.shadows.shadowFloat, shape) else Modifier)
            .height(Css.Chip.height)
            .clip(shape)
            .background(bg)
            .selectable(selected, interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .focusRing(source, shape)
            .padding(horizontal = Css.Chip.padding.left),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = CluetoothTheme.type.control.copy(fontWeight = if (selected) Weights.Medium else Weights.Regular),
            color = fg,
            maxLines = 1,
        )
    }
}

/** C-3 ChipRow: horizontally scrollable without a scrollbar, a 6 dp gap and `--edge` side padding. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Tokens.edge),
        horizontalArrangement = Arrangement.spacedBy(SpecValues.chipRowGap),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** C-4 sizes: [Default] 36 dp (32 dp options), [Header] 40 dp (36 dp options, S-3), the sheet header height. */
enum class SegmentedSize(val track: Dp, val option: Dp) {
    Default(Css.Seg_Button.height + Css.Seg.padding.top * 2, Css.Seg_Button.height),
    Header(Tokens.sheetHeaderH, Css.DiscHead_Seg_Button.height),
}

/**
 * C-4 Segmented: a `--fill2` pill track with 2 dp padding; the selected `--seg` thumb (`--shadow-thumb`) slides to
 * the new option over `--t-std`. Labels are 14 sp, `--t2`, the selected one `--t1` at 500. Options share the width
 * equally. Width comes from [modifier] (184 dp in a sheet header, the full width for the device tabs). As in the
 * CSS (`white-space: nowrap`, overflow visible), a label is centered on its option and may run into the option's
 * 16 dp padding rather than being cut: "This area" needs about 62 dp of a header option's 90.
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    size: SegmentedSize = SegmentedSize.Default,
) {
    val c = CluetoothTheme.colors
    val m = CluetoothTheme.motion
    val pad = Css.Seg.padding.top
    BoxWithConstraints(
        modifier
            .height(size.track)
            .clip(RoundedCornerShape(Css.Seg.borderRadius))
            .background(c.segBg)
            .padding(pad),
    ) {
        val optionW = maxWidth / options.size
        val thumbX by animateDpAsState(optionW * selected.coerceIn(0, options.lastIndex), m.std(), label = "thumb")
        val thumbShape = RoundedCornerShape(Css.SegThumb.borderRadius)
        Box(
            Modifier
                .offset { IntOffset(thumbX.roundToPx(), 0) }
                .width(optionW)
                .fillMaxHeight()
                .tokenShadow(CluetoothTheme.shadows.segOnShadow, thumbShape)
                .clip(thumbShape)
                .background(c.segOnBg),
        )
        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                val fg by animateColorAsState(if (on) c.segOnFg else c.segFg, m.fast(), label = "segFg")
                val source = remember { MutableInteractionSource() }
                val shape = RoundedCornerShape(Css.Seg_Button.borderRadius)
                Box(
                    Modifier
                        .width(optionW)
                        .height(size.option)
                        .clip(shape)
                        .selectable(on, interactionSource = source, indication = null, role = Role.RadioButton) { onSelect(i) }
                        .focusRing(source, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        Modifier.wrapContentWidth(unbounded = true).testTag("segLabel:$i"),
                        style = CluetoothTheme.type.control.copy(fontWeight = if (on) Weights.Medium else Weights.Regular),
                        color = fg,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * C-4 switching: the content below a segmented control moves `--swap-x` (28 dp) in the direction of the change while
 * fading (shared axis X, `--t-std`). [D-1a] the view switch crossfades instead: pass [crossfade].
 */
@Composable
fun <S> SwapContent(
    target: S,
    index: (S) -> Int,
    modifier: Modifier = Modifier,
    crossfade: Boolean = false,
    content: @Composable (S) -> Unit,
) {
    val m = CluetoothTheme.motion
    val swapX = with(LocalDensity.current) { Tokens.swapX.roundToPx() }
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            if (crossfade) {
                fadeIn(m.fast()) togetherWith fadeOut(m.fast())
            } else {
                val dir = if (index(targetState) > index(initialState)) 1 else -1
                ContentTransform(
                    slideInHorizontally(m.std()) { dir * swapX } + fadeIn(m.std()),
                    slideOutHorizontally(m.std()) { -dir * swapX } + fadeOut(m.std()),
                )
            }
        },
        label = "swap",
    ) { content(it) }
}

/**
 * C-11 Switch: a 46 × 28 dp pill with a 22 dp knob. Off `--fill2` with a 1 dp inset `--div` ring and a `--fill1`
 * knob; on `--blue`, the knob moving right over `--t-std`.
 */
@Composable
fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    val c = CluetoothTheme.colors
    val m = CluetoothTheme.motion
    val bg by animateColorAsState(if (checked) c.blue else c.fill2, m.fast(), label = "switchBg")
    val ring by animateFloatAsState(if (checked) 0f else 1f, m.fast(), label = "switchRing")
    val travel = Css.Switch.width - Css.Switch.height
    val x by animateDpAsState(if (checked) travel else 0.dp, m.std(), label = "knob")
    val shape = RoundedCornerShape(Css.Switch.borderRadius)
    val source = remember { MutableInteractionSource() }
    val ringW = Css.Switch.boxShadowLengths[3]
    Box(
        modifier
            .size(Css.Switch.width, Css.Switch.height)
            .clip(shape)
            .background(bg)
            .drawWithContent {
                drawContent()
                if (ring > 0f) {
                    val w = ringW.toPx()
                    drawRoundRect(
                        c.div.copy(alpha = c.div.alpha * ring),
                        topLeft = Offset(w / 2, w / 2),
                        size = Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius((size.height - w) / 2),
                        style = Stroke(w),
                    )
                }
            }
            .toggleable(checked, interactionSource = source, indication = null, role = Role.Switch, onValueChange = onCheckedChange)
            .then(if (label != null) Modifier.semanticsLabel(label) else Modifier)
            .focusRing(source, shape),
    ) {
        Box(
            Modifier
                .offset { IntOffset((Css.SwitchAfter.left + x).roundToPx(), Css.SwitchAfter.top.roundToPx()) }
                .size(Css.SwitchAfter.width)
                .clip(CircleShape)
                .background(c.fill1),
        )
    }
}

/**
 * The Filter sheet's checkbox (`.check`): a 20 dp box of radius 6 with a 2 dp inset `--t3` ring; checked it fills
 * `--chip-on-bg` with a `--chip-on-fg` mark (stroke 2.5).
 */
@Composable
fun Checkbox(checked: Boolean, modifier: Modifier = Modifier) {
    val c = CluetoothTheme.colors
    val m = CluetoothTheme.motion
    val bg by animateColorAsState(if (checked) c.chipOnBg else c.chipOnBg.copy(alpha = 0f), m.fast(), label = "checkBg")
    val markAlpha by animateFloatAsState(if (checked) Css.FilterItemAriaCheckedTrue_Check_Svg.opacity else Css.Check_Svg.opacity, m.fast(), label = "mark")
    val ringW = Css.Check.boxShadowLengths[3]
    Box(
        modifier
            .size(Css.Check.width)
            .clip(RoundedCornerShape(Css.Check.borderRadius))
            .background(bg)
            .drawWithContent {
                if (!checked) {
                    val w = ringW.toPx()
                    drawRoundRect(
                        c.t3,
                        topLeft = Offset(w / 2, w / 2),
                        size = Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(Css.Check.borderRadius.toPx() - w / 2),
                        style = Stroke(w),
                    )
                }
                drawContent()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Glyphs.check, null, Modifier.alpha(markAlpha), size = Css.Check_Svg.width, tint = c.chipOnFg, strokeWidth = Css.Check_Svg.strokeWidth)
    }
}

/**
 * C-9 SearchField (the wiki `.sf`): a 42 dp `--fill2` pill, 16 dp padding, a `--t3` search icon and a 10 dp gap,
 * 14 sp text with a `--t3` placeholder. Focused it turns `--field` with the inset focus ring (G-6).
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onSearch: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = Css.Sf.padding.left),
) {
    val c = CluetoothTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val bg by animateColorAsState(if (focused) c.field else c.fill2, CluetoothTheme.motion.fast(), label = "sf")
    val shape = RoundedCornerShape(Css.Sf.borderRadius)
    val style = CluetoothTheme.type.control.copy(fontSize = Css.Sf.fontSize.sp, color = c.t1)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .height(Css.Sf.height)
            .clip(shape)
            .background(bg)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusRing(source, shape),
        textStyle = style,
        singleLine = true,
        cursorBrush = SolidColor(c.blue),
        interactionSource = source,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        decorationBox = { inner ->
            Row(
                Modifier.padding(contentPadding),
                horizontalArrangement = Arrangement.spacedBy(Css.Sf.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) leading() else Icon(Glyphs.search, null, tint = c.t3)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = style, color = c.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            }
        },
    )
}

internal fun Modifier.semanticsLabel(label: String): Modifier =
    this.then(Modifier.semantics(mergeDescendants = false) { contentDescription = label })

