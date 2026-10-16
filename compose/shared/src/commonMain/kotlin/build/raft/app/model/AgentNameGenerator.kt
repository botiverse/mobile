package build.raft.app.model

import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

// Word lists for a suggested agent name ("adjective-noun", e.g. `swift-otter`).
// Lowercase ASCII only, no spaces: every pair must pass validateAgentName
// (NAME_REGEX, NAME_MAX_LENGTH, reserved names), which AgentNameGeneratorTest
// checks for the full cross product. The name becomes the agent's @handle.
//
// KEEP IN SYNC with botiverse/slock `packages/shared/src/agentNameGenerator.ts`
// (AGENT_NAME_ADJECTIVES / AGENT_NAME_NOUNS, same words, same order).
// [suggestAgentName] follows the same selection as web `suggestAgentName`
// (slock #10023): pick from the pairs not yet taken, no retry loop.
val AGENT_NAME_ADJECTIVES: List<String> = listOf(
    "amber", "bold", "brave", "breezy", "bright", "brisk", "calm", "candid", "cheery", "clever",
    "cosmic", "crisp", "curious", "daring", "dashing", "deft", "eager", "earnest", "easy", "fancy",
    "fearless", "fleet", "fluent", "frank", "fresh", "gentle", "giddy", "glad", "golden", "grand",
    "happy", "hardy", "hearty", "honest", "humble", "jolly", "jovial", "keen", "kind", "lively",
    "lucid", "lucky", "lunar", "mellow", "merry", "mighty", "misty", "modest", "nimble", "noble",
    "patient", "peppy", "plucky", "polite", "proud", "quick", "quiet", "radiant", "rapid", "ready",
    "regal", "rosy", "rustic", "sage", "serene", "sharp", "shiny", "silent", "silver", "sincere",
    "sleek", "smooth", "snappy", "solar", "sparkly", "spry", "steady", "stellar", "sturdy", "sunny",
    "swift", "tidy", "tranquil", "trusty", "upbeat", "vivid", "warm", "wise", "witty", "zesty",
)

val AGENT_NAME_NOUNS: List<String> = listOf(
    "badger", "beaver", "bison", "comet", "condor", "cougar", "coyote", "crane", "cricket", "dolphin",
    "eagle", "falcon", "ferret", "finch", "firefly", "fox", "gazelle", "gecko", "heron", "ibis",
    "jaguar", "kestrel", "koala", "lark", "lemur", "leopard", "lynx", "magpie", "marten", "meadow",
    "meteor", "mink", "moose", "nebula", "newt", "ocelot", "orca", "osprey", "otter", "owl",
    "panda", "panther", "parrot", "pelican", "penguin", "petrel", "pika", "plover", "puffin", "quail",
    "quasar", "raven", "robin", "salmon", "seal", "sparrow", "squirrel", "starling", "stork", "swallow",
    "hawk", "tapir", "tern", "thrush", "tiger", "toucan", "turtle", "walrus", "weasel", "whale",
    "wombat", "wren", "yak", "zebra", "aurora", "breeze", "canyon", "cedar", "cloud", "delta",
    "ember", "fjord", "glacier", "harbor", "island", "lagoon", "maple", "orbit", "pebble", "summit",
)

private val ALL_AGENT_NAME_PAIRS: List<String> by lazy {
    AGENT_NAME_ADJECTIVES.flatMap { adjective -> AGENT_NAME_NOUNS.map { noun -> "$adjective-$noun" } }
}

private fun <T> pick(items: List<T>, random: () -> Double): T =
    items[min(items.size - 1, floor(random() * items.size).toInt())]

/**
 * A suggested agent name, `adjective-noun`. `exclude` holds names already
 * taken (compared trimmed and case-insensitively). The result is picked
 * uniformly from every adjective-noun pair (adjective-major order) that is not
 * taken. Only when all pairs are taken does it pick from all of them, and
 * the server's uniqueness check decides.
 */
fun suggestAgentName(
    exclude: Iterable<String> = emptyList(),
    random: () -> Double = { Random.nextDouble() },
): String {
    val taken = exclude.mapTo(HashSet()) { it.trim().lowercase() }
    val free = if (taken.isEmpty()) ALL_AGENT_NAME_PAIRS else ALL_AGENT_NAME_PAIRS.filter { it !in taken }
    return pick(free.ifEmpty { ALL_AGENT_NAME_PAIRS }, random)
}
