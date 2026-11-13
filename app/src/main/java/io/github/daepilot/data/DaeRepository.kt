package io.github.daepilot.data

import io.github.daepilot.core.config.DaeConfigRenderer
import io.github.daepilot.core.model.AppSnapshot
import io.github.daepilot.core.model.AppSettings
import io.github.daepilot.core.model.DnsConfig
import io.github.daepilot.core.model.Node
import io.github.daepilot.core.model.OutboundGroup
import io.github.daepilot.core.model.daeKey
import io.github.daepilot.core.model.ParsedSubscription
import io.github.daepilot.core.model.RenderSpec
import io.github.daepilot.core.model.RoutingRule
import io.github.daepilot.core.model.RoutingTemplate
import io.github.daepilot.core.model.ShareLink
import io.github.daepilot.core.model.Subscription
import io.github.daepilot.core.model.UserConfig
import io.github.daepilot.core.subscription.SubscriptionParser
import androidx.room.withTransaction
import io.github.daepilot.data.db.AppDatabase
import io.github.daepilot.data.db.NodeDao
import io.github.daepilot.data.db.SubscriptionDao
import io.github.daepilot.data.net.LatencyResult
import io.github.daepilot.data.net.LatencyTester
import io.github.daepilot.data.net.SubscriptionClient
import io.github.daepilot.data.net.SubscriptionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.util.UUID

data class ImportResult(
    val ok: Boolean,
    val message: String,
    val nodeCount: Int = 0,
    val format: String = "",
    val warnings: List<String> = emptyList(),
    val subscriptionId: String? = null,
)

data class SubscriptionPreview(
    val ok: Boolean,
    val message: String,
    val nodeCount: Int = 0,
    val format: String = "",
    val warnings: List<String> = emptyList(),
)

