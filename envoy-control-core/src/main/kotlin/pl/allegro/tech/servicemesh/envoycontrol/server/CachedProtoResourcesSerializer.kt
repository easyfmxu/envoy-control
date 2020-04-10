package pl.allegro.tech.servicemesh.envoycontrol.server

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.protobuf.Any
import com.google.protobuf.Message
import io.envoyproxy.controlplane.server.serializer.ProtoResourcesSerializer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.GuavaCacheMetrics
import pl.allegro.tech.servicemesh.envoycontrol.utils.noopTimer
import java.util.function.Supplier

internal class CachedProtoResourcesSerializer(
    meterRegistry: MeterRegistry,
    reportMetrics: Boolean
) : ProtoResourcesSerializer {

    private val serializeTimer = if (reportMetrics) {
        meterRegistry.timer("protobuf-cache.serialize.time")
    } else {
        noopTimer
    }

    private val cache: Cache<Collection<Message>, MutableCollection<Any>> = if (reportMetrics) {
        GuavaCacheMetrics
            .monitor(
                meterRegistry,
                CacheBuilder.newBuilder()
                    .recordStats()
                    .weakValues()
                    .build<Collection<Message>, MutableCollection<Any>>(),
                "protobuf-cache"
            )
    } else {
        CacheBuilder.newBuilder()
            .weakValues()
            .build<Collection<Message>, MutableCollection<Any>>()
    }

    override fun serialize(resources: MutableCollection<out Message>): MutableCollection<Any> = serializeTimer
        .record(Supplier { getResources(resources) })

    private fun getResources(resources: MutableCollection<out Message>): MutableCollection<Any> {
        // TODO: this is is not effective, because resources is an unknown collection, and we do not know if equals()
        // is implemented properly on that collection. Probably it is not, because this probably be HashMap.Values collection,
        // which doesn't implement equals().
        // We should create a Set from the collection, or cache individual entries, just like in JCP version of CachedProtoResourcesSerializer
        return cache.get(resources) {
            resources.asSequence()
                .map { Any.pack(it) }
                .toMutableList()
        }
    }

    @Suppress("NotImplementedDeclaration")
    override fun serialize(resource: Message?): Any {
        throw NotImplementedError("Serializing single messages is not supported")
    }
}
