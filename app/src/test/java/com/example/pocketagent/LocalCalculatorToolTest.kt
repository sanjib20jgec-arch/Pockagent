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
}
