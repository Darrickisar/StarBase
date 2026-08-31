package StarBase.Android.Forum.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import StarBase.Android.Forum.data.Me
import StarBase.Android.Forum.ui.Gap
import StarBase.Android.Forum.ui.components.MetaText
import StarBase.Android.Forum.ui.components.PageHead
import StarBase.Android.Forum.ui.components.SectionHeader
import StarBase.Android.Forum.ui.components.StarMark
import StarBase.Android.Forum.ui.components.UserAvatar
import StarBase.Android.Forum.ui.components.tierColor
import StarBase.Android.Forum.ui.components.tierLabel
import StarBase.Android.Forum.ui.glass.GlassButton
import StarBase.Android.Forum.ui.glass.GlassChip
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.GlassPanel
import StarBase.Android.Forum.ui.glass.GlyphTile
import StarBase.Android.Forum.ui.glass.pressFeedback
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics
import StarBase.Android.Forum.ui.theme.SbRadius

/*
 * §04 我的页 (个人控制台).
 *
 * Same eight entries as before, but they stop being a tall stack of full-width
 * rows: 身份卡 carries 登录/注册 for guests, and 我的内容 becomes one continuous
 * glass panel holding a 4 x 2 matrix. Below it sits the one row that is not
 * forum content - 应用设置, which now holds 外观, 关于 and the update check. The
 * point of §04 is that most of the page is visible without scrolling.
 */

/** Everything reachable from 我的. */
enum class MineEntry(val label: String, val glyph: String) {
    TOPICS("我的主题", "题"),
    REPLIES("我的回帖", "帖"),
    MESSAGES("私信", "信"),
    TITLES("我的称号", "号"),
    POINTS("积分", "分"),
    BOOKMARKS("收藏", "藏"),
    NOTIFICATIONS("通知", "铃"),
    SETTINGS("个人设置", "设")
}

@Composable
fun MineScreen(
    me: Me?,
    checking: Boolean,
    onEntry: (MineEntry) -> Unit,
    onAppSettings: () -> Unit,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    onProfile: (Int) -> Unit,
    onRefresh: () -> Unit,
    updateReady: Boolean = false
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item("head") {
            PageHead(
                title = "我的",
                action = "刷新",
                onAction = onRefresh
            )
            if (me == null) {
                GuestCard(checking = checking, onLogin = onLogin, onRegister = onRegister)
            } else {
                MeCard(me = me, onProfile = { onProfile(me.id) })
            }
        }

        item("entries") {
            Gap(18)
            SectionHeader(
                title = "我的内容",
                subtitle = if (me == null) "登录后查看" else "来自 linux.sb"
            )
            Gap(10)
            EntryMatrix(onEntry = onEntry)
        }

        item("app") {
            Gap(18)
            SectionHeader(title = "本机", subtitle = "不需要登录")
            Gap(10)
            AppSettingsRow(updateReady = updateReady, onClick = onAppSettings)
            Gap(28)
        }
    }
}

/**
 * §4.1 未登录身份卡: StarBase mark, two lines of text, and 登录 / 注册 on the right - the
 * two buttons narrower than the card body so the card still reads as one line of
 * information rather than as a banner.
 */
@Composable
private fun GuestCard(checking: Boolean, onLogin: () -> Unit, onRegister: () -> Unit) {
    val tokens = LocalTokens.current
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
        padding = 16.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(50))
                    .background(tokens.accentWarm.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                StarMark(size = 30.dp, tint = tokens.accentWarm)
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (checking) "正在检查登录状态" else "还没有登录",
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Gap(3)
                Text(
                    text = "登录后可以看评论、发回帖、收私信",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textTertiary,
                    maxLines = 2
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassButton(
                    text = "登录",
                    onClick = onLogin,
                    modifier = Modifier.width(74.dp),
                    compact = true
                )
                GlassButton(
                    text = "注册",
                    onClick = onRegister,
                    modifier = Modifier.width(74.dp),
                    primary = false,
                    compact = true
                )
            }
        }
    }
}

