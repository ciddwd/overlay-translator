package com.gameocr.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** What sits at the end of a home list row. */
internal sealed interface HomeRowTrailing {
    /** Opens something inside the app. */
    data object Chevron : HomeRowTrailing
    /** Leaves the app (browser, external app). */
    data object External : HomeRowTrailing
    data object Busy : HomeRowTrailing
    data object None : HomeRowTrailing
    /** Finished item: green chip, the row is not tappable. */
    data class Done(val label: String) : HomeRowTrailing
}

/**
 * Shared row for the home sections: 36 dp icon tile, title, one-line status, trailing chip or chevron.
 * Min height 56 dp, padding 8 dp, corner 12 dp.
 */
@Composable
internal fun HomeListRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    trailing: HomeRowTrailing,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    highlighted: Boolean = false,
) {
    val shape = RoundedCornerShape(12.dp)
    val clickable = onClick != null && trailing !is HomeRowTrailing.Done && trailing != HomeRowTrailing.Busy
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (highlighted) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
            .then(
                if (clickable) {
                    Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick!!)
                } else {
                    Modifier
                }
            )
            .heightIn(min = 56.dp)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeIconTile(icon, background = if (highlighted) MaterialTheme.colorScheme.surface else null)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    modifier = Modifier.padding(top = 1.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                )
            }
        }
        AnimatedContent(
            targetState = trailing,
            transitionSpec = { scaleIn(spring(dampingRatio = 0.55f)) + fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "home-row-trailing",
        ) { end ->
            when (end) {
                HomeRowTrailing.Chevron -> Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
                )
                HomeRowTrailing.External -> Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
                )
                HomeRowTrailing.Busy -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                HomeRowTrailing.None -> Spacer(Modifier.size(0.dp))
                is HomeRowTrailing.Done -> HomeDoneChip(end.label)
            }
        }
    }
}

@Composable
internal fun HomeIconTile(icon: ImageVector, background: Color? = null) {
    Box(
        Modifier
            .size(36.dp)
            .background(background ?: MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun HomeDoneChip(label: String) {
    Row(
        modifier = Modifier
            .background(HomeStatusColors.okContainer(), RoundedCornerShape(12.dp))
            .heightIn(min = 24.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Check, null, tint = HomeStatusColors.ok(), modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = HomeStatusColors.ok())
    }
}

/** Rounded home card with the same border and surface as the capture card. */
@Composable
internal fun HomeSectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    horizontalInset: Dp = 16.dp,
    fillContentHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.padding(horizontal = horizontalInset).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier
                .then(
                    if (fillContentHeight) Modifier.fillMaxHeight() else Modifier
                )
                .padding(contentPadding),
            content = content,
        )
    }
}

/** Header for the gallery face and the presets card: icon tile, title, subtitle, optional text action. */
@Composable
internal fun HomeCardHeader(
    icon: ImageVector? = null,
    title: String,
    subtitle: String,
    prominent: Boolean,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null && prominent) {
            Box(
                Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
            }
        } else if (icon != null) {
            HomeIconTile(icon)
        }
        Column(Modifier.weight(1f).padding(start = if (icon != null) 12.dp else 0.dp, end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(18.dp))
            }
        }
    }
}
