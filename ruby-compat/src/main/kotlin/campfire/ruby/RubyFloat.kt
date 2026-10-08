package campfire.ruby

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * A number's significand fills at most this much of `rb_cstr_to_dbl`'s buffer (`DBL_DIG * 4`),
 * and it all at most this much (`sizeof(buf) - 1`).
 */
private const val SIGNIFICAND_WIDTH = 60
private const val BUFFER_WIDTH = 69

/**
 * The most significant digits `ruby_strtod` reads of a fraction (`DBL_DIG * 4`): past them the
 * rest is ignored. Zeros count once a digit follows them.
 */
private const val FRACTION_DIGITS = 60

private const val NONE = '\u0000'

/**
 * `String#to_f` (`rb_cstr_to_dbl` in Ruby 3.4's object.c, which reads up to the first NUL): the
 * number after any leading whitespace, and 0.0 when there's none. An underscore between two digits
 * is skipped, so `"1_000.5"` is 1000.5; `"1.2.3"` is 1.2 and `"1e2"` 100.0. Hexadecimal is read
 * only after a sign: `"-0x1A"` is -26.0, and `"0x1A"` 0.0
 * (`once-campfire-rust/crates/ruby/src/float.rs`, `to_f`).
 */
fun toF(s: String): Double {
    val nul = s.indexOf(NONE).let { if (it < 0) s.length else it }
    val start = skipRubySpace(s.subSequence(0, nul))
    val number = s.substring(start, nul)
    if (isHex(number)) return 0.0
    val (value, end) = strtod(number)
    if (end == 0 || end == number.length) return value
    val compacted = withoutUnderscores(number, end)
    return if (isHex(compacted)) 0.0 else strtod(compacted).value
}

/** A number `strtod` read, and how many characters of its input it took up (0 for none). */
private data class Parsed(val value: Double, val end: Int)

private fun CharSequence.at(i: Int): Char = if (i < length) this[i] else NONE

private fun isHex(s: CharSequence): Boolean = s.at(0) == '0' && (s.at(1) == 'x' || s.at(1) == 'X')

private fun isDigit(c: Char, base: Int): Boolean =
    c in '0'..'9' || (base == 16 && (c in 'a'..'f' || c in 'A'..'F'))

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/**
 * What `rb_cstr_to_dbl` hands `strtod` a second time when the first read stopped short at [end]:
 * the number copied into a fixed buffer with the underscores between digits left out, up to
 * anything else that can't continue it. Significand characters past the buffer's first 60 are
 * dropped, not scaled, so `"1" + "0" * 70 + "x"` is 1e59 (`float.rs`, `without_underscores`).
 * Successive zeros after a leading zero are squeezed into the one.
 */
private fun withoutUnderscores(s: String, end: Int): String {
    val out = StringBuilder(BUFFER_WIDTH)
    var width = SIGNIFICAND_WIDTH
    var base = 10
    var exponentLetter = 'e'
    var dotSeen = false
    var previous = NONE
    var p = 0
    if (s.at(p) == '+' || s.at(p) == '-') {
        previous = s.at(p)
        out.append(previous)
        p++
    }
    if (s.at(p) == '0') {
        previous = '0'
        out.append('0')
        p++
        if (s.at(p) == 'x' || s.at(p) == 'X') {
            previous = 'x'
            out.append('x')
            base = 16
            exponentLetter = 'p'
            p++
        }
        while (s.at(p) == '0') p++
    }
    while (p < end && out.length < width) {
        previous = s[p]
        out.append(previous)
        p++
    }
    while (p < s.length) {
        if (s[p] == '_') {
            p++
            if (out.isEmpty() || !isDigit(previous, base) || !isDigit(s.at(p), base)) break
        }
        previous = s[p]
        p++
        if (width == SIGNIFICAND_WIDTH && (previous == exponentLetter || previous == exponentLetter.uppercaseChar())) {
            width = BUFFER_WIDTH
            out.append(previous)
            if (s.at(p) == '+' || s.at(p) == '-') {
                previous = s.at(p)
                out.append(previous)
                p++
            }
            if (s.at(p) == '0') {
                previous = '0'
                out.append('0')
                while (s.at(p) == '0') p++
            }
            base = 10
            continue
        } else if (isRubySpace(previous)) {
            p = skipRubySpace(s, p)
            if (p < s.length) break
        } else if (previous == '.') {
            if (dotSeen) break
            dotSeen = true
        } else if (!isDigit(previous, base)) {
            break
        }
        if (out.length < width) out.append(previous)
    }
    return out.toString()
}

/**
 * `ruby_strtod` (David Gay's, in Ruby's missing/dtoa.c): the number at the start of [s], and how
 * much of [s] it takes up (0 for none) (`float.rs`, `strtod`). `Double.parseDouble` parses the
 * digits it reads; both round correctly.
 */
