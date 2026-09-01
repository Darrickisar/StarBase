package StarBase.Android.Forum.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import StarBase.Android.Forum.data.BlockRule
import StarBase.Android.Forum.data.UserStore
import StarBase.Android.Forum.ui.EmptyPanel
import StarBase.Android.Forum.ui.Gap
import StarBase.Android.Forum.ui.SmallAction
import StarBase.Android.Forum.ui.components.MetaText
import StarBase.Android.Forum.ui.components.SectionHeader
import StarBase.Android.Forum.ui.components.SegmentPill
import StarBase.Android.Forum.ui.glass.GlassChip
import StarBase.Android.Forum.ui.glass.GlassLevel
import StarBase.Android.Forum.ui.glass.GlassPanel
import StarBase.Android.Forum.ui.glass.liquidGlass
import StarBase.Android.Forum.ui.theme.LocalTokens
import StarBase.Android.Forum.ui.theme.SbMetrics
import StarBase.Android.Forum.ui.theme.SbRadius

/*
 * 本地屏蔽 / 折叠.
 *
 * A rule list, and that is all that is stored - nothing about the topics or
 * replies a rule happens to hide. Turning a rule off brings everything straight
 * back, because there is no second copy anywhere for it to come back from.
 *
 * One thing this page says out loud rather than papering over: linux.sb has its
 * own keyword filter (the 屏蔽设置 button on the home feed). This is not that one
 * and does not touch it. It exists alongside it because the site's version covers
 * the home feed only and matches keywords only, while these rules also apply to
 * board pages, search results and profile lists, and can fold one person's replies
 * inside a thread - which the site has no equivalent for.
 */

@Composable
fun BlockScreen(
    store: UserStore,
    onBack: () -> Unit,
    onOpenSiteFilter: () -> Unit
) {
    val tokens = LocalTokens.current
    val keyboard = LocalSoftwareKeyboardController.current
    var draft by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(BlockRule.Kind.KEYWORD) }

    fun commit() {
        val value = draft.trim()
        if (value.isEmpty()) return
        store.addBlockRule(BlockRule(value = value, kind = kind))
        draft = ""
        keyboard?.hide()
    }

    Column(modifier = Modifier.fillMaxWidth().imePadding()) {
        DetailBar(
            title = "本地屏蔽",
            subtitle = if (store.blockRules.isEmpty()) "" else "${store.blockRules.size} 条规则",
            onBack = onBack,
            action = if (store.blockRules.isEmpty()) "" else "全部清除",
            onAction = { store.clearBlockRules() }
        )

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item("add") {
                Gap(6)
                GlassPanel(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
                    level = GlassLevel.LOW,
                    shape = RoundedCornerShape(16.dp),
                    padding = 13.dp
                ) {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BlockRule.Kind.entries.forEach { option ->
                                SegmentPill(
                                    label = option.label,
                                    selected = option == kind,
                                    onClick = { kind = option }
                                )
                            }
                        }
                        Gap(11)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .liquidGlass(
                                        shape = RoundedCornerShape(SbRadius.small),
                                        level = GlassLevel.LOW,
                                        refract = false
                                    )
                                    .padding(horizontal = 11.dp, vertical = 10.dp)
                            ) {
                                if (draft.isEmpty()) {
                                    Text(
                                        text = if (kind == BlockRule.Kind.KEYWORD) {
                                            "想藏起来的词"
                                        } else {
                                            "不想看到的用户名"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tokens.textTertiary
                                    )
                                }
                                BasicTextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        color = tokens.textPrimary
                                    ),
                                    cursorBrush = SolidColor(tokens.accentGlow),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { commit() }),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(Modifier.width(9.dp))
                            SmallAction("添加", primary = true, onClick = { commit() })
                        }
                        Gap(9)
                        Text(
                            text = if (kind == BlockRule.Kind.KEYWORD) {
                                "匹配标题和回帖正文，不分大小写。板块名不算。"
                            } else {
                                "整个用户名相同才算，「张三」不会连「张三丰」一起屏蔽。"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.textTertiary
                        )
                    }
                }
            }

            item("rules-head") {
                Gap(18)
                SectionHeader(
                    title = "规则",
                    subtitle = if (store.blockRules.isEmpty()) "还没有" else "作用在每次现取的内容上"
                )
                Gap(8)
            }

            if (store.blockRules.isEmpty()) {
                item("empty") {
                    EmptyPanel(
                        "没有屏蔽规则",
                        "加一条之后，帖子列表里命中的会被藏起来，回帖会折叠成一行"
                    )
                }
            } else {
                items(store.blockRules, key = { "${it.kind.key}:${it.value}" }) { rule ->
                    RuleRow(
                        rule = rule,
                        onToggle = { store.toggleBlockRule(rule) },
                        onRemove = { store.removeBlockRule(rule) }
                    )
                    Gap(8)
                }
            }

            item("site-note") {
                Gap(18)
                GlassPanel(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
                    level = GlassLevel.LOW,
                    shape = RoundedCornerShape(16.dp),
                    padding = 13.dp
                ) {
                    Column {
                        Text(
                            text = "站点自己也有一个关键词屏蔽",
                            style = MaterialTheme.typography.titleSmall,
                            color = tokens.textPrimary
                        )
                        Gap(5)
                        Text(
                            text = "网页版首页有个「屏蔽设置」，那份存在站点上、跟着账号走，" +
                                "但只管首页、只按关键词。这里的规则只在这台手机上，" +
                                "板块页、搜索结果和个人主页也算，还能折叠某个人的回帖。两者互不影响。",
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.textSecondary
                        )
                        Gap(10)
                        SmallAction("打开站点的屏蔽设置", primary = false, onClick = onOpenSiteFilter)
                    }
                }
                Gap(24)
            }
        }
    }
}

@Composable
private fun RuleRow(rule: BlockRule, onToggle: () -> Unit, onRemove: () -> Unit) {
    val tokens = LocalTokens.current
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SbMetrics.pagePadding),
        level = GlassLevel.LOW,
        shape = RoundedCornerShape(14.dp),
        padding = 12.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.value,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (rule.enabled) tokens.textPrimary else tokens.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(7.dp))
                    GlassChip(
                        text = rule.kind.label,
                        tint = if (rule.enabled) tokens.pinTint else tokens.textTertiary
                    )
                }
                if (!rule.enabled) {
                    Gap(3)
                    MetaText("已停用")
                }
            }
            Spacer(Modifier.width(8.dp))
            SmallAction(
                text = if (rule.enabled) "停用" else "启用",
                primary = false,
                onClick = onToggle
            )
            Spacer(Modifier.width(6.dp))
            SmallAction("删除", primary = false, onClick = onRemove)
        }
    }
}
