package io.github.daepilot.data

import io.github.daepilot.core.config.DaeConfigRenderer
import io.github.daepilot.core.model.AppSnapshot
import io.github.daepilot.core.model.AppSettings
import io.github.daepilot.core.model.DnsConfig
import io.github.daepilot.core.model.Node
import io.github.daepilot.core.model.OutboundGroup
import io.github.daepilot.core.model.ParsedSubscription
import io.github.daepilot.core.model.RenderSpec
import io.github.daepilot.core.model.RoutingRule
import io.github.daepilot.core.model.RoutingTemplate
import io.github.daepilot.core.model.Subscription
import io.github.daepilot.core.model.UserConfig
import io.github.daepilot.core.subscription.SubscriptionParser
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

class DaeRepository(
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
    ) { subs, nodeList, config ->
        val selected = config.selectedNodeId?.takeIf { id -> nodeList.any { it.id == id } }
        AppSnapshot(
            subscriptions = subs,
            nodes = nodeList,
            groups = config.groups,
            rules = config.rules,
            template = config.template,
            selectedNodeId = selected,
            settings = config.settings,
            dns = config.dns,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), AppSnapshot())

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

    suspend fun refreshSubscription(id: String, force: Boolean = false): ImportResult =
        withContext(Dispatchers.IO) {
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
                )
            }

            nodeDao.deleteBySubscription(id)
            nodeDao.upsertAll(nodes.map { it.toEntity() })
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
            subscriptionDao.upsert(updated)

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
        nodeDao.deleteBySubscription(id)
        subscriptionDao.delete(id)
        val remaining = nodeDao.getAll().map { it.id }.toSet()
        settingsStore.update { config ->
            config.copy(selectedNodeId = config.selectedNodeId?.takeIf { it in remaining })
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
            val now = System.currentTimeMillis()
            val nodes = parsed.nodes.map { parsedNode ->
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
                message = "已导入 ${nodes.size} 个本地节点",
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

    suspend fun selectNode(id: String?) = settingsStore.update { it.copy(selectedNodeId = id) }

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

    suspend fun setRuleEnabled(id: String, enabled: Boolean) = settingsStore.update { config ->
        config.copy(rules = config.rules.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    suspend fun upsertGroup(group: OutboundGroup) = settingsStore.update { config ->
        val existing = config.groups.indexOfFirst { it.id == group.id }
        val groups = config.groups.toMutableList()
        if (existing >= 0) groups[existing] = group else groups += group
        config.copy(groups = groups)
    }

    suspend fun deleteGroup(id: String) = settingsStore.update { config ->
        config.copy(groups = config.groups.filterNot { it.id == id })
    }

    suspend fun testAllNodes(): List<LatencyResult> = withContext(Dispatchers.IO) {
        val all = nodeDao.getAll().map { it.toModel() }
        if (all.isEmpty()) return@withContext emptyList()
        val results = latencyTester.test(all)
        persistLatency(results)
        results
    }

    suspend fun testNode(id: String): LatencyResult? = withContext(Dispatchers.IO) {
        val entity = nodeDao.getAll().firstOrNull { it.id == id } ?: return@withContext null
        val result = latencyTester.probe(entity.toModel())
        persistLatency(listOf(result))
        result
    }

    private suspend fun persistLatency(results: List<LatencyResult>) {
        val now = System.currentTimeMillis()
        results.forEach { result ->
            nodeDao.updateLatency(result.nodeId, result.latencyMs, if (result.latencyMs != null) now else 0L)
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
        return RenderSpec(
            nodes = renderNodes,
            groups = snapshot.groups,
            selectedNodeId = selected,
            template = snapshot.template,
            customRules = snapshot.rules,
            dns = snapshot.dns,
            tproxyPort = snapshot.settings.tproxyPort,
            logLevel = snapshot.settings.logLevel,
            bypassPackages = snapshot.settings.bypassPackages,
        )
    }

    fun render(snapshot: AppSnapshot): String = DaeConfigRenderer.render(renderSpec(snapshot))
}
