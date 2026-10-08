package campfire.richtext

/**
 * Something Ruby would have raised while rendering (`once-campfire-rust/crates/richtext/src/lib.rs`,
 * `Error`). Callers mirror what the Rails code does with the exception: `message_presentation`
 * rescues everything and renders "". No stack trace is captured, as these are expected outcomes.
 */
sealed class RichTextError(message: String) : RuntimeException(message, null, false, false) {
    /** Nokogiri's `ArgumentError` for markup past one of Gumbo's limits. */
    class Parse(val error: ParseError) : RichTextError(error.message)

    /** Any other exception the Ruby raised, named by [what]. */
    class Raised(val what: String) : RichTextError("raised $what")

    /** Raised with a message that isn't valid UTF-8, so the rescue's own logging raises too. */
    class Unrenderable(val what: String) : RichTextError("raised $what with an unloggable message")
}
