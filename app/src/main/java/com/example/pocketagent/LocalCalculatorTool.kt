package com.example.pocketagent

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.Stack
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pure local offline mathematical calculator and unit converter for PocketAgent.
 *
 * Invariants:
 * - 100% offline, executes on-device without scripting engines or reflection.
 * - Secure Shunting-yard & Pratt token-based evaluation with strict cycle bounds.
 * - Supports arithmetic, standard functions, percentages, and common unit conversions.
 */
object LocalCalculatorTool {

    data class CalculationResult(
        val expression: String,
        val numericValue: Double,
        val formattedResult: String,
        val unitLabel: String? = null,
    ) {
        val displayOutput: String
            get() = if (unitLabel != null) "$formattedResult $unitLabel" else formattedResult
    }

    private val numberFormat = DecimalFormat("#,##0.######", DecimalFormatSymbols(Locale.US))

    private val DATE_PATTERN = Regex("""\b(?:\d{4}[-/]\d{1,2}[-/]\d{1,2}|\d{1,2}[-/]\d{1,2}[-/]\d{2,4})\b""")
    private val PERCENT_PATTERN = Regex("""(?i)^\s*(\d+(?:\.\d+)?)\s*(?:%|percent)\s+(?:of)\s+(\d+(?:\.\d+)?)\s*$""")
    private val EMBEDDED_PERCENT_PATTERN = Regex(
        """(?i)(?<![\d.])(\d+(?:\.\d+)?)\s*(?:%|percent)\s+of\s+(\d+(?:\.\d+)?)(?![\d.])"""
    )
    private val CONVERSION_PATTERN = Regex(
        """(?i)^\s*([+-]?\d+(?:\.\d+)?)\s*([a-zA-Z°]+)\s+(?:to|in|into)\s+([a-zA-Z°]+)\s*$"""
    )
    private val EMBEDDED_CONVERSION_PATTERN = Regex(
        """(?i)(?<![\dA-Za-z.-])([+-]?\d+(?:\.\d+)?)\s*([a-zA-Z°]+)\s+(?:to|in|into)\s+([a-zA-Z°]+)(?![A-Za-z])"""
    )
    private val REVERSE_CONVERSION_PATTERN = Regex(
        """(?i)\bhow\s+many\s+([a-zA-Z°]+)\s+(?:is|are|in)\s+([+-]?\d+(?:\.\d+)?)\s*([a-zA-Z°]+)(?![A-Za-z])"""
    )
    private val PROMPT_PREFIX_PATTERN = Regex(
        """(?i)^(?:(?:hey[,:\s]+)?(?:please\s+|can\s+you\s+|could\s+you\s+)*(?:please\s+)?)?(?:what\s+is|what's|whats|calculate|compute|evaluate|solve|find)\s+(.+?)[?!.\s]*$"""
    )
    private val FUNCTION_CALL_PATTERN = Regex("""(?i)\b(?:sqrt|abs|round|sin|cos|tan|log|ln)\s*\(""")
    private const val MATH_TOKEN_PIECE =
        """(?:(?i:sqrt|abs|round|sin|cos|tan|log|ln)\s*\(|(?i:\bpi\b)|π|\be\b|\d+(?:\.\d+)?|[+\-*/×÷%^()])"""
    private const val MATH_TERM_END = """(?:(?i:\bpi\b)|π|\be\b|\d+(?:\.\d+)?|\))"""
    private val MATH_SEGMENT_PATTERN = Regex(
        """(?<![A-Za-z0-9_./])(?:$MATH_TOKEN_PIECE\s*)+$MATH_TERM_END(?![A-Za-z0-9_/]|\.\d)"""
    )

