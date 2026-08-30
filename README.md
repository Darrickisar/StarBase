# StarBase

[linux.sb（烧饼社区）](https://linux.sb) 的第三方 Android 客户端。Jetpack Compose 单 Activity，
界面按 Liquid Glass 一套玻璃语言重构，玻璃面板是真实背景采样而不是半透明色块。

站点是服务端渲染的，所以 App 没有自己的后端：OkHttp 取 HTML，Jsoup 解析成模型。登录交给站点
自己的表单在 WebView 里跑，App 不接触也不保存密码。

## 界面

底部四个 tab，其余页面压栈：

| Tab | 内容 |
| --- | --- |
| 首页 | 主题流（多种排序）、每日热帖、板块直达、发帖入口 |
| 板块 | 板块列表 → 板块内主题流（带发帖入口） |
| 发现 | 搜索，以及榜单 / 精华 / 抽奖 / 发卡 / 称号馆的入口矩阵 |
| 我的 | 身份卡 + 4×2 功能矩阵 + 外观 + 关于 |

压栈页面：主题详情（含回帖）、板块、用户主页（主题 / 回帖 / 收藏三个 tab）、榜单、称号馆、
通知、私信、私信会话、发新帖、收藏、个人设置、登录 / 注册。需要登录才有内容的入口统一走
App 内的登录页，不再把人踢回网页。

外观三档，只存本机：`浅色玻璃` / `深色玻璃` / `经典深色`（后者关闭半透明，给低端机和省电）。

## 写操作

回帖（可引用楼层）、发新帖、点赞 / 投币、发私信。都是原生请求，不套 WebView。

站点没有 JSON API，这些表单只在登录态页面里才有。**字段名一律从页面上的真实表单读，不写死**
——`Parse.formOf` 读出 action、method、enctype 和每一个可提交字段，调用方只覆盖用户填的那一两
项，其余原样回发。这不是洁癖：站点自己的命名就不统一（回帖正文叫 `body`，私信正文叫
`content`），而且 `topic_special_type` 这类字段猜错会把普通帖发成抽奖帖。

| 操作 | action | 正文字段 |
| --- | --- | --- |
| 回帖 | `/reply_edit` | `body` |
| 发帖 | `/topic_edit`（表单无 action，发回自己） | `body` |
| 点赞 | `/donate_reply_reaction` | 无，用 `donate_reaction_points` |
| 私信 | `/direct_messages/{对方uid}` | `content` |

引用楼层不是表单字段：站点的回帖处理器从正文文本里认 `@某人 #12`，所以 `Parse.quotePrefix`
负责写、解析层负责读，两个方向有往返测试互锁。

表单如果带 Cloudflare Turnstile（目前没有），原生请求做不出那个 token，这时会落到 WebView，
不会假装失败。

## 技术栈

| 项 | 版本 | 说明 |
| --- | --- | --- |
| AGP | 8.13.2 | |
| Kotlin | 2.3.21 | Compose 编译器随 Kotlin 工具链走 `plugin.compose` |
| Compose BOM | 2026.02.01 | Material 3 |
| [kyant0/backdrop](https://github.com/Kyant0/AndroidLiquidGlass) | 1.0.6 | 真实背景采样，`ui/glass` 只是对它的封装 |
| OkHttp | 4.12.0 | |
| Jsoup | 1.17.2 | 解析服务端渲染的 HTML |
| Coil | 2.7.0 | 头像与帖内图片 |
| androidx.webkit | 1.11.0 | 登录 WebView，兼 cookie 源 |
| kotlinx-serialization-json | 1.6.3 | |

`compileSdk 36` / `targetSdk 34` / `minSdk 24` / JVM 17。compileSdk 抬到 36 是 backdrop 的 aar
声明了 `minCompileSdk=36`，不是运行时需要——targetSdk 保持在 34。

## 目录结构

```
app/src/main/java/StarBase/Android/Forum/
├── MainActivity.kt          单 Activity 入口
├── data/                    Live.kt（内存态 + 拉取编排）、UserStore.kt（本机偏好）
└── net/                     Net.kt（OkHttp + cookie）、Api.kt（站点入口）、Parse.kt（HTML → 模型）
└── ui/
    ├── Shell.kt             底栏、Route 压栈、滚动位置记忆
    ├── glass/               Glass.kt（liquidGlass / pageBackdrop / AmbientRoom）、GlassComponents.kt
    ├── theme/Theme.kt       SbTokens 三套配色 + SbRadius / SbMetrics
    ├── components/          TopicRow、Avatar、StarMark（品牌星标）、NavIcon（底栏图形）等
    └── screens/             各页面
app/src/test/                Parse 层单元测试 + 真实抓取的 fixture 页面
docs/logo-prompt.md          logo 的生成提醒词与几何锁定项
_mkicon.py                   logo.png → 整套启动器图标
logo.png                     图标源图
_grab.ps1                    抓登录态页面（写操作的表单只在登录态才有）
_tools/mkfixtures.py         把抓下来的页面脱敏成 fixture
```

约 12,100 行 Kotlin。

### 抓登录态页面

写操作的表单、点赞按钮的积分档位、私信会话的结构，游客都看不到。要改这部分先抓一份：

```powershell
# 浏览器 F12 → Network → 刷新 → 第一个请求 → Request Headers → 复制 Cookie: 后面整串
# 存进 _grab-cookie.txt（在 .gitignore 里）
powershell -ExecutionPolicy Bypass -File _grab.ps1
python _tools/mkfixtures.py        # 脱敏后写进 app/src/test/resources/
```

`_grab/` 和 `_grab-cookie.txt` 都不进仓库——原始页面带 session 和 CSRF。只有脱敏副本入库。

三个坑记在这里省得再踩：

- PowerShell 5.1 的 `Invoke-WebRequest` 会忽略手设的 `Cookie` 头（要用 `WebRequestSession` +
  `CookieContainer`），失败形态是 200 + 登录页，看着像成功。
- `$home` 是只读变量，赋值静默失败，后面所有正则会去匹配用户目录路径。
- **不要用 `Get-Content -Raw` + `Set-Content` 改带中文的文件。** PS 5.1 按 ANSI 解码再按 UTF-8
  写回，中文会双重编码成不可逆的乱码。改文本用编辑器或 Python。

## 构建

需要 JDK 17、Android SDK（platform 36 + build-tools 36.0.0）、Gradle 8.14.5。

> **仓库不含 Gradle wrapper**，请用系统装的 Gradle（版本不要低于 8.14），或自己
> `gradle wrapper --gradle-version 8.14.5` 生成一份。

先写 `local.properties` 指向你的 SDK：

```properties
sdk.dir=/path/to/Android/sdk
```

然后：

```bash
gradle :app:assembleDebug          # 调试包
gradle :app:testDebugUnitTest      # 单元测试
gradle :app:assembleRelease        # 发布包，输出 StarBase-release.apk
```

release 走 minify + 资源压缩。签名读仓库根目录的 `keystore.properties`：

```properties
storeFile=keystore/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

这个文件和 `keystore/` 都不在仓库里；缺失时 release 会自动回落到 debug key，项目仍然能编译。

## 测试

```bash
gradle :app:testDebugUnitTest
```

48 个用例，全部打在 `Parse.kt` 上——解析层是唯一会因为站点改版而静默失效的地方。fixture 是从
linux.sb 真实抓下来的页面（`app/src/test/resources/*.html`，其中的一次性 CSRF / 验证码 token
已替换为占位值，真人昵称和私信正文也换掉了）。站点结构变了就先加一个 fixture 再改解析，不要
只改解析。

写操作的用例值得单独说：它们锁的不是「解析没崩」，而是**具体字段名和 action**。回帖曾经发
`content` 到 `/topic/{id}`，站点只是把帖子页重新渲染一遍返回，于是 App 报「已提交，但未能确认
结果」——实际一条都没发出去，而当时的测试全绿。所以现在每个写操作都有一条用例直接断言它的
action 和字段集合。

## 图标与品牌标识

`logo.png`（1254×1254 RGBA）是唯一的图标源图，整套启动器图标由脚本派生：

```bash
python _mkicon.py     # 需要 Pillow
```

产出 `mipmap-*/` 下 15 个无损 WebP：传统方形、传统圆形（放大越过金边后圆形遮罩 + 补画金环）、
自适应前景（满幅方块本体，配 `<inset 14%>`）。

App 界面内的品牌位不用位图，`ui/components/StarMark.kt` 用 Canvas 按同一套比例现画（四角星 +
浅弧 + 倾斜轨道），尺寸缩小时细节逐级退场。造型的来龙去脉和锁定项见 `docs/logo-prompt.md`。

## 隐私与网络

- 权限只有 `INTERNET` 和 `ACCESS_NETWORK_STATE`。
- 全程 HTTPS：`usesCleartextTraffic=false`，另有 `res/xml/network_security_config.xml`。
- 会话 cookie 由 WebView 持有，OkHttp 通过 `WebViewCookieJar` 读取；App 不存储账号密码。
- 本机只保存外观偏好和称号缓存（SharedPreferences），不上传任何用户数据到第三方。

## 已知限制

- 依赖站点的 HTML 结构。改版会打断解析层，这是这类客户端的固有代价。
- 登录必须走 WebView：站点有 Cloudflare、`_csrf`、算术验证码、PoW 和蜜罐字段，无法用纯 HTTP 复现。
- 发帖只能发普通帖。抽奖帖和发卡帖的表单字段多一大截（奖品清单、开奖时间、卡密、面额），
  没做进去。
- 回帖不能带附件。附件是独立的上传端点（`/attachment_upload`），要先上传再把返回的引用插进
  正文，这一步没做。
- 私信只能在已有会话里发。要开新会话得先在网页上找人。
- 搜索必须登录，这是站点的限制。
- 没有 Gradle wrapper（见上）。

## 声明

第三方客户端，与 linux.sb 官方无关。站点名称、内容与用户数据归原站点及其作者所有。

