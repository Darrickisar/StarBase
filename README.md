# StarBase

[linux.sb（烧饼社区）](https://linux.sb) 的第三方 Android 客户端。Jetpack Compose 单 Activity，
界面按 Liquid Glass 一套玻璃语言重构，玻璃面板是真实背景采样而不是半透明色块。

站点是服务端渲染的，所以 App 没有自己的后端：OkHttp 取 HTML，Jsoup 解析成模型。登录交给站点
自己的表单在 WebView 里跑，App 不接触也不保存密码。

## 界面

底部四个 tab，其余页面压栈：

| Tab | 内容 |
| --- | --- |
| 首页 | 主题流（多种排序）、每日热帖轮播、板块直达 |
| 板块 | 板块列表 → 板块内主题流 |
| 发现 | 搜索，以及榜单 / 精华 / 抽奖 / 发卡 / 称号馆的入口矩阵 |
| 我的 | 身份卡 + 4×2 功能矩阵 + 外观 + 关于 |

压栈页面：主题详情（含回帖）、板块、用户主页（主题 / 回帖 / 收藏三个 tab）、榜单、称号馆、
通知、私信、收藏、个人设置、登录 / 注册。需要登录才有内容的入口统一走 App 内的登录页，不再
把人踢回网页。

外观三档，只存本机：`浅色玻璃` / `深色玻璃` / `经典深色`（后者关闭半透明，给低端机和省电）。

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
├── net/                     Net.kt（OkHttp + cookie）、Api.kt（站点入口）、Parse.kt（HTML → 模型）
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
```

约 10,600 行 Kotlin。

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

25 个用例，全部打在 `Parse.kt` 上——解析层是唯一会因为站点改版而静默失效的地方。fixture 是从
linux.sb 真实抓下来的页面（`app/src/test/resources/*.html`，其中的一次性 CSRF / 验证码 token
已替换为占位值）。站点结构变了就先加一个 fixture 再改解析，不要只改解析。

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
- 没有 Gradle wrapper（见上）。

## 声明

第三方客户端，与 linux.sb 官方无关。站点名称、内容与用户数据归原站点及其作者所有。
