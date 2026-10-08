package campfire.app

import campfire.db.Reader
import campfire.db.User
import campfire.rails.GlobalId
import campfire.rails.Secrets
import campfire.rails.SignedGlobalId
import campfire.richtext.AttachableResolver
import campfire.richtext.GidLookup
import campfire.richtext.MentionUser
import campfire.richtext.SignedLookup
import java.time.Instant

/**
 * Action Text's record lookups for the rich-text pipeline, backed by the database
 * (`crates/campfire/src/controllers/presenters/rich_text.rs` `DbResolver`): mentions resolve to
 * users through their signed (or, for users only, unverified) GlobalIDs.
 */
class DbResolver(private val r: Reader, private val secrets: Secrets, private val now: Instant) : AttachableResolver {
    override fun locateSigned(sgid: String): SignedLookup {
        val gid = SignedGlobalId.locateSigned(secrets, sgid, SignedGlobalId.ATTACHABLE_PURPOSE, now) ?: return SignedLookup.Invalid
        if (gid.modelName != "User") return SignedLookup.MissingRecord(gid.modelName)
        return findUser(gid.id)?.let { SignedLookup.User(mentionUser(it)) } ?: SignedLookup.MissingRecord(gid.modelName)
    }

    override fun findGid(gid: String): GidLookup {
        val parsed = GlobalId.parse(gid) ?: return GidLookup.NotFound
        return when (parsed.modelName) {
            "User" -> findUser(parsed.id)?.let { GidLookup.User(mentionUser(it)) } ?: GidLookup.NotFound
            "Message", "Room", "Rooms::Open", "Rooms::Closed", "Rooms::Direct", "Boost", "Account" -> GidLookup.OtherModel
            else -> GidLookup.Raises
        }
    }

    /** `GlobalID::Locator` finds records by primary key; ids that aren't integers never match. */
    private fun findUser(id: String): User? = id.toLongOrNull()?.let { r.user(it) }

    private fun mentionUser(user: User) = MentionUser(
        id = user.id,
        name = user.name,
        title = user.title,
        attachableSgid = SignedGlobalId.attachableSgid(secrets, GlobalId("User", user.id)),
        userPath = "/users/${user.id}",
        avatarPath = "/users/${secrets.avatarToken(user.id)}/avatar?v=${user.updatedAt.number()}",
    )
}
