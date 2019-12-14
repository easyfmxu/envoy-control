package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import com.google.re2j.Matcher
import com.google.re2j.Pattern

class ServiceTagFilter(properties: ServiceTagsProperties = ServiceTagsProperties()) {

    /**
     * We reuse matchers, which are not thread-safe, so we have to use thread locals
     */
    private val localInstance = ThreadLocal.withInitial { ThreadLocalServiceTagFilter(properties) }

    /**
     * Transforms raw instance's tags to tags that should actually be added to instance metadata.
     *  - discards tags that are not suitable for routing
     *  - generates tags combinations that may be used for routing
     *
     * @return sequence of tags that should be added to instance's metadata or null if tag entry should not be added to
     * instance's metadata.
     */
    fun getAllTagsForRouting(serviceName: String, instanceTags: Set<String>): Sequence<String>? = localInstance.get()
        .getAllTagsForRouting(serviceName, instanceTags)
}

private class ThreadLocalServiceTagFilter(properties: ServiceTagsProperties = ServiceTagsProperties()) {

    private val tagsBlacklist: Matcher = properties.routingExcludedTags
            .joinToString("|")
            .let { Pattern.compile(it).matcher("") }
    private val twoTagsCombinationsByService: Map<String, List<Pair<Matcher, Matcher>>>
    private val threeTagsCombinationsByService: Map<String, List<Triple<Matcher, Matcher, Matcher>>>

    init {
        properties.allowedTagsCombinations.forEach {
            if (it.tags.size < 2 || it.tags.size > 3) {
                throw IllegalArgumentException(
                    "A tags combination must contain 2 or 3 tags. Combination with ${it.tags.size} tags found")
            }
        }
        val combinationsByService = properties.allowedTagsCombinations
            .groupBy({ it.serviceName }) { it.tags }

        threeTagsCombinationsByService = combinationsByService
            .mapValues { it.value.filter { it.size == 3 } }
            .mapValues { it.value
                .map { it.sorted().map { Pattern.compile(it).matcher("") } }
                .map { Triple(it[0], it[1], it[2]) }
                .distinct()
            }
            .filterValues { it.isNotEmpty() }

        twoTagsCombinationsByService = combinationsByService
            .mapValues { it.value.filter { it.size == 2 } }
            .mapValues { it.value
                .map { it.sorted().map { Pattern.compile(it).matcher("") } }
                .map { it[0] to it[1] }
                .distinct()
            }
            .mapValues { (serviceName, combinations) ->
                combinations + threeTagsCombinationsByService[serviceName].orEmpty()
                    .flatMap { it.getAllPairs() }
            }
            .filterValues { it.isNotEmpty() }
    }

    fun getAllTagsForRouting(serviceName: String, instanceTags: Set<String>): Sequence<String>? {
        val tags = filterTagsForRouting(instanceTags)
        if (tags.isEmpty()) {
            return null
        }

        val addPairs = isAllowedToMatchOnTwoTags(serviceName)
        val addTriples = isAllowedToMatchOnThreeTags(serviceName)

        val tagsPairs = when (addPairs || addTriples) {
            true -> generatePairs(tags, serviceName)
            false -> emptyList()
        }
        val tagsPairsJoined = when (addPairs) {
            true -> generateJoinedPairs(tagsPairs)
            false -> emptySequence()
        }
        val tagsTriplesJoined = when (addTriples) {
            true -> generateJoinedTriples(tagsPairs, tags, serviceName)
            false -> emptySequence()
        }

        // concatenating sequences avoids unnecessary list allocation
        return tags.asSequence() + tagsPairsJoined + tagsTriplesJoined
    }

    private fun generatePairs(tags: Set<String>, serviceName: String): List<Pair<String, String>> = tags
        .flatMap { tag1 ->
            tags
                .filter { it > tag1 }
                .filter { tag2 -> canBeCombined(serviceName, tag1, tag2) }
                .map { tag2 -> tag1 to tag2 }
        }

    private fun generateJoinedPairs(tagsPairs: List<Pair<String, String >>): Sequence<String> = tagsPairs
        .map { "${it.first},${it.second}" }.asSequence()

    private fun generateJoinedTriples(
        tagsPairs: List<Pair<String, String >>,
        tags: Set<String>,
        serviceName: String
    ): Sequence<String> = tagsPairs
        .flatMap { pair -> tags
            .filter { it > pair.second }
            .filter { tag -> canBeCombined(serviceName, pair.first, pair.second, tag) }
            .map { tag -> "${pair.first},${pair.second},$tag" }
        }
        .asSequence()

    private fun filterTagsForRouting(tags: Set<String>): Set<String> = tags
        .filter { tag -> !matches(tagsBlacklist, tag) }
        .toSet()

    private fun isAllowedToMatchOnTwoTags(serviceName: String): Boolean = twoTagsCombinationsByService
        .contains(serviceName)

    private fun isAllowedToMatchOnThreeTags(serviceName: String): Boolean = threeTagsCombinationsByService
        .contains(serviceName)

    /**
     * Assuming tags are sorted lexicographically, that is: tag1 < tag2
     */
    private fun canBeCombined(serviceName: String, tag1: String, tag2: String): Boolean {
        return twoTagsCombinationsByService[serviceName].orEmpty()
            .any { (pattern1, pattern2) ->
                matches(pattern1, tag1) && matches(pattern2, tag2)
            }
    }

    /**
     * Assuming tags are sorted lexicographically, that is: tag1 < tag2 < tag3
     */
    private fun canBeCombined(serviceName: String, tag1: String, tag2: String, tag3: String): Boolean {
        return threeTagsCombinationsByService[serviceName].orEmpty()
            .any { (pattern1, pattern2, pattern3) ->
                matches(pattern1, tag1) && matches(pattern2, tag2) && matches(pattern3, tag3)
            }
    }

    private fun <T> Triple<T, T, T>.getAllPairs(): List<Pair<T, T>> {
        return listOf(
            this.first to this.second,
            this.first to this.third,
            this.second to this.third
        )
    }

    /**
     * We reuse matchers to avoid creating new matchers every time.
     * Matcher instance is not thread-safe, so we have to ensure given matcher will not be used by multiple threads
     */
    private fun matches(matcher: Matcher, input: String): Boolean = matcher.reset(input).matches()
}
