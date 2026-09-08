# Collections And Essence

Owned production files, all under `app/src/main/java/StarBase/Android/Forum`:

- `data/Collections.kt`
- `net/CollectionsApi.kt`
- `net/EssenceApi.kt`
- `ui/screens/CollectionsScreen.kt`
- `ui/screens/EssenceScreen.kt`

No shared parser, API, network, theme, navigation, or build file is edited by this feature.

## Integration

Both screens are in `StarBase.Android.Forum.ui.screens`. ViewModels have default constructors and can also be supplied by the caller. Pass the actual session state so login and account changes clear transient forms and reload permissions.

```kotlin
@Composable
fun CollectionsScreen(
    onBack: () -> Unit,
    onTopic: (Int) -> Unit,
    onLogin: () -> Unit,
    onOpenLink: (String) -> Unit,
    vm: CollectionsViewModel = viewModel(),
    signedIn: Boolean = false,
    userId: Int = 0,
    initialCollectionId: Int? = null,
    initialTopicId: Int? = null
)

@Composable
fun EssenceScreen(
    onBack: () -> Unit,
    onTopic: (Int) -> Unit,
    onLogin: () -> Unit,
    onOpenLink: (String) -> Unit,
    vm: EssenceViewModel = viewModel(),
    signedIn: Boolean = false,
    userId: Int = 0,
    initialTopicId: Int? = null
)
```

CollectionsViewModel exposes `bind`, `selectTab`, `openCollection`, `backToList`, `load(more = false)`, `openTopic`, `closeTopic`, `edit`, `cancelEdit`, `submit`, `changeMembership`, `removeAll`, and `refreshIfStale`. EssenceViewModel exposes `bind`, `openTopic`, `backToList`, `load(more = false)`, `edit`, `cancelEdit`, `submit`, and `refreshIfStale`. Both expose observable `state`, `detail`, `editor`, `saving`, and `notice` properties.

For collections, `initialCollectionId` opens an album and `initialTopicId` opens the membership/create dialog immediately after account binding. The parent can pass its topic route ID directly, without prompting for a topic number. Essence's `initialTopicId` opens the review detail. `onTopic` opens the actual post in the parent's topic route. `onOpenLink` receives a same-origin page URL for browser fallback. The collection topic picker accepts a positive ID or a linux.sb topic URL. Native creation uses the site's actual "create and include this topic" flow, not standalone empty collection creation.

## Read-Only Evidence

Inspected existing `_grab/home-signed.html` and `_grab/topic-signed.html` first. These older fixtures link the v8.8.0 assets and do not contain the current collection/review forms.

On 2026-09-07, GET-only inspection of the following public/authenticated pages established the selectors and forms reproduced here:

- `/topic_collections?tab=everyone`: `.topic-collections-collection-row`, metadata, public tags, `tab` and `p` pagination.
- `/topic_collections?tab=mine`: the current account has no created/subscribed collections. The site says new collections are created from a topic page.
- `/topic_collection/63`: `.topic-collections-collection-head`, `.topic-collections-topic-list`, and a POST `.topic-collections-subscribe` form at `/topic_collections_action`, carrying `_csrf`, `collection_id`, and `topic_collections_action=subscription_add`.
- `/topic/20314`: `data-topic-collections-add-form` and the creation modal. The create form posts to `/topic_collections_action` with `collection_create_add`, `topic_id`, `name`, `description`, and optional `private=1`. The stated name limit is 80 characters.
- `/topic_essence_review_list`: per-row `.topic-essence-review-progress`, including negative progress, and pagination.
- `/topic/20314`: a voting panel at `data-status=voting`, POST `/topic_essence_review_vote`, `_csrf`, `topic_id`, radio `vote=support|oppose`, and required `reason` with `maxlength=300`. The site states the reason is published as a review reply.
- `/topic/20323`: `pending_approval`, 7/7 progress, no voting form. Reaching the threshold does not itself approve essence.
- The authenticated account's own topic: `not_applied`, a disabled POST `/topic_essence_review_apply` form and the explicit insufficient-body-length reason. No personal topic content or account identifier is retained.
- `/app/assets/plugins.js?v=7f66675e25eb`: `topic_collections_main` reads `option.dataset.included` and switches the actual hidden action to `item_add`, `item_remove`, or `item_remove_all`. The latter requires the rendered `__topic_collections_remove_all__` option.
- `/app/assets/index.js?v=v9.0-topic-types-20260907-2`: generic form submission sends `FormData`, includes only the chosen submitter, and requires JSON `data.ok`.

All fixture names, titles, topic/collection/user IDs, descriptions, avatars, and CSRF values are synthetic replacements. Page furniture, personal sidebars, and unrelated content are removed. Membership choices and the owner-edit test are explicitly synthetic variants of the observed form structure. The edit test uses a fixture-only discriminator and proves that the action supplied by the page is preserved; it does not assert an unobserved live edit action name. All production action URLs and discriminators are read again from live forms before submission. Only the three membership discriminator transformations are implemented explicitly, because they were verified in the site's JavaScript.

## Verification And Limits

`CollectionsEssenceTest` uses injected suspending `CommunityHttp` responses. It never uses the production HTTP implementation or any cookie. It covers parsing, field validation, permissions, fresh CSRF, form identity, multiple submit buttons, same-origin links, strict success confirmation, membership state, disabled membership submitters, and cancellation before posting. The production transport cancels the OkHttp call when its coroutine is cancelled. Account changes invalidate both read and write generations, clear transient forms, and hide previous-session content until binding completes. An old write's completion or cleanup cannot change a later write's state, including an A-to-B-to-A account switch.

The optional `app/src/test/collections-essence/run-offline-check.ps1 -IncludeUi` directly invokes the cached Kotlin compiler and JUnit against existing release classes and cached Android/Compose dependencies. It writes only to a fresh `_tmp/collections-essence-check` directory. It does not invoke Gradle, run a Gradle daemon, or make network requests. Parent integration still needs its normal Gradle build and device verification.

The inspection account owns no collection, so an actual owner edit form and administrator review controls could not be observed. Native edit forms with rendered `name`, `description`, and collection discriminator fields are handled without guessing their actions. Review actions are rendered only when the topic panel supplies the corresponding permission-bearing form. Unsupported browser-only forms (file upload, password, Turnstile, multi-select, external attached controls, or non-AJAX submission) remain disabled with an `onOpenLink` fallback. No live posting, voting, application, creation, subscription, edit, or membership change was performed.