private fun strtod(s: String): Parsed {
    var i = skipRubySpace(s)
    val negative = s.at(i) == '-'
    if (s.at(i) == '+' || s.at(i) == '-') i++
    if (i == s.length) return Parsed(0.0, 0)
    if (s.at(i) == '0' && (s.at(i + 1) == 'x' || s.at(i + 1) == 'X')) return hexStrtod(s, i + 2, negative)
    val signedZero = if (negative) -0.0 else 0.0
    val leadingZero = s.at(i) == '0'
    while (s.at(i) == '0') i++
    if (leadingZero && i == s.length) return Parsed(signedZero, i)
    val integerStart = i
    while (s.at(i) in '0'..'9') i++
    val integerEnd = i
    var digits = integerEnd - integerStart
    val fraction = StringBuilder()
    var fractionZeros = false
    if (s.at(i) == '.' && s.at(i + 1) in '0'..'9') {
        i++
        var zeros = 0
        while (s.at(i) in '0'..'9') {
            val digit = s.at(i)
            i++
            if (digits > FRACTION_DIGITS) {
                continue
            } else if (digit == '0') {
                zeros++
                fractionZeros = true
                continue
            }
            repeat(zeros) { fraction.append('0') }
            fraction.append(digit)
            digits += if (digits == 0) 1 else zeros + 1
            zeros = 0
        }
    } else if (s.at(i) == '.') {
        i++
    }
    val anyDigits = digits > 0 || fractionZeros || leadingZero
    var exponent = 0
    if (s.at(i) == 'e' || s.at(i) == 'E') {
        if (!anyDigits) return Parsed(0.0, 0)
        val (value, end) = strtodExponent(s, i)
        exponent = value
        i = end
    }
    if (digits == 0) return if (anyDigits) Parsed(signedZero, i) else Parsed(0.0, 0)
    val number = StringBuilder(integerEnd - integerStart + fraction.length + 8)
    if (negative) number.append('-')
    number.append(s, integerStart, integerEnd).append('.').append(fraction).append('e').append(exponent)
    return Parsed(number.toString().toDouble(), i)
}

/**
 * The exponent at `s[e]` (the `e`), and where it ends; `s[e]` itself when no digit follows. Past
 * 19999 it's 19999, as in Ruby (`float.rs`, `strtod_exponent`).
 */
private fun strtodExponent(s: String, e: Int): Pair<Int, Int> {
    var i = e + 1
    val negative = s.at(i) == '-'
    if (s.at(i) == '+' || s.at(i) == '-') i++
    if (s.at(i) !in '0'..'9') return 0 to e
    while (s.at(i) == '0') i++
    val start = i
    var exponent = 0L
    while (s.at(i) in '0'..'9') {
        if (exponent <= 19999) exponent = exponent * 10 + (s.at(i) - '0')
        i++
    }
    val bounded = if (i - start > 8 || exponent > 19999) 19999 else exponent.toInt()
    return (if (negative) -bounded else bounded) to i
}

/**
 * `ruby_strtod`'s hexadecimal branch, from the digits after `0x` at [start]: hex digits, a
 * fraction and a binary exponent (`p`), added up as doubles the way Ruby does, then scaled
 * (`float.rs`, `hex_strtod`). Ruby stops reading the exponent once it's past where any
 * significand overflows.
 */
private fun hexStrtod(s: String, start: Int, negative: Boolean): Parsed {
    fun signed(value: Double) = if (negative) -value else value
    var i = start
    if (hexValue(s.at(i)) < 0 && s.at(i) != '.') return Parsed(0.0, 0)
    var sum = 0.0
    var weight = 1.0
    var exponent = -4L
    while (s.at(i) == '0') i++
    if (i == s.length) return Parsed(signed(0.0), i)
    while (hexValue(s.at(i)) >= 0) {
        sum += weight * hexValue(s.at(i))
        exponent += 4
        weight /= 16.0
        i++
    }
    if (s.at(i) == '.') {
        i++
        if (hexValue(s.at(i)) >= 0) {
            if (exponent < 0) {
                while (s.at(i) == '0') {
                    i++
                    exponent -= 4
                }
            }
            while (hexValue(s.at(i)) >= 0) {
                sum += weight * hexValue(s.at(i))
                i++
                weight /= 16.0
                if (weight == 0.0) {
                    while (hexValue(s.at(i)) >= 0) i++
                    break
                }
            }
        }
    }
    if (s.at(i) == 'p' || s.at(i) == 'P') {
        i++
        val sign = when (s.at(i)) {
            '-' -> -1L
            '+' -> 1L
            else -> 0L
        }
        if (sign != 0L) i++
        val direction = if (sign == 0L) 1L else sign
        if (s.at(i) !in '0'..'9') return Parsed(0.0, 0)
        var power = 0L
        while (s.at(i) in '0'..'9') {
            power = power * 10 + (s.at(i) - '0')
            i++
            if (power + direction * exponent > 2095) {
                while (s.at(i) in '0'..'9') i++
                break
            }
        }
        exponent += power * direction
    }
    return Parsed(signed(scaleByTwo(sum, exponent)), i)
}