class DaeRepository(
    private val db: AppDatabase,
    private val subscriptionDao: SubscriptionDao,
    private val nodeDao: NodeDao,
    private val settingsStore: SettingsStore,
    private val client: SubscriptionClient,
    private val latencyTester: LatencyTester,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val subscriptions: Flow<List<Subscription>> =
        subscriptionDao.observeAll().map { list -> list.map { it.toModel() } }

    val nodes: Flow<List<Node>> =
        nodeDao.observeAll().map { list -> list.map { it.toModel() } }

    val userConfig: Flow<UserConfig> = settingsStore.config

    val snapshot: StateFlow<AppSnapshot> = combine(
        subscriptions,
        nodes,
        userConfig,
    ) { subs, nodeList, config -> buildSnapshot(subs, nodeList, config) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), AppSnapshot())

    /**
     * Snapshot read straight from the stores. [snapshot] is fed by Room/DataStore
     * invalidation, so it still holds the pre-write state right after a mutator
     * returns — rendering a live config from it can reload dae with stale rules.
     */
    suspend fun snapshotNow(): AppSnapshot = withContext(Dispatchers.IO) {
        buildSnapshot(
            subscriptionDao.getAll().map { it.toModel() },
            nodeDao.getAll().map { it.toModel() },
            settingsStore.current(),
        )
    }

    private fun buildSnapshot(
        subs: List<Subscription>,
        nodeList: List<Node>,
        config: UserConfig,
    ): AppSnapshot {
        val selected = config.selectedNodeId?.takeIf { id -> nodeList.any { it.id == id } }
        val defaultGroup = config.defaultGroupId?.takeIf { id -> config.groups.any { it.id == id } }
        return AppSnapshot(
            subscriptions = subs,
            nodes = nodeList,
            groups = config.groups,
            rules = config.rules,
            template = config.template,
            selectedNodeId = selected,
            defaultGroupId = defaultGroup,
            settings = config.settings,
            dns = config.dns,
        )
    }

    suspend fun addSubscription(name: String, url: String, enabled: Boolean = true): ImportResult {
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            return ImportResult(false, "订阅地址必须以 http:// 或 https:// 开头")
        }
        val now = System.currentTimeMillis()
        val subscription = Subscription(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "未命名订阅" },
            url = trimmedUrl,
            enabled = enabled,
            lastUpdatedAt = now,
        )
        subscriptionDao.upsert(subscription.toEntity())
        val result = refreshSubscription(subscription.id, force = true)
        return result.copy(subscriptionId = subscription.id)
    }

    /** Fetches and parses a subscription without writing it to the database. */
    suspend fun previewSubscription(url: String): SubscriptionPreview =
        withContext(Dispatchers.IO) {
            val trimmed = url.trim()
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                return@withContext SubscriptionPreview(
                    false,
                    "订阅地址必须以 http:// 或 https:// 开头",
                )
            }
            val fetch = try {
                client.fetch(trimmed, null)
            } catch (e: SubscriptionException) {
                return@withContext SubscriptionPreview(false, "无法获取订阅：${e.message}")
            } catch (e: Exception) {
                return@withContext SubscriptionPreview(
                    false,
                    "无法获取订阅：${e.message ?: e.javaClass.simpleName}",
                )
            }
            if (fetch.notModified) {
                return@withContext SubscriptionPreview(false, "服务器返回 304，请重试")
            }
            val parsed = try {
                SubscriptionParser.parse(fetch.body ?: ByteArray(0), null)
            } catch (e: Exception) {
                return@withContext SubscriptionPreview(
                    false,
                    "解析失败：${e.message ?: e.javaClass.simpleName}",
                )
            }
            if (parsed.nodes.isEmpty()) {
                return@withContext SubscriptionPreview(
                    false,
                    parsed.warnings.firstOrNull() ?: "订阅中没有可用节点",
                    warnings = parsed.warnings,
                )
            }
            SubscriptionPreview(
                ok = true,
                message = "可导入 ${parsed.nodes.size} 个节点",
                nodeCount = parsed.nodes.size,
                format = parsed.format,
                warnings = parsed.warnings,
            )
        }

    suspend fun refreshSubscription(id: String, force: Boolean = false): ImportResult =        withContext(Dispatchers.IO) {
            val entity = subscriptionDao.getById(id)
                ?: return@withContext ImportResult(false, "订阅不存在")
            if (!entity.enabled && !force) {
                return@withContext ImportResult(false, "订阅已停用")
            }
            val now = System.currentTimeMillis()
            val fetch = try {
                client.fetch(entity.url, if (force) null else entity.etag)
            } catch (e: SubscriptionException) {
                subscriptionDao.updateResult(
                    id = id,
                    at = now,
                    error = e.message,
                    count = nodeDao.countBySubscription(id),
                    etag = entity.etag,
                )
                return@withContext ImportResult(false, "更新失败：${e.message}")
            }

            val traffic = fetch.traffic
            if (fetch.notModified) {
                subscriptionDao.updateResult(
                    id = id,
                    at = now,
                    error = null,
                    count = nodeDao.countBySubscription(id),
                    etag = entity.etag,
                )
                return@withContext ImportResult(
                    ok = true,
                    message = "订阅未变化",
                    nodeCount = nodeDao.countBySubscription(id),
                    format = "304",
                    subscriptionId = id,
                )
            }

            val parsed: ParsedSubscription = try {
                SubscriptionParser.parse(fetch.body ?: ByteArray(0), entity.name)
            } catch (e: Exception) {
                subscriptionDao.updateResult(
                    id, now, "解析失败：${e.message}", nodeDao.countBySubscription(id), entity.etag,
                )
                return@withContext ImportResult(false, "解析失败：${e.message ?: e.javaClass.simpleName}")
            }

            if (parsed.nodes.isEmpty()) {
                val message = parsed.warnings.firstOrNull() ?: "订阅中没有可用节点"
                subscriptionDao.updateResult(
                    id, now, message, nodeDao.countBySubscription(id), entity.etag,
                )
                return@withContext ImportResult(false, message, warnings = parsed.warnings)
            }

            val existing = nodeDao.getBySubscription(id).associateBy { it.id }
            val nodes = parsed.nodes.map { parsedNode ->
                val nodeId = DaeConfigRenderer.nodeId(id, parsedNode.link)
                val old = existing[nodeId]
                Node(
                    id = nodeId,
                    name = parsedNode.name,
                    protocol = parsedNode.protocol,
                    link = parsedNode.link,
                    subscriptionId = id,
                    latencyMs = old?.latencyMs,
                    lastTestedAt = old?.lastTestedAt ?: 0L,
                    favorite = old?.favorite ?: false,
                    createdAt = old?.createdAt ?: now,
                    countryCode = old?.countryCode,
                    skipCertVerify = old?.skipCertVerify ?: false,
                )
            }

            val updated = entity.copy(
                lastUpdatedAt = now,
                lastError = null,
                nodeCount = nodes.size,
                uploadBytes = traffic?.uploadBytes ?: entity.uploadBytes,
                downloadBytes = traffic?.downloadBytes ?: entity.downloadBytes,
                totalBytes = traffic?.totalBytes ?: entity.totalBytes,
                expireAt = traffic?.expireAtEpochSeconds ?: entity.expireAt,
                providerIntervalHours = fetch.providerIntervalHours ?: entity.providerIntervalHours,
                etag = fetch.etag ?: entity.etag,
            )
            // Atomic swap: deleting then inserting outside a transaction would
            // lose every node of this subscription if the process dies in
            // between. The subscription row update belongs in the same
            // transaction so counts and node rows can never disagree.
            db.withTransaction {
                nodeDao.deleteBySubscription(id)
                nodeDao.upsertAll(nodes.map { it.toEntity() })
                subscriptionDao.upsert(updated)
            }

            ImportResult(
                ok = true,
                message = "已导入 ${nodes.size} 个节点",
                nodeCount = nodes.size,
                format = parsed.format,
                warnings = parsed.warnings,
                subscriptionId = id,
            )
        }

    suspend fun refreshAllDue(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var updated = 0
        for (entity in subscriptionDao.getAll()) {
            if (!entity.enabled || !entity.autoUpdate) continue
            val interval = (entity.providerIntervalHours ?: entity.updateIntervalHours).coerceAtLeast(1)
            val due = entity.lastUpdatedAt == 0L ||
                now - entity.lastUpdatedAt >= interval * 3_600_000L
            if (!due) continue
            val result = refreshSubscription(entity.id)
            if (result.ok && result.format != "304") updated++
        }
        updated
    }

    suspend fun deleteSubscription(id: String) = withContext(Dispatchers.IO) {
        // One transaction: a crash between the two deletes would leave the
        // subscription's nodes orphaned, and renderSpec still emits nodes whose
        // subscription row is gone.
        db.withTransaction {
            nodeDao.deleteBySubscription(id)
            subscriptionDao.delete(id)
        }
        val selected = settingsStore.current().selectedNodeId
        if (selected != null && !nodeDao.exists(selected)) {
            settingsStore.update { it.copy(selectedNodeId = null) }
        }
    }

    suspend fun setSubscriptionEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val entity = subscriptionDao.getById(id) ?: return@withContext
        subscriptionDao.upsert(entity.copy(enabled = enabled))
    }

    suspend fun setSubscriptionAutoUpdate(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val entity = subscriptionDao.getById(id) ?: return@withContext
        subscriptionDao.upsert(entity.copy(autoUpdate = enabled))
    }

    /** Import raw share links (clipboard / file). */
    suspend fun importLinks(text: String, namePrefix: String? = null): ImportResult =
        withContext(Dispatchers.IO) {
            val parsed = SubscriptionParser.parse(
                text.toByteArray(Charsets.UTF_8),
                namePrefix?.takeIf { it.isNotBlank() },
            )
            if (parsed.nodes.isEmpty()) {
                return@withContext ImportResult(
                    false,
                    parsed.warnings.firstOrNull() ?: "没有找到可导入的节点",
                    warnings = parsed.warnings,
                )
            }
            val existing = nodeDao.getAll()
                .map { ShareLink.canonicalize(it.link) }
                .toSet()
            val seen = HashSet<String>()
            val fresh = parsed.nodes.filter { parsedNode ->
                val key = ShareLink.canonicalize(parsedNode.link)
                key !in existing && seen.add(key)
            }
            val skipped = parsed.nodes.size - fresh.size
            if (fresh.isEmpty()) {
                return@withContext ImportResult(
                    true,
                    if (parsed.nodes.size == 1) {
                        "这个节点已经在节点库里了"
                    } else {
                        "没有新节点（${parsed.nodes.size} 个已存在）"
                    },
                    format = parsed.format,
                    warnings = parsed.warnings,
                )
            }
            val now = System.currentTimeMillis()
            val nodes = fresh.map { parsedNode ->
                Node(
                    id = DaeConfigRenderer.nodeId("local", parsedNode.link),
                    name = parsedNode.name,
                    protocol = parsedNode.protocol,
                    link = parsedNode.link,
                    subscriptionId = null,
                    createdAt = now,
                )
            }
            nodeDao.upsertAll(nodes.map { it.toEntity() })
            ImportResult(
                ok = true,
                message = buildString {
                    append("已导入 ${nodes.size} 个本地节点")
                    if (skipped > 0) append("，跳过 $skipped 个重复")
                },
                nodeCount = nodes.size,
                format = parsed.format,
                warnings = parsed.warnings,
            )
        }

    suspend fun deleteNode(id: String) = withContext(Dispatchers.IO) {
        nodeDao.delete(id)
        settingsStore.update { config ->
            if (config.selectedNodeId == id) config.copy(selectedNodeId = null) else config
        }
    }

    suspend fun setFavorite(id: String, favorite: Boolean) = nodeDao.setFavorite(id, favorite)

    suspend fun setNodeSkipCertVerify(id: String, skip: Boolean) =
        nodeDao.setSkipCertVerify(id, skip)

    suspend fun selectNode(id: String?) = settingsStore.update {
        it.copy(selectedNodeId = id, defaultGroupId = null)
    }

    suspend fun selectGroup(id: String) = settingsStore.update {
        it.copy(defaultGroupId = id, selectedNodeId = null)
    }

    suspend fun selectAuto() = settingsStore.update {
        it.copy(selectedNodeId = null, defaultGroupId = null)
    }

    suspend fun migrateLegacyDns() = settingsStore.migrateLegacyDns()

    suspend fun setTemplate(template: RoutingTemplate) =
        settingsStore.update { it.copy(template = template) }

    suspend fun setDns(dns: DnsConfig) = settingsStore.update { it.copy(dns = dns) }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) =
        settingsStore.update { it.copy(settings = transform(it.settings)) }

    suspend fun upsertRule(rule: RoutingRule) = settingsStore.update { config ->
        val existing = config.rules.indexOfFirst { it.id == rule.id }
        val rules = config.rules.toMutableList()
        if (existing >= 0) rules[existing] = rule else rules += rule
        config.copy(rules = rules)
    }

    suspend fun deleteRule(id: String) = settingsStore.update { config ->
        config.copy(rules = config.rules.filterNot { it.id == id })
    }

    suspend fun moveRule(id: String, offset: Int) = settingsStore.update { config ->
        val index = config.rules.indexOfFirst { it.id == id }
        if (index < 0) return@update config
        val target = (index + offset).coerceIn(0, config.rules.lastIndex)
        if (target == index) return@update config
        val rules = config.rules.toMutableList()
        val rule = rules.removeAt(index)
        rules.add(target, rule)
        config.copy(rules = rules)
    }

    suspend fun duplicateRule(id: String) = settingsStore.update { config ->
        val index = config.rules.indexOfFirst { it.id == id }
        if (index < 0) return@update config
        val rules = config.rules.toMutableList()
        rules.add(index + 1, config.rules[index].copy(id = UUID.randomUUID().toString()))
        config.copy(rules = rules)
    }

    suspend fun setRuleEnabled(id: String, enabled: Boolean) = settingsStore.update { config ->
        config.copy(rules = config.rules.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    suspend fun upsertGroup(group: OutboundGroup) = settingsStore.update { config ->
        val incoming = group.withStableKey(config.groups)
        val existing = config.groups.indexOfFirst { it.id == incoming.id }
        val groups = config.groups.toMutableList()
        if (existing >= 0) groups[existing] = incoming else groups += incoming
        config.copy(groups = groups)
    }

    suspend fun deleteGroup(id: String) = settingsStore.update { config ->
        config.copy(
            groups = config.groups.filterNot { it.id == id },
            defaultGroupId = config.defaultGroupId?.takeIf { it != id },
        )
    }

    /** Assigns the dae identifier once so renaming the display name cannot break rules. */
    private fun OutboundGroup.withStableKey(existing: List<OutboundGroup>): OutboundGroup {
        if (key.isNotBlank()) return this
        val base = daeKey()
        val used = existing.filter { it.id != id }.map { it.daeKey() }.toSet()
        var candidate = base
        var suffix = 2
        while (candidate in used) {
            candidate = "${base}_$suffix"
            suffix++
        }
        return copy(key = candidate)
    }

    suspend fun testAllNodes(): List<LatencyResult> = withContext(Dispatchers.IO) {
        val all = nodeDao.getAll().map { it.toModel() }
        if (all.isEmpty()) return@withContext emptyList()
        val results = latencyTester.test(all)
        persistLatency(results)
        results
    }

    suspend fun testNode(id: String): LatencyResult? = withContext(Dispatchers.IO) {
        val entity = nodeDao.getById(id) ?: return@withContext null
        val result = latencyTester.probe(entity.toModel())
        persistLatency(listOf(result))
        result
    }

    private suspend fun persistLatency(results: List<LatencyResult>) {
        val now = System.currentTimeMillis()
        // Single transaction instead of one UPDATE round-trip per node. Failed
        // probes keep their timestamp too: it is the only thing that lets the
        // list tell a node that was tested from one that never was.
        db.withTransaction {
            results.forEach { result ->
                nodeDao.updateLatency(result.nodeId, result.latencyMs, now)
            }
        }
    }

    fun renderSpec(snapshot: AppSnapshot): RenderSpec {
        val disabledSubscriptions = snapshot.subscriptions
            .filterNot { it.enabled }
            .map { it.id }
            .toSet()
        val renderNodes = snapshot.nodes
            .filter { it.subscriptionId == null || it.subscriptionId !in disabledSubscriptions }
            .sortedWith(compareByDescending<Node> { it.favorite }.thenBy { it.name.lowercase() })
        val selected = snapshot.selectedNodeId?.takeIf { id -> renderNodes.any { it.id == id } }
        val defaultGroupId = snapshot.defaultGroupId?.takeIf { id ->
            snapshot.groups.any { it.id == id && it.enabled }
        }
        return RenderSpec(
            nodes = renderNodes,
            groups = snapshot.groups,
            selectedNodeId = selected,
            defaultGroupId = defaultGroupId,
            template = snapshot.template,
            customRules = snapshot.rules,
            dns = snapshot.dns,
            tproxyPort = snapshot.settings.tproxyPort,
            logLevel = snapshot.settings.logLevel,
            bypassPackages = snapshot.settings.bypassPackages,
            lanInterface = if (snapshot.settings.tetherShare) "auto" else null,
        )
    }

    fun render(snapshot: AppSnapshot): String = DaeConfigRenderer.render(renderSpec(snapshot))
}