/** The signed-in header, built from the sidebar user card the site renders. */
@Composable
private fun MeCard(me: Me, onProfile: () -> Unit) {
    val tokens = LocalTokens.current
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
        onClick = onProfile,
        padding = 16.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(name = me.name, url = me.avatar, size = 48.dp, ring = true)
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = me.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Gap(4)
                // 用户组 and UID only. 称号 and 积分 are their own pills below,
                // because the site glues all four into one sidebar line and the
                // whole point of this card is that they read as separate facts.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (me.rank.isNotBlank()) {
                        GlassChip(text = me.rank, tint = tokens.textSecondary)
                        Spacer(Modifier.width(6.dp))
                    }
                    if (me.id > 0) MetaText("UID ${me.id}")
                }
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.textTertiary
            )
        }

        if (me.title != null || me.points.isNotBlank()) {
            Gap(12)
            Row(verticalAlignment = Alignment.CenterVertically) {
                me.title?.let { title ->
                    val color = tierColor(title.tier)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(SbRadius.small))
                            .background(color.copy(alpha = 0.11f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (title.tier.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = tierLabel(title.tier),
                                style = MaterialTheme.typography.labelSmall,
                                color = color.copy(alpha = 0.85f),
                                maxLines = 1
                            )
                        }
                        if (title.serial.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "编号 ${title.serial}",
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.textTertiary,
                                maxLines = 1
                            )
                        }
                    }
                }
                if (me.points.isNotBlank()) {
                    if (me.title != null) Spacer(Modifier.width(8.dp))
                    GlassChip(text = me.points, tint = tokens.accentWarm)
                }
            }
        }
    }
}

/**
 * §4.2 我的内容: 4 x 2 矩阵, 整体一层轻玻璃, 单项不再是一条大分割行.
 *
 * The eight entries are unchanged - what changes is that they now cost two rows
 * of ~78dp instead of eight full-width rows, which is what makes the rest of the
 * page (外观, 关于) visible without scrolling.
 */
@Composable
private fun EntryMatrix(onEntry: (MineEntry) -> Unit) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
        level = GlassLevel.LOW,
        padding = 8.dp
    ) {
        MineEntry.entries.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { entry ->
                    EntryCell(entry = entry, modifier = Modifier.weight(1f)) { onEntry(entry) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun EntryCell(
    entry: MineEntry,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val tokens = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(78.dp)
            .pressFeedback(interaction)
            .clip(RoundedCornerShape(SbRadius.field))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GlyphTile(glyph = entry.glyph, size = 31.dp)
            Gap(6)
            Text(
                text = entry.label,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // §4.2 箭头收敛为右上角一个极弱的提示。这一格不再放未读数：方案
        // 里的“不显示额外数字”是硬要求，数字只留在底部导航上。
        Text(
            text = "›",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textTertiary.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 7.dp)
        )
    }
}

/**
 * §4.4 应用设置: the one row on this page that is not linux.sb content. It is the
 * whole width, unlike the eight cells above, because it is a different kind of
 * thing - and it works signed out, since nothing behind it needs a session.
 */
@Composable
private fun AppSettingsRow(updateReady: Boolean, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
        onClick = onClick,
        level = GlassLevel.LOW,
        padding = 12.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphTile(glyph = "用", size = 34.dp)
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "应用设置",
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.textPrimary,
                        maxLines = 1
                    )
                    if (updateReady) {
                        Spacer(Modifier.width(7.dp))
                        GlassChip(text = "新版本", tint = tokens.accentGlow)
                    }
                }
                Gap(3)
                Text(
                    text = "检查更新 · 外观 · 本机数据 · 关于",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "›",
                style = MaterialTheme.typography.titleSmall,
                color = tokens.textTertiary.copy(alpha = 0.6f)
            )
        }
    }
}
