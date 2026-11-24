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

    /**
     * Tries to evaluate a string as either a math expression or a unit conversion.
     * Returns null if the text cannot be safely parsed.
     */
    fun tryEvaluate(input: String): CalculationResult? {
        val trimmed = input.trim()
        if (trimmed.length < 2 || trimmed.length > 300) return null

        // 1. Check unit conversions
        val unitResult = evaluateUnitConversion(trimmed)
        if (unitResult != null) return unitResult

        // 2. Check percentage pattern "X% of Y" or "X percent of Y"
        val pctResult = evaluatePercentage(trimmed)
        if (pctResult != null) return pctResult

        // 3. Try standard mathematical evaluation
        val hasMathChars = trimmed.any { it.isDigit() || it in "+-*/%^" }
        if (!hasMathChars) return null

        return try {
            evaluateMathExpression(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    private fun evaluatePercentage(input: String): CalculationResult? {
        val pctRegex = Regex("""(?i)^\s*(\d+(?:\.\d+)?)\s*(?:%|percent)\s+(?:of)\s+(\d+(?:\.\d+)?)\s*$""")
        val match = pctRegex.find(input) ?: return null
        val percent = match.groupValues[1].toDoubleOrNull() ?: return null
        val total = match.groupValues[2].toDoubleOrNull() ?: return null

        val result = (percent / 100.0) * total
        return CalculationResult(
            expression = input,
            numericValue = result,
            formattedResult = numberFormat.format(result),
        )
    }

    private fun evaluateUnitConversion(input: String): CalculationResult? {
        val convRegex = Regex("""(?i)^\s*([+-]?\d+(?:\.\d+)?)\s*([a-zA-Z°]+)\s+(?:to|in|into)\s+([a-zA-Z°]+)\s*$""")
        val match = convRegex.find(input) ?: return null

        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val fromUnit = match.groupValues[2].lowercase(Locale.US).replace("°", "")
        val toUnit = match.groupValues[3].lowercase(Locale.US).replace("°", "")

        val converted = convertUnits(value, fromUnit, toUnit) ?: return null
        return CalculationResult(
            expression = input,
            numericValue = converted.first,
            formattedResult = numberFormat.format(converted.first),
            unitLabel = converted.second,
        )
    }

    private fun convertUnits(value: Double, from: String, to: String): Pair<Double, String>? {
        return when {
            // Distance / Length
            (from in listOf("km", "kilometer", "kilometers") && to in listOf("mi", "mile", "miles")) ->
                Pair(value * 0.621371, "miles")
            (from in listOf("mi", "mile", "miles") && to in listOf("km", "kilometer", "kilometers")) ->
                Pair(value * 1.60934, "km")
            (from in listOf("m", "meter", "meters") && to in listOf("ft", "foot", "feet")) ->
                Pair(value * 3.28084, "ft")
            (from in listOf("ft", "foot", "feet") && to in listOf("m", "meter", "meters")) ->
                Pair(value * 0.3048, "m")
            (from in listOf("cm", "centimeter", "centimeters") && to in listOf("in", "inch", "inches")) ->
                Pair(value * 0.393701, "inches")
            (from in listOf("in", "inch", "inches") && to in listOf("cm", "centimeter", "centimeters")) ->
                Pair(value * 2.54, "cm")

            // Mass / Weight
            (from in listOf("kg", "kilogram", "kilograms") && to in listOf("lbs", "lb", "pound", "pounds")) ->
                Pair(value * 2.20462, "lbs")
            (from in listOf("lbs", "lb", "pound", "pounds") && to in listOf("kg", "kilogram", "kilograms")) ->
                Pair(value * 0.453592, "kg")
            (from in listOf("g", "gram", "grams") && to in listOf("oz", "ounce", "ounces")) ->
                Pair(value * 0.035274, "oz")
            (from in listOf("oz", "ounce", "ounces") && to in listOf("g", "gram", "grams")) ->
                Pair(value * 28.3495, "g")

            // Temperature
            (from in listOf("c", "celsius") && to in listOf("f", "fahrenheit")) ->
                Pair((value * 9.0 / 5.0) + 32.0, "°F")
            (from in listOf("f", "fahrenheit") && to in listOf("c", "celsius")) ->
                Pair((value - 32.0) * 5.0 / 9.0, "°C")

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
                    "%" -> a % b
                    "^" -> a.pow(b)
                    else -> throw IllegalArgumentException("Unknown operator $token")
                }
                stack.push(res)
            }
        }

        require(stack.size == 1) { "Invalid expression syntax." }
        return stack.pop()
    }
}
