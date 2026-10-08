package campfire.routes

import kotlin.test.Test

/**
 * A timing harness for recognition, after `times_recognition` in
 * `crates/campfire/src/controllers.rs`: the paths the page benchmarks request, `/up`, Active
 * Storage URLs and a 404, each recognized 1M times after a warm-up (5 samples of 200,000), printed as the median
 * ns/op. It asserts
 * nothing about time.
 */
class RecognitionBenchmarkTest {
    /** Times `RouteTable.recognize` (and `normalizePath` on an already normal path). */
    @Test
    fun timesRecognition() {
        val table = RouteTable.campfire
        val longFilename = "/rails/active_storage/blobs/redirect/abc--def/" + "dir/café.tar/".repeat(160) + "photo.png"
        val paths = listOf(
            "room_show" to "/rooms/12",
            "messages_page" to "/rooms/12/messages",
            "search" to "/searches",
            "up" to "/up",
            "representation" to "/rails/active_storage/representations/redirect/eyJfcmFpbHMiOnsiZGF0YSI6NDIsInB1ciI6ImJsb2JfaWQifX0=--4c2a1f0e9b8d7c6a5f4e3d2c1b0a9f8e7d6c5b4a/eyJfcmFpbHMiOnsiZGF0YSI6eyJmb3JtYXQiOiJ3ZWJwIiwicmVzaXplX3RvX2xpbWl0IjpbMTIwMCwxMjAwXX0sInB1ciI6InZhcmlhdGlvbiJ9fQ==--0f1e2d3c4b5a69788796a5b4c3d2e1f0a1b2c3d4/photo.png",
            "long filename" to longFilename,
            "404" to "/wp-login.php",
        )
        val samples = 5
        val iterations = 200_000
        var sink = 0
        for ((_, path) in paths) repeat(iterations) { sink += outcome(table.recognize("GET", normalizePath(path))) }
        for ((name, path) in paths) {
            val nanos = DoubleArray(samples) {
                val started = System.nanoTime()
                repeat(iterations) { sink += outcome(table.recognize("GET", normalizePath(path))) }
                (System.nanoTime() - started).toDouble() / iterations
            }.sorted()
            println("recognition %-15s median %8.1f ns/op (min %.1f, max %.1f)".format(name, nanos[samples / 2], nanos.first(), nanos.last()))
        }
        println("recognition sink $sink")
    }

    /** A cheap digest of a recognition, so the work isn't optimized away (`hashCode` would hash the params). */
    private fun outcome(recognition: Recognition): Int = (recognition as? Recognition.Matched)?.params?.size ?: 0
}