private fun powerOfTwo(n: Long): Double = java.lang.Double.longBitsToDouble((0x3ffL + n) shl 52)

/**
 * `ldexp(value, exponent)`: [value] times 2 to the [exponent], rounded once (musl's `scalbn`;
 * `float.rs`, `scale_by_two`). Scaled into the subnormals so that the last step is the only one
 * that rounds.
 */
private fun scaleByTwo(value: Double, exponent: Long): Double {
    var result = value
    var n = exponent
    if (n > 1023) {
        result *= powerOfTwo(1023)
        n -= 1023
        if (n > 1023) {
            result *= powerOfTwo(1023)
            n = minOf(n - 1023, 1023)
        }
    } else if (n < -1022) {
        result *= powerOfTwo(-1022) * powerOfTwo(53)
        n += 1022 - 53
        if (n < -1022) {
            result *= powerOfTwo(-1022) * powerOfTwo(53)
            n = maxOf(n + 1022 - 53, -1022)
        }
    }
    return result * powerOfTwo(n)
}

/**
 * `Float#to_s`: plain decimals from 1e-4 up to (not including) 1e15, and above that while the
 * shortest digits still reach past the decimal point (`1000000000000000.1`); the exponent form
 * otherwise (`flo_to_s` in Ruby 3.4's numeric.c; `once-campfire-rust/crates/ruby/src/float.rs`,
 * `float_to_s`).
 */
fun floatToS(f: Double): String {
    if (f.isNaN()) return "NaN"
    if (f.isInfinite()) return if (f > 0) "Infinity" else "-Infinity"
    if (f == 0.0) return if (1.0 / f < 0) "-0.0" else "0.0"
    val (digits, decpt) = shortestDigits(Math.abs(f))
    val out = StringBuilder(digits.length + 8)
    if (f < 0) out.append('-')
    if (decpt < -3 || (decpt > 15 && digits.length <= decpt)) {
        val e = decpt - 1
        out.append(digits[0]).append('.')
        if (digits.length == 1) out.append('0') else out.append(digits, 1, digits.length)
        out.append('e').append(if (e < 0) '-' else '+')
        if (Math.abs(e) < 10) out.append('0')
        out.append(Math.abs(e))
    } else if (decpt <= 0) {
        out.append("0.")
        repeat(-decpt) { out.append('0') }
        out.append(digits)
    } else if (decpt >= digits.length) {
        out.append(digits)
        repeat(decpt - digits.length) { out.append('0') }
        out.append(".0")
    } else {
        out.append(digits, 0, decpt).append('.').append(digits, decpt, digits.length)
    }
    return out.toString()
}

/**
 * The shortest digits that read back as [magnitude], and where the decimal point goes in them: of
 * two equally close, the even one, as Ruby's dtoa does (`float.rs`, `shortest_digits`, which
 * corrects Rust's formatting toward that). Java's `Double.toString` already picks the closest and
 * breaks ties to even, but never gives fewer than two digits, so a two-digit answer is replaced by
 * the nearest one-digit decimal when that reads back too (`5e-324`, not Java's `4.9E-324`).
 */
private fun shortestDigits(magnitude: Double): Pair<String, Int> {
    val (digits, decpt) = javaDigits(magnitude.toString())
    if (digits.length == 2) {
        val one = BigDecimal(magnitude).round(MathContext(1, RoundingMode.HALF_EVEN))
        if (one.toDouble() == magnitude) return one.unscaledValue().toString() to 1 - one.scale()
    }
    return digits to decpt
}

/**
 * The significant digits of a `Double.toString` result (`1.2345E6`, `0.001`), without leading or
 * trailing zeros, and where the decimal point goes in them (7 for `1.2345E6`).
 */
private fun javaDigits(formatted: String): Pair<String, Int> {
    val e = formatted.indexOf('E')
    val mantissa = if (e < 0) formatted else formatted.substring(0, e)
    val exponent = if (e < 0) 0 else formatted.substring(e + 1).toInt()
    val dot = mantissa.indexOf('.')
    val all = mantissa.substring(0, dot) + mantissa.substring(dot + 1)
    var first = 0
    while (first < all.length - 1 && all[first] == '0') first++
    var last = all.length
    while (last > first + 1 && all[last - 1] == '0') last--
    return all.substring(first, last) to dot + exponent - first
}
