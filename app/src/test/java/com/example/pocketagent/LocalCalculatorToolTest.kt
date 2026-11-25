package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCalculatorToolTest {

    @Test
    fun `evaluates basic arithmetic expressions`() {
        val r1 = LocalCalculatorTool.tryEvaluate("2 + 3 * 4")
        assertNotNull(r1)
        assertEquals(14.0, r1!!.numericValue, 0.0001)

        val r2 = LocalCalculatorTool.tryEvaluate("(2 + 3) * 4")
        assertNotNull(r2)
        assertEquals(20.0, r2!!.numericValue, 0.0001)

        val r3 = LocalCalculatorTool.tryEvaluate("100 / 4 - 5")
        assertNotNull(r3)
        assertEquals(20.0, r3!!.numericValue, 0.0001)
    }

    @Test
    fun `evaluates powers and negative numbers`() {
        val r1 = LocalCalculatorTool.tryEvaluate("2^8")
        assertNotNull(r1)
        assertEquals(256.0, r1!!.numericValue, 0.0001)

        val r2 = LocalCalculatorTool.tryEvaluate("-10 + 25")
        assertNotNull(r2)
        assertEquals(15.0, r2!!.numericValue, 0.0001)
    }

    @Test
    fun `evaluates standard math functions`() {
        val r1 = LocalCalculatorTool.tryEvaluate("sqrt(144) + 8")
        assertNotNull(r1)
        assertEquals(20.0, r1!!.numericValue, 0.0001)

        val r2 = LocalCalculatorTool.tryEvaluate("abs(-42) + round(3.7)")
        assertNotNull(r2)
        assertEquals(46.0, r2!!.numericValue, 0.0001)
    }

    @Test
    fun `evaluates percentages`() {
        val r1 = LocalCalculatorTool.tryEvaluate("20% of 150")
        assertNotNull(r1)
        assertEquals(30.0, r1!!.numericValue, 0.0001)

        val r2 = LocalCalculatorTool.tryEvaluate("12.5 percent of 80")
        assertNotNull(r2)
        assertEquals(10.0, r2!!.numericValue, 0.0001)
    }

    @Test
    fun `evaluates unit conversions`() {
        val r1 = LocalCalculatorTool.tryEvaluate("10 km to miles")
        assertNotNull(r1)
        assertEquals(6.21371, r1!!.numericValue, 0.01)
        assertEquals("miles", r1.unitLabel)

        val r2 = LocalCalculatorTool.tryEvaluate("0 C to F")
        assertNotNull(r2)
        assertEquals(32.0, r2!!.numericValue, 0.01)
        assertEquals("°F", r2.unitLabel)

        val r3 = LocalCalculatorTool.tryEvaluate("100 F to C")
        assertNotNull(r3)
        assertEquals(37.7778, r3!!.numericValue, 0.01)
        assertEquals("°C", r3.unitLabel)

        val r4 = LocalCalculatorTool.tryEvaluate("5 kg to lbs")
        assertNotNull(r4)
        assertEquals(11.0231, r4!!.numericValue, 0.01)
        assertEquals("lbs", r4.unitLabel)
    }

    @Test
    fun `returns null for non-math text and invalid expressions`() {
        assertNull(LocalCalculatorTool.tryEvaluate("hello world"))
        assertNull(LocalCalculatorTool.tryEvaluate("how are you today?"))
        assertNull(LocalCalculatorTool.tryEvaluate("10 / 0")) // Division by zero handled
        assertNull(LocalCalculatorTool.tryEvaluate("sqrt(-4)")) // Square root of negative handled
    }

    @Test
    fun `extracts embedded percentages from natural questions`() {
        val r1 = LocalCalculatorTool.tryEvaluate("What is 15% of 2400?")
        assertNotNull(r1)
        assertEquals(360.0, r1!!.numericValue, 0.0001)
        assertEquals("360", r1.formattedResult)

        val r2 = LocalCalculatorTool.tryEvaluate("calculate 12.5 percent of 80")
        assertNotNull(r2)
        assertEquals(10.0, r2!!.numericValue, 0.0001)
    }

    @Test
    fun `extracts embedded unit conversions from natural questions`() {
        val r1 = LocalCalculatorTool.tryEvaluate("convert 10 km to miles")
        assertNotNull(r1)
        assertEquals(6.21371, r1!!.numericValue, 0.01)
        assertEquals("miles", r1.unitLabel)

        val r2 = LocalCalculatorTool.tryEvaluate("what is 100 F to C?")
        assertNotNull(r2)
        assertEquals(37.7778, r2!!.numericValue, 0.01)
        assertEquals("°C", r2.unitLabel)

        val r3 = LocalCalculatorTool.tryEvaluate("how many miles is 25 km to miles?")
        assertNotNull(r3)
        assertEquals(15.5343, r3!!.numericValue, 0.01)
        assertEquals("miles", r3.unitLabel)

        val r4 = LocalCalculatorTool.tryEvaluate("Convert 5 miles to kilometres")
        assertNotNull(r4)
        assertEquals(8.0467, r4!!.numericValue, 0.01)
        assertEquals("km", r4.unitLabel)

        val r5 = LocalCalculatorTool.tryEvaluate("how many miles is 25 km?")
        assertNotNull(r5)
        assertEquals(15.5343, r5!!.numericValue, 0.01)
        assertEquals("miles", r5.unitLabel)
    }

    @Test
    fun `extracts explicit arithmetic expressions after prompt prefixes and in prose segments`() {
        val r1 = LocalCalculatorTool.tryEvaluate("What is 2 + 3 * 4?")
        assertNotNull(r1)
        assertEquals(14.0, r1!!.numericValue, 0.0001)

        val r2 = LocalCalculatorTool.tryEvaluate("what's (12 + 8) / 5?")
        assertNotNull(r2)
        assertEquals(4.0, r2!!.numericValue, 0.0001)

        val r3 = LocalCalculatorTool.tryEvaluate("compute 2^10")
        assertNotNull(r3)
        assertEquals(1024.0, r3!!.numericValue, 0.0001)

        val r4 = LocalCalculatorTool.tryEvaluate("evaluate 100 - 37.5")
        assertNotNull(r4)
        assertEquals(62.5, r4!!.numericValue, 0.0001)

        val r5 = LocalCalculatorTool.tryEvaluate("solve 3 * (4 + 5) - 7")
        assertNotNull(r5)
        assertEquals(20.0, r5!!.numericValue, 0.0001)

        val r6 = LocalCalculatorTool.tryEvaluate("find sqrt(144) + 8")
        assertNotNull(r6)
        assertEquals(20.0, r6!!.numericValue, 0.0001)

        val r7 = LocalCalculatorTool.tryEvaluate("If each box holds (12 + 8) * 3 items, what is the capacity?")
        assertNotNull(r7)
        assertEquals(60.0, r7!!.numericValue, 0.0001)

        val r8 = LocalCalculatorTool.tryEvaluate("The result of sin(0) + cos(0) is needed")
        assertNotNull(r8)
        assertEquals(1.0, r8!!.numericValue, 0.0001)

        val r9 = LocalCalculatorTool.tryEvaluate("Use log(100) + ln(e) for scaling")
        assertNotNull(r9)
        assertEquals(3.0, r9!!.numericValue, 0.0001)
    }

    @Test
    fun `avoids dates plain english sentences and handles exponent overflow safely`() {
        assertNull(LocalCalculatorTool.tryEvaluate("2025-11-25"))
        assertNull(LocalCalculatorTool.tryEvaluate("Today is 2025-11-25"))
        assertNull(LocalCalculatorTool.tryEvaluate("What is 2025-11-25?"))
        assertNull(LocalCalculatorTool.tryEvaluate("Meeting on 11/25/2025"))
        assertNull(LocalCalculatorTool.tryEvaluate("Read pages 10-20 in the manual"))
        assertNull(LocalCalculatorTool.tryEvaluate("It takes 2-3 hours"))
        assertNull(LocalCalculatorTool.tryEvaluate("What is the capital of Australia?"))
        assertNull(LocalCalculatorTool.tryEvaluate("Find 3 ways to save battery"))
        assertNull(LocalCalculatorTool.tryEvaluate("What is Android 15?"))
        assertNull(LocalCalculatorTool.tryEvaluate("2^10000"))
        assertNull(LocalCalculatorTool.tryEvaluate("What is 2^10000?"))
        assertNull(LocalCalculatorTool.tryEvaluate("(-2)^0.5"))
        assertNull(LocalCalculatorTool.tryEvaluate("10 % 0"))
    }
}
