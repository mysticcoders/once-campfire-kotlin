package campfire.app

import campfire.assets.Assets
import campfire.assets.StaticRequest
import campfire.kit.Body
import campfire.kit.Headers
import campfire.kit.Middleware
import campfire.kit.Response
import campfire.kit.StaticFile

/** `ActionDispatch::Static` (`crates/campfire/src/app.rs` `public_files`): `public/` and digested `/assets` before routing. */
val PublicFiles = Middleware { request, next ->
    val served = Assets.serve(
        StaticRequest(request.method, request.path, request.headers["accept-encoding"], request.headers["range"], request.headers["if-modified-since"]),
    )
    if (served == null) {
        next.handle(request)
    } else {
        val headers = Headers()
        for ((name, value) in served.headers) headers.add(name, value)
        val body = if (served.bodyOffset == 0 && served.bodyLength == served.body.size) served.body
        else served.body.copyOfRange(served.bodyOffset, served.bodyOffset + served.bodyLength)
        Response(served.status, headers, Body.Bytes(body)).also { it.putExtension(StaticFile) }
    }
}
