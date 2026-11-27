package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneEquipmentToolsTest {

    @Test
    fun `computeSolarTimes returns expected sunrise and sunset for Kolkata equinox`() {
        val result = computeSolarTimes(
            latDeg = 22.5726,
            lonDeg = 88.3639,
            dayOfYear = 80,
            utcOffsetHours = 5.5,
        )
        assertTrue("Expected Sunrise in result: $result", result.contains("Sunrise: 05:"))
        assertTrue("Expected Sunset in result: $result", result.contains("Sunset: 17:"))
    }

    @Test
    fun `computeSolarTimes returns roughly 12h day at equator on equinox`() {
        val result = computeSolarTimes(
            latDeg = 0.0,
            lonDeg = 0.0,
            dayOfYear = 80,
            utcOffsetHours = 0.0,
        )
        assertTrue("Expected Sunrise around 06:xx: $result", result.contains("Sunrise: 06:"))
        assertTrue("Expected Sunset around 18:xx: $result", result.contains("Sunset: 18:"))
    }

    @Test
    fun `computeSolarTimes handles polar midnight sun and polar night`() {
        val summer = computeSolarTimes(
            latDeg = 78.0,
            lonDeg = 15.0,
            dayOfYear = 172,
            utcOffsetHours = 1.0,
        )
        assertTrue("Expected Midnight sun: $summer", summer.contains("Midnight sun"))

        val winter = computeSolarTimes(
            latDeg = 78.0,
            lonDeg = 15.0,
            dayOfYear = 355,
            utcOffsetHours = 1.0,
        )
        assertTrue("Expected Polar night: $winter", winter.contains("Polar night"))

        val exactNorthPole = computeSolarTimes(
            latDeg = 90.0,
            lonDeg = 0.0,
            dayOfYear = 172,
            utcOffsetHours = 0.0,
        )
        assertTrue("Expected Midnight sun at North Pole: $exactNorthPole", exactNorthPole.contains("Midnight sun"))
    }

    @Test
    fun `AgentToolLoop delegates toggle_flashlight read_sensors and get_location`() {
        var capturedFlashMode = ""
        var capturedSensorMode = ""
        val loop = AgentToolLoop(
            flashlightController = { mode ->
                capturedFlashMode = mode
                "Flashlight:$mode"
            },
            sensorReader = { mode ->
                capturedSensorMode = mode
                "Sensors:$mode"
            },
            locationReader = { "Lat:22.57,Lon:88.36" },
        )

        assertEquals("Flashlight:sos", loop.executeToolCall("toggle_flashlight", "sos"))
        assertEquals("sos", capturedFlashMode)

        assertEquals("Flashlight:toggle", loop.executeToolCall("toggle_flashlight", ""))
        assertEquals("toggle", capturedFlashMode)

        assertEquals("Sensors:light", loop.executeToolCall("read_sensors", "light"))
        assertEquals("light", capturedSensorMode)

        assertEquals("Sensors:all", loop.executeToolCall("read_sensors", ""))
        assertEquals("all", capturedSensorMode)

        assertEquals("Lat:22.57,Lon:88.36", loop.executeToolCall("get_location", ""))
    }

    @Test
    fun `AgentToolLoop returns unavailable messages when hardware callbacks are null`() {
        val loop = AgentToolLoop()
        assertEquals("Flashlight unavailable.", loop.executeToolCall("toggle_flashlight", "on"))
        assertEquals("Sensors unavailable.", loop.executeToolCall("read_sensors", "all"))
        assertEquals("Location unavailable.", loop.executeToolCall("get_location", ""))
    }

    @Test
    fun `AgentToolLoop processOutput extracts hardware tool calls and instruction lists them`() {
        assertTrue(AgentToolLoop.AGENT_MODE_INSTRUCTION.contains("toggle_flashlight"))
        assertTrue(AgentToolLoop.AGENT_MODE_INSTRUCTION.contains("read_sensors"))
        assertTrue(AgentToolLoop.AGENT_MODE_INSTRUCTION.contains("get_location"))

        val loop = AgentToolLoop(
            flashlightController = { "ON" },
            sensorReader = { "120 lx" },
            locationReader = { "22.57, 88.36" },
        )
        val (hasTools, result) = loop.processOutput(
            "TOOL_CALL: toggle_flashlight(on)\nTOOL_CALL: read_sensors(light)\nTOOL_CALL: get_location()"
        )
        assertTrue(hasTools)
        assertEquals(3, result.toolResults.size)
        assertEquals("ON", result.toolResults[0].output)
        assertEquals("120 lx", result.toolResults[1].output)
        assertEquals("22.57, 88.36", result.toolResults[2].output)
    }
}
