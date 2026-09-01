package StarBase.Android.Forum.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import StarBase.Android.Forum.data.Lottery
import StarBase.Android.Forum.data.Reminders
import StarBase.Android.Forum.ui.Hairline
import StarBase.Android.Forum.ui.SmallAction
import StarBase.Android.Forum.ui.glass.GlassChip
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.theme.LocalTokens

/**
 * 抽奖卡: the site's own lottery panel, where the site puts it - at the end of
 * 主楼, under the body and above the comments.
 *
 * Every line in it is the site's: the 抽奖中 pill, 「410 人参与」, the prize rows,
 * and above all [Lottery.condition] - 「到 2026-09-04 09:12自动开奖」 or 「满 500 人
 * 自动开奖」. The app used to print none of this, which is why 开奖时间 was nowhere on
 * screen and 开奖提醒 could not be reached: the time is a field on this panel, not a
 * sentence in the post.
 *
 * 开奖提醒 sits **on the condition row, beside the time it would ring at** - the one
 * place where what the button does is self-evident. It is offered only when that
 * row actually holds a clock and the clock is still ahead: a 人数-triggered draw
 * gets the sentence and no button, because nobody knows when the 500th reply lands.
 */
@Composable
fun LotteryCard(
    lottery: Lottery,
    reminded: Boolean,
    onRemind: (Long) -> Unit,
    onCancelRemind: () -> Unit,
    onUser: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalTokens.current
    // One clock reading for the whole card: the countdown and the 「值得设吗」
    // decision have to agree with each other.
    val now = remember(lottery.drawAt) { System.currentTimeMillis() }
    val settable = lottery.drawAt > 0L && Reminders.drawWorthScheduling(lottery.drawAt, now)

    SbCard(modifier = modifier.fillMaxWidth(), level = GlassLevel.LOW, padding = 15.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "抽奖帖",
                style = MaterialTheme.typography.titleSmall,
                color = tokens.textPrimary
            )
            Spacer(Modifier.width(8.dp))
            if (lottery.status.isNotBlank()) {
                GlassChip(
                    text = lottery.status,
                    tint = if (lottery.open) tokens.accentWarm else tokens.textTertiary
                )
            }
            Spacer(Modifier.weight(1f))
            if (lottery.participants.isNotBlank()) {
                MetaText(lottery.participants)
            }
        }

        if (lottery.note.isNotBlank()) {
            Text(
                text = lottery.note,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                modifier = Modifier.padding(top = 7.dp)
            )
        }

        if (lottery.prizes.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                lottery.prizes.forEach { prize ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = prize.name,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = tokens.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        // 「兑换码 · 5 份 · 每份 1 个烧饼」 - one printed line on the
                        // site, kept as one line here rather than split into fields
                        // the app would have to name itself.
                        Text(
                            text = prize.detail,
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.textTertiary,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        if (lottery.condition.isNotBlank()) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Hairline()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = lottery.condition,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = tokens.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    if (settable) {
                        Spacer(Modifier.width(10.dp))
                        SmallAction(
                            text = if (reminded) "已设提醒" else "开奖提醒",
                            primary = false,
                            onClick = {
                                if (reminded) onCancelRemind() else onRemind(lottery.drawAt)
                            }
                        )
                    }
                }
                if (settable) {
                    Text(
                        text = Reminders.countdownText(lottery.drawAt, now) +
                            if (reminded) {
                                " · 到点响一次，再按一下取消"
                            } else {
                                " · 本机闹钟，到点响一次，不联网"
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.textTertiary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        if (lottery.result.isNotBlank()) {
            Text(
                text = lottery.result,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                modifier = Modifier.padding(top = 11.dp)
            )
        }

        if (lottery.winners.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = "中奖名单",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textTertiary
                )
                lottery.winners.forEach { winner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = winner.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.accentGlow,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = winner.userId > 0) {
                                    onUser(winner.userId)
                                }
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = winner.prize,
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
