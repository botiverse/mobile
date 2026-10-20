package build.raft.app.model

import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

// Classic first names for a suggested agent name (the old adjective-noun pairs
// such as `misty-lagoon` read as noise). ASCII letters only, capitalized like
// the onboarding assistant "Cindy" (deliberately not in the list). Every name,
// bare or paired with an adjective, must pass validateAgentName, which
// AgentNameGeneratorTest checks. The name becomes the agent's @handle.
//
// KEEP IN SYNC with botiverse/slock `packages/shared/src/agentNameGenerator.ts`
// (AGENT_CLASSIC_NAMES / AGENT_NAME_ADJECTIVES, same words, same order).
// [suggestAgentName] follows the same selection as web `suggestAgentName`.
val AGENT_CLASSIC_NAMES: List<String> = listOf(
    "Ada", "Adam", "Alan", "Albert", "Alice", "Amelia", "Amy", "Andrew", "Anna", "Anne",
    "Arthur", "Audrey", "Barbara", "Beatrice", "Ben", "Bella", "Benjamin", "Betty", "Bruce", "Caroline",
    "Carl", "Catherine", "Charles", "Charlotte", "Chloe", "Claire", "Clara", "Daisy", "Daniel", "David",
    "Diana", "Dorothy", "Edgar", "Edith", "Edward", "Eleanor", "Elena", "Eliza", "Ella", "Emily",
    "Emma", "Eric", "Ethan", "Eva", "Felix", "Fiona", "Frank", "George", "Grace", "Hannah",
    "Harry", "Hazel", "Helen", "Henry", "Hugo", "Ian", "Iris", "Isaac", "Isabel", "Ivy",
    "Jack", "Jacob", "James", "Jane", "Jasper", "Jean", "Jessica", "Joan", "John", "Jonathan",
    "Joseph", "Judith", "Julia", "Julian", "Kate", "Laura", "Leo", "Leon", "Lily", "Linda",
    "Lionel", "Louis", "Louise", "Lucas", "Lucy", "Luke", "Lydia", "Margaret", "Maria", "Martha",
    "Martin", "Mary", "Matthew", "Max", "Maya", "Michael", "Miles", "Molly", "Nancy", "Nathan",
    "Nina", "Noah", "Nora", "Oliver", "Olivia", "Oscar", "Owen", "Patrick", "Paul", "Peter",
    "Philip", "Rachel", "Ralph", "Rebecca", "Richard", "Robert", "Rose", "Ruby", "Ruth", "Sam",
    "Samuel", "Sarah", "Simon", "Sophia", "Sophie", "Stella", "Susan", "Thomas", "Tom", "Victor",
    "Victoria", "Vincent", "Violet", "Walter", "William", "Zoe",
)

// Adjectives that tell agents with the same first name apart: once every name
// is taken, the suggestion becomes `Swift-Leo`.
val AGENT_NAME_ADJECTIVES: List<String> = listOf(
    "Amber", "Bold", "Brave", "Breezy", "Bright", "Brisk", "Calm", "Candid", "Cheery", "Clever",
    "Cosmic", "Crisp", "Curious", "Daring", "Dashing", "Deft", "Eager", "Earnest", "Easy", "Fancy",
    "Fearless", "Fleet", "Fluent", "Frank", "Fresh", "Gentle", "Giddy", "Glad", "Golden", "Grand",
    "Happy", "Hardy", "Hearty", "Honest", "Humble", "Jolly", "Jovial", "Keen", "Kind", "Lively",
    "Lucid", "Lucky", "Lunar", "Mellow", "Merry", "Mighty", "Misty", "Modest", "Nimble", "Noble",
    "Patient", "Peppy", "Plucky", "Polite", "Proud", "Quick", "Quiet", "Radiant", "Rapid", "Ready",
    "Regal", "Rosy", "Rustic", "Sage", "Serene", "Sharp", "Shiny", "Silent", "Silver", "Sincere",
    "Sleek", "Smooth", "Snappy", "Solar", "Sparkly", "Spry", "Steady", "Stellar", "Sturdy", "Sunny",
    "Swift", "Tidy", "Tranquil", "Trusty", "Upbeat", "Vivid", "Warm", "Wise", "Witty", "Zesty",
)

private val ALL_AGENT_NAME_PAIRS: List<String> by lazy {
    AGENT_NAME_ADJECTIVES.flatMap { adjective -> AGENT_CLASSIC_NAMES.map { name -> "$adjective-$name" } }
}

private fun <T> pick(items: List<T>, random: () -> Double): T =
    items[min(items.size - 1, floor(random() * items.size).toInt())]

/**
 * A suggested agent name. `exclude` holds names already taken by agents or
 * members (compared trimmed and case-insensitively). Picks a classic first
 * name not yet taken (`Leo`); once every name is taken, an adjective plus a
 * name not yet taken (`Swift-Leo`, adjective-major order); only when all of
 * those are taken too does it add the first free number (`Swift-Leo2`), so
 * the suggestion never collides with a name in `exclude`.
 */
fun suggestAgentName(
    exclude: Iterable<String> = emptyList(),
    random: () -> Double = { Random.nextDouble() },
): String {
    val taken = exclude.mapTo(HashSet()) { it.trim().lowercase() }
    fun isFree(name: String) = name.lowercase() !in taken
    AGENT_CLASSIC_NAMES.filter(::isFree).ifEmpty { null }?.let { return pick(it, random) }
    ALL_AGENT_NAME_PAIRS.filter(::isFree).ifEmpty { null }?.let { return pick(it, random) }
    val base = pick(ALL_AGENT_NAME_PAIRS, random)
    var suffix = 2
    while (!isFree("$base$suffix")) suffix += 1
    return "$base$suffix"
}
