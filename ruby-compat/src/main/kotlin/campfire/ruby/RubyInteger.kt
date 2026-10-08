package campfire.ruby

import java.math.BigInteger

private const val NEGATIVE_LIMIT_DIV_10 = Long.MIN_VALUE / 10

/** Rust's `i128::MAX`, where `to_i128` saturates (`once-campfire-rust/crates/ruby/src/integer.rs`). */
internal val I128_MAX: BigInteger = BigInteger.ONE.shiftLeft(127) - BigInteger.ONE

/**
 * `String#to_i`, saturating at the Long bounds where Ruby goes on to a Bignum
 * (`once-campfire-rust/crates/ruby/src/integer.rs`, `to_i`).
 */
fun toI(s: String): Long =
    parseInteger(s, onOverflow = { negative -> if (negative) Long.MIN_VALUE else Long.MAX_VALUE }, onValue = { it })

/**
 * `String#to_i`, or null where Ruby's answer doesn't fit in a Long
 * (`once-campfire-rust/crates/ruby/src/integer.rs`, `to_i_checked`).
 */
fun toIChecked(s: String): Long? = parseInteger(s, onOverflow = { null }, onValue = { it })

/**
 * A string as Active Record binds it for an integer column (`find`, `find_by(id:)`, `where`):
 * `ActiveModel::Type::Integer#serialize`, with the SQLite adapter's 8-byte limit. Null unless it
 * starts like a number (`/\A\s*[+-]?\d/`), then `to_i`, and null out of range, where Rails raises
 * (activemodel's `type/integer.rb`; `once-campfire-rust/crates/ruby/src/integer.rs`,
 * `integer_cast`).
 */
fun integerCast(s: String): Long? {
    var i = skipRubySpace(s)
    if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
    if (i >= s.length || s[i] !in '0'..'9') return null
    return toIChecked(s)
}

/**
 * `String#to_i` as Rust's `to_i128` reads it (`once-campfire-rust/crates/ruby/src/integer.rs`):
 * saturating at `i128::MAX` in magnitude, which is wide enough to tell every Long, and every file
 * size, from what's past it. Digits past the saturation point are skipped rather than multiplied
 * in.
 */
internal fun toI128(s: String): BigInteger {
    var number = BigInteger.ZERO
    var saturated = false
    val negative = scanDigits(s) { digit ->
        if (!saturated) {
            number = number.multiply(BigInteger.TEN).add(BigInteger.valueOf(digit.toLong()))
            if (number > I128_MAX) {
                number = I128_MAX
                saturated = true
            }
        }
    }
    return if (negative) number.negate() else number
}

/**
 * `String#to_i` into a Long, accumulated negatively so that `Long.MIN_VALUE` fits, handing the
 * result to [onValue], or the sign to [onOverflow] when it doesn't fit.
 */
private inline fun <T> parseInteger(s: String, onOverflow: (Boolean) -> T, onValue: (Long) -> T): T {
    var accumulated = 0L
    var overflow = false
    val negative = scanDigits(s) { digit ->
        if (!overflow) {
            if (accumulated < NEGATIVE_LIMIT_DIV_10 || accumulated * 10 < Long.MIN_VALUE + digit) {
                overflow = true
            } else {
                accumulated = accumulated * 10 - digit
            }
        }
    }
    return when {
        overflow -> onOverflow(negative)
        negative -> onValue(accumulated)
        accumulated == Long.MIN_VALUE -> onOverflow(false)
        else -> onValue(-accumulated)
    }
}

/**
 * The shape `String#to_i` reads (`to_i128` in `integer.rs`): optional leading whitespace and
 * sign, an optional `0d`, then digits, an underscore allowed between two. Calls [onDigit] with
 * each digit's value and returns whether the sign was `-`.
 */
private inline fun scanDigits(s: String, onDigit: (Int) -> Unit): Boolean {
    var i = skipRubySpace(s)
    val negative = i < s.length && s[i] == '-'
    if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
    if (i + 1 < s.length && s[i] == '0' && (s[i + 1] == 'd' || s[i + 1] == 'D')) i += 2
    var previousDigit = false
    while (i < s.length) {
        val c = s[i]
        if (c in '0'..'9') {
            onDigit(c - '0')
            previousDigit = true
        } else if (c == '_' && previousDigit) {
            previousDigit = false
        } else {
            break
        }
        i++
    }
    return negative
}
