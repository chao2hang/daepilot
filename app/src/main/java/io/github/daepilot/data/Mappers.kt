package io.github.daepilot.data

import io.github.daepilot.core.model.Node
import io.github.daepilot.core.model.NodeProtocol
import io.github.daepilot.core.model.Subscription
import io.github.daepilot.core.model.TrafficInfo
import io.github.daepilot.data.db.NodeEntity
import io.github.daepilot.data.db.SubscriptionEntity

fun SubscriptionEntity.toModel(): Subscription = Subscription(
    id = id,
    name = name,
    url = url,
    enabled = enabled,
    autoUpdate = autoUpdate,
    updateIntervalHours = updateIntervalHours,
    lastUpdatedAt = lastUpdatedAt,
    lastError = lastError,
    traffic = if (uploadBytes == 0L && downloadBytes == 0L && totalBytes == 0L) {
        null
    } else {
        TrafficInfo(uploadBytes, downloadBytes, totalBytes, expireAt)
    },
    nodeCount = nodeCount,
    updateIntervalFromProviderHours = providerIntervalHours,
)

fun Subscription.toEntity(etag: String? = null): SubscriptionEntity = SubscriptionEntity(
    id = id,
    name = name,
    url = url,
    enabled = enabled,
    autoUpdate = autoUpdate,
    updateIntervalHours = updateIntervalHours,
    lastUpdatedAt = lastUpdatedAt,
    lastError = lastError,
    uploadBytes = traffic?.uploadBytes ?: 0,
    downloadBytes = traffic?.downloadBytes ?: 0,
    totalBytes = traffic?.totalBytes ?: 0,
    expireAt = traffic?.expireAtEpochSeconds ?: 0,
    nodeCount = nodeCount,
    providerIntervalHours = updateIntervalFromProviderHours,
    etag = etag,
)

fun NodeEntity.toModel(): Node = Node(
    id = id,
    name = name,
    protocol = runCatching { NodeProtocol.valueOf(protocol) }.getOrDefault(NodeProtocol.UNKNOWN),
    link = link,
    subscriptionId = subscriptionId,
    latencyMs = latencyMs,
    lastTestedAt = lastTestedAt,
    favorite = favorite,
    createdAt = createdAt,
    countryCode = countryCode,
)

fun Node.toEntity(): NodeEntity = NodeEntity(
    id = id,
    name = name,
    protocol = protocol.name,
    link = link,
    subscriptionId = subscriptionId,
    latencyMs = latencyMs,
    lastTestedAt = lastTestedAt,
    favorite = favorite,
    createdAt = createdAt,
    countryCode = countryCode,
)