    /**
     * Tries to evaluate a string as either a math expression or a unit conversion,
     * falling back to [extractEmbeddedCalculation] for natural-language questions.
     * Returns null if the text cannot be safely parsed.
     */
    fun tryEvaluate(input: String): CalculationResult? {
        val trimmed = input.trim()
        if (trimmed.length < 2 || trimmed.length > 300) return null
        if (DATE_PATTERN.matches(trimmed)) return null

        // 1. Check unit conversions
        val unitResult = evaluateUnitConversion(trimmed)
        if (unitResult != null) return unitResult

        // 2. Check percentage pattern "X% of Y" or "X percent of Y"
        val pctResult = evaluatePercentage(trimmed)
        if (pctResult != null) return pctResult

        // 3. Try standard mathematical evaluation
        val hasMathChars = trimmed.any { it.isDigit() || it in "+-*/%^" }
        if (hasMathChars && !DATE_PATTERN.containsMatchIn(trimmed)) {
            val direct = try {
                evaluateMathExpression(trimmed)
            } catch (_: Exception) {
                null
            }
            if (direct != null) return direct
        }

        // 4. Natural-question extraction fallback
        return extractEmbeddedCalculation(trimmed)
    }

    /**
     * Extracts embedded percentages, unit conversions, or explicit arithmetic expressions
     * from natural-language questions while avoiding dates and plain prose sentences.
     */
    fun extractEmbeddedCalculation(input: String): CalculationResult? {
        val trimmed = input.trim()
        if (trimmed.length < 3 || trimmed.length > 300) return null

        // 1. Embedded unit conversions (e.g., "convert 10 km to miles", "what is 100 F to C?")
        for (match in EMBEDDED_CONVERSION_PATTERN.findAll(trimmed)) {
            val value = match.groupValues[1].toDoubleOrNull() ?: continue
            val fromUnit = match.groupValues[2].lowercase(Locale.US).replace("°", "")
            val toUnit = match.groupValues[3].lowercase(Locale.US).replace("°", "")
            val converted = convertUnits(value, fromUnit, toUnit) ?: continue
            if (!converted.first.isNaN() && !converted.first.isInfinite()) {
                return CalculationResult(
                    expression = match.value.trim(),
                    numericValue = converted.first,
                    formattedResult = numberFormat.format(converted.first),
                    unitLabel = converted.second,
                )
            }
        }

        for (match in REVERSE_CONVERSION_PATTERN.findAll(trimmed)) {
            val toUnit = match.groupValues[1].lowercase(Locale.US).replace("°", "")
            val value = match.groupValues[2].toDoubleOrNull() ?: continue
            val fromUnit = match.groupValues[3].lowercase(Locale.US).replace("°", "")
            val converted = convertUnits(value, fromUnit, toUnit) ?: continue
            if (!converted.first.isNaN() && !converted.first.isInfinite()) {
                val expr = "${match.groupValues[2]} ${match.groupValues[3]} to ${match.groupValues[1]}"
                return CalculationResult(
                    expression = expr,
                    numericValue = converted.first,
                    formattedResult = numberFormat.format(converted.first),
                    unitLabel = converted.second,
                )
            }
        }

        // 2. Embedded percentage queries (e.g., "What is 15% of 2400?")
        for (match in EMBEDDED_PERCENT_PATTERN.findAll(trimmed)) {
            val percent = match.groupValues[1].toDoubleOrNull() ?: continue
            val total = match.groupValues[2].toDoubleOrNull() ?: continue
            val result = (percent / 100.0) * total
            if (!result.isNaN() && !result.isInfinite()) {
                return CalculationResult(
                    expression = match.value.trim(),
                    numericValue = result,
                    formattedResult = numberFormat.format(result),
                )
            }
        }

        // 3. Explicit math expression after common prompt prefixes
        val prefixMatch = PROMPT_PREFIX_PATTERN.find(trimmed)
        if (prefixMatch != null) {
            val remainder = prefixMatch.groupValues[1].trim()
            if (remainder.isNotEmpty() &&
                !DATE_PATTERN.containsMatchIn(remainder) &&
                remainder.any { it.isDigit() || it in "+-*/×÷%^" }
            ) {
                val prefixEval = try {
                    evaluateMathExpression(remainder)
                } catch (_: Exception) {
                    null
                }
                if (prefixEval != null) return prefixEval
            }
        }

        // 4. Isolated arithmetic segments inside prose (avoiding bare hyphenated ranges/dates)
        for (match in MATH_SEGMENT_PATTERN.findAll(trimmed)) {
            val candidate = match.value.trim()
            if (!hasStrongIsolatedMath(candidate) || !hasBalancedParentheses(candidate)) continue
            val segmentEval = try {
                evaluateMathExpression(candidate)
            } catch (_: Exception) {
                null
            }
            if (segmentEval != null) return segmentEval
        }

        return null
    }

