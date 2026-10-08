package campfire.app.browser

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TextNode
import java.io.File

/**
 * `vectors/campfire_user_agents.json` (produced by reference-tools/campfire/user_agents.rb in the
 * Rust repo) and the comparison the Rust tests in `concerns/user_agent.rs` make against it.
 */
internal object UserAgentVectors {
    val root: JsonNode by lazy {
        ObjectMapper().readTree(File(System.getProperty("campfire.vectors"), "campfire_user_agents.json"))
    }

    /** Every User-Agent in the vectors (null for the nil one). */
    val userAgents: List<String?> by lazy { root["user_agents"].map { it["ua"].textValue() } }

    /**
     * Compares a Ruby value (`{"error": ...}` when it raised) with ours: [actual] is run, and a
     * [Raised] stands for the error.
     */
    fun check(failures: MutableList<String>, context: String, expected: JsonNode?, actual: () -> Any?) {
        val result = try {
            Result.success(json(actual()))
        } catch (e: Raised) {
            Result.failure(e)
        }
        val expectsError = expected != null && expected.isObject && expected.has("error")
        val ok = if (expectsError) result.isFailure else result.isSuccess && result.getOrNull() == expected
        if (!ok) failures.add("$context: expected $expected, got ${result.getOrNull() ?: "raised"}")
    }

    /** Our value as JSON: strings, booleans and nil. */
    fun json(value: Any?): JsonNode = when (value) {
        null -> NullNode.instance
        is String -> TextNode(value)
        is Boolean -> BooleanNode.valueOf(value)
        is Version -> TextNode(value.toString())
        else -> error("unexpected $value")
    }
}
