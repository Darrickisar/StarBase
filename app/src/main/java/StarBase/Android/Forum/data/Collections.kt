package StarBase.Android.Forum.data

enum class CollectionsTab(val value: String, val label: String) {
    EVERYONE("everyone", "大家的淘帖"), MINE("mine", "我的淘帖")
}

data class CollectionCard(
    val id: Int,
    val name: String,
    val description: String = "",
    val author: String = "",
    val authorId: Int = 0,
    val avatar: String = "",
    val metadata: String = "",
    val visibility: String = ""
)

data class CollectionsPage(
    val collections: List<CollectionCard>,
    val tab: CollectionsTab,
    val page: Int,
    val lastPage: Int
)

data class CollectionDetail(
    val collection: CollectionCard,
    val topics: List<TopicCard>,
    val forms: List<CommunityForm>,
    val page: Int,
    val lastPage: Int,
    val canManage: Boolean
)

enum class CommunityFieldType { TEXT, MULTILINE, NUMBER, CHECKBOX, CHOICE }

data class CommunityOption(val value: String, val label: String)

data class CommunityField(
    val name: String,
    val label: String,
    val type: CommunityFieldType,
    val value: String,
    val options: List<CommunityOption> = emptyList(),
    val required: Boolean = false,
    val maxLength: Int? = null,
    val min: Double? = null,
    val max: Double? = null,
    val checked: Boolean = false
)

/** Transient server form. Hidden values, including CSRF, must never be logged or persisted. */
class CommunityForm(
    val key: String,
    val sourceUrl: String,
    val action: String,
    val label: String,
    val hidden: List<Pair<String, String>>,
    val fields: List<CommunityField>,
    val submitter: Pair<String, String>?,
    val enabled: Boolean,
    val unavailableReason: String = "",
    val confirmation: String = ""
) {
    fun initialValues(): Map<String, String> = fields.associate { field ->
        field.name to if (field.type == CommunityFieldType.CHECKBOX && !field.checked) "" else field.value
    }

    override fun toString(): String = "CommunityForm(label=$label, enabled=$enabled)"
}

data class CollectionChoice(val id: Int, val name: String, val included: Boolean)

data class TopicCollections(
    val topicId: Int,
    val topicTitle: String,
    val choices: List<CollectionChoice>,
    val membershipForm: CommunityForm?,
    val createForm: CommunityForm?,
    val canRemoveAll: Boolean,
    val notice: String = ""
)

data class CommunityWriteResult(val message: String, val redirect: String = "")

data class EssenceProgress(val points: Int, val required: Int) {
    val fraction: Float get() = if (required > 0) (points.toFloat() / required).coerceIn(0f, 1f) else 0f
}

data class EssenceEntry(val topic: TopicCard, val progress: EssenceProgress?)

data class EssencePage(val entries: List<EssenceEntry>, val page: Int, val lastPage: Int)

data class EssenceDetail(
    val topicId: Int,
    val title: String,
    val status: String,
    val statusLabel: String,
    val progress: EssenceProgress?,
    val metadata: List<String>,
    val notes: List<String>,
    val forms: List<CommunityForm>
)
