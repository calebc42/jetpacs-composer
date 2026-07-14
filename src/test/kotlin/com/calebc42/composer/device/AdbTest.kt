// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdbTest {

    @Test
    fun parsesDevicesOutput() {
        val out = """
            List of devices attached
            R58M12ABCDE            device product:beyond1q model:Galaxy_S10 device:beyond1
            emulator-5554          offline
            * daemon started successfully

        """.trimIndent()
        val devices = Adb.parseDevices(out)
        assertEquals(2, devices.size)
        assertEquals(DeviceInfo("R58M12ABCDE", "device", "Galaxy S10"), devices[0])
        assertTrue(devices[0].ready)
        assertEquals(DeviceInfo("emulator-5554", "offline", null), devices[1])
        assertTrue(!devices[1].ready)
    }

    @Test
    fun parsesEmptyList() {
        assertEquals(emptyList(), Adb.parseDevices("List of devices attached\n\n"))
    }

    @Test
    fun installSnippetIsBalancedElisp() {
        val snippet = Deployer.installSnippet()
        assertEquals(snippet.count { it == '(' }, snippet.count { it == ')' })
        assertTrue("jetpacs-app-*.el" in snippet)
    }
}