    private fun hasStrongIsolatedMath(candidate: String): Boolean {
        if (DATE_PATTERN.containsMatchIn(candidate)) return false
        val hasPrimaryOpOrFunc =
            candidate.any { it in "+*×/÷^%" } || FUNCTION_CALL_PATTERN.containsMatchIn(candidate)
        if (hasPrimaryOpOrFunc) return true
        return candidate.contains('(') && candidate.contains(')') && candidate.contains('-')
    }

    private fun hasBalancedParentheses(expr: String): Boolean {
        var depth = 0
        for (ch in expr) {
            when (ch) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0
    }

    private fun evaluatePercentage(input: String): CalculationResult? {
        val match = PERCENT_PATTERN.find(input) ?: return null
        val percent = match.groupValues[1].toDoubleOrNull() ?: return null
        val total = match.groupValues[2].toDoubleOrNull() ?: return null

        val result = (percent / 100.0) * total
        if (result.isNaN() || result.isInfinite()) return null
        return CalculationResult(
            expression = input,
            numericValue = result,
            formattedResult = numberFormat.format(result),
        )
    }

    private fun evaluateUnitConversion(input: String): CalculationResult? {
        val match = CONVERSION_PATTERN.find(input) ?: return null

        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val fromUnit = match.groupValues[2].lowercase(Locale.US).replace("°", "")
        val toUnit = match.groupValues[3].lowercase(Locale.US).replace("°", "")

        val converted = convertUnits(value, fromUnit, toUnit) ?: return null
        if (converted.first.isNaN() || converted.first.isInfinite()) return null
        return CalculationResult(
            expression = input,
            numericValue = converted.first,
            formattedResult = numberFormat.format(converted.first),
            unitLabel = converted.second,
        )
    }

    private fun convertUnits(value: Double, from: String, to: String): Pair<Double, String>? {
        val kmUnits = listOf("km", "kilometer", "kilometers", "kilometre", "kilometres")
        val mileUnits = listOf("mi", "mile", "miles")
        val meterUnits = listOf("m", "meter", "meters", "metre", "metres")
        val footUnits = listOf("ft", "foot", "feet")
        val cmUnits = listOf("cm", "centimeter", "centimeters", "centimetre", "centimetres")
        val inchUnits = listOf("in", "inch", "inches")
        val kgUnits = listOf("kg", "kilogram", "kilograms", "kilogramme", "kilogrammes")
        val lbUnits = listOf("lbs", "lb", "pound", "pounds")
        val gramUnits = listOf("g", "gram", "grams", "gramme", "grammes")
        val ozUnits = listOf("oz", "ounce", "ounces")
        val cUnits = listOf("c", "celsius")
        val fUnits = listOf("f", "fahrenheit")

        return when {
            // Distance / Length
            (from in kmUnits && to in mileUnits) -> Pair(value * 0.621371, "miles")
            (from in mileUnits && to in kmUnits) -> Pair(value * 1.60934, "km")
            (from in meterUnits && to in footUnits) -> Pair(value * 3.28084, "ft")
            (from in footUnits && to in meterUnits) -> Pair(value * 0.3048, "m")
            (from in cmUnits && to in inchUnits) -> Pair(value * 0.393701, "inches")
            (from in inchUnits && to in cmUnits) -> Pair(value * 2.54, "cm")

            // Mass / Weight
            (from in kgUnits && to in lbUnits) -> Pair(value * 2.20462, "lbs")
            (from in lbUnits && to in kgUnits) -> Pair(value * 0.453592, "kg")
            (from in gramUnits && to in ozUnits) -> Pair(value * 0.035274, "oz")
            (from in ozUnits && to in gramUnits) -> Pair(value * 28.3495, "g")

            // Temperature
            (from in cUnits && to in fUnits) -> Pair((value * 9.0 / 5.0) + 32.0, "°F")
            (from in fUnits && to in cUnits) -> Pair((value - 32.0) * 5.0 / 9.0, "°C")

            else -> null
        }
    }

    /**
     * Evaluates an arithmetic expression using Shunting-Yard and RPN evaluation.
     */
    fun evaluateMathExpression(expression: String): CalculationResult {
        // Substitute constants only as whole words. A blind replace("e", ...) rewrote the letter
        // "e" inside ordinary words ("hello world" -> "h2.718...llo"), which made the tool
        // "evaluate" prose and inject a bogus verification line into the prompt.
        val sanitized = wholeWordConstants(
            expression
                .replace("×", "*")
                .replace("÷", "/"),
        )

        val tokens = tokenize(sanitized)
        require(tokens.isNotEmpty()) { "No valid math tokens found." }
        require(tokens.any { it in listOf("+", "-", "*", "/", "%", "^") || isFunction(it) }) {
            "Expression must contain an operator or math function."
        }

        // If the text still contains a non-function word, it is prose, not an expression.
        val hasProse = tokens.any { token ->
            token.toDoubleOrNull() == null &&
                !isFunction(token) &&
                token !in listOf("+", "-", "*", "/", "%", "^", "(", ")", ",")
        }
        require(!hasProse) { "Not a math expression." }

        val rpn = shuntingYard(tokens)
        val result = evaluateRpn(rpn)

        require(!result.isNaN() && !result.isInfinite()) { "Result is undefined or division by zero." }

        return CalculationResult(
            expression = expression,
            numericValue = result,
            formattedResult = numberFormat.format(result),
        )
    }

    /** Replaces pi/π/e with their values only when they stand alone as words. */
    private fun wholeWordConstants(expr: String): String {
        var out = expr
        out = Regex("(?<![A-Za-z])pi(?![A-Za-z])", RegexOption.IGNORE_CASE).replace(out, Math.PI.toString())
        out = out.replace("π", Math.PI.toString())
        out = Regex("(?<![A-Za-z])e(?![A-Za-z])").replace(out, Math.E.toString())
        return out
    }

    private fun tokenize(expr: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        val len = expr.length

        while (i < len) {
            val c = expr[i]
            when {
                c.isWhitespace() -> i++
                c in "+-*/%^()," -> {
                    // Check for unary minus: preceded by start of expr or an operator or '('
                    if (c == '-' && (tokens.isEmpty() || tokens.last() in listOf("+", "-", "*", "/", "%", "^", "("))) {
                        // Unary minus: inspect next chars for number
                        var j = i + 1
                        while (j < len && (expr[j].isDigit() || expr[j] == '.')) j++
                        if (j > i + 1) {
                            tokens.add(expr.substring(i, j))
                            i = j
                            continue
                        }
                    }
                    tokens.add(c.toString())
                    i++
                }
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < len && (expr[i].isDigit() || expr[i] == '.')) i++
                    tokens.add(expr.substring(start, i))
                }
                c.isLetter() -> {
                    val start = i
                    while (i < len && expr[i].isLetter()) i++
                    val word = expr.substring(start, i).lowercase(Locale.US)
                    when (word) {
                        "e" -> tokens.add(Math.E.toString())
                        "pi" -> tokens.add(Math.PI.toString())
                        in listOf("sqrt", "abs", "round", "sin", "cos", "tan", "log", "ln") -> tokens.add(word)
                        else -> throw IllegalArgumentException("Unknown identifier in math expression: $word")
                    }
                }
                else -> throw IllegalArgumentException("Unexpected character in math expression: $c")
            }
        }
        return tokens
    }

    private fun precedence(op: String): Int = when (op) {
        "+", "-" -> 1
        "*", "/", "%" -> 2
        "^" -> 3
        else -> 0
    }

    private fun isFunction(token: String): Boolean = token in listOf(
        "sqrt", "abs", "round", "sin", "cos", "tan", "log", "ln"
    )

    private fun shuntingYard(tokens: List<String>): List<String> {
        val output = mutableListOf<String>()
        val stack = Stack<String>()

        for (token in tokens) {
            when {
                token.toDoubleOrNull() != null -> output.add(token)
                isFunction(token) -> stack.push(token)
                token in listOf("+", "-", "*", "/", "%", "^") -> {
                    while (!stack.isEmpty() && stack.peek() != "(" &&
                        (precedence(stack.peek()) > precedence(token) ||
                                (precedence(stack.peek()) == precedence(token) && token != "^"))
                    ) {
                        output.add(stack.pop())
                    }
                    stack.push(token)
                }
                token == "(" -> stack.push(token)
                token == ")" -> {
                    while (!stack.isEmpty() && stack.peek() != "(") {
                        output.add(stack.pop())
                    }
                    if (!stack.isEmpty() && stack.peek() == "(") {
                        stack.pop()
                    }
                    if (!stack.isEmpty() && isFunction(stack.peek())) {
                        output.add(stack.pop())
                    }
                }
            }
        }

        while (!stack.isEmpty()) {
            val top = stack.pop()
            if (top != "(" && top != ")") {
                output.add(top)
            }
        }

        return output
    }

    private fun evaluateRpn(rpn: List<String>): Double {
        val stack = Stack<Double>()

        for (token in rpn) {
            val num = token.toDoubleOrNull()
            if (num != null) {
                stack.push(num)
            } else if (isFunction(token)) {
                require(stack.isNotEmpty()) { "Missing operand for function $token" }
                val arg = stack.pop()
                val res = when (token) {
                    "sqrt" -> {
                        require(arg >= 0) { "Square root of negative number" }
                        sqrt(arg)
                    }
                    "abs" -> abs(arg)
                    "round" -> round(arg)
                    "sin" -> sin(arg)
                    "cos" -> cos(arg)
                    "tan" -> tan(arg)
                    "log" -> {
                        require(arg > 0) { "Logarithm of non-positive number" }
                        log10(arg)
                    }
                    "ln" -> {
                        require(arg > 0) { "Logarithm of non-positive number" }
                        ln(arg)
                    }
                    else -> throw IllegalArgumentException("Unknown function $token")
                }
                require(!res.isNaN() && !res.isInfinite()) { "Result is undefined or out of range." }
                stack.push(res)
            } else if (token in listOf("+", "-", "*", "/", "%", "^")) {
                require(stack.size >= 2) { "Insufficient operands for operator $token" }
                val b = stack.pop()
                val a = stack.pop()
                val res = when (token) {
                    "+" -> a + b
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> {
                        require(b != 0.0) { "Division by zero" }
                        a / b
                    }
                    "%" -> {
                        require(b != 0.0) { "Modulo by zero" }
                        a % b
                    }
                    "^" -> {
                        val powered = a.pow(b)
                        require(!powered.isNaN() && !powered.isInfinite()) {
                            "Exponentiation overflow or undefined result."
                        }
                        powered
                    }
                    else -> throw IllegalArgumentException("Unknown operator $token")
                }
                require(!res.isNaN() && !res.isInfinite()) { "Result is undefined or out of range." }
                stack.push(res)
            }
        }

        require(stack.size == 1) { "Invalid expression syntax." }
        return stack.pop()
    }
}
