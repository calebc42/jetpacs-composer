// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.device

import java.io.File
import java.util.concurrent.TimeUnit

data class DeviceInfo(val serial: String, val state: String, val model: String?) {
    val ready: Boolean get() = state == "device"
}

data class CmdResult(val exit: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exit == 0
}

data class FileNode(val name: String, val isDirectory: Boolean)

/** Thin wrapper over the adb CLI; every call is synchronous and bounded. */
object Adb {

    const val COMPANION_PACKAGE = "com.calebc42.jetpacs"

    /** adb from PATH or the default Android SDK location; null if absent. */
    val executable: String? by lazy {
        val candidates = buildList {
            add("adb")
            System.getenv("LOCALAPPDATA")?.let {
                add("$it\\Android\\Sdk\\platform-tools\\adb.exe")
            }
            System.getenv("ANDROID_HOME")?.let {
                add("$it\\platform-tools\\adb.exe")
            }
        }
        candidates.firstOrNull { runs(it) }
    }

    private fun runs(exe: String): Boolean = runCatching {
        ProcessBuilder(exe, "version").start().waitFor(10, TimeUnit.SECONDS)
    }.getOrDefault(false)

    fun run(vararg args: String, timeoutSeconds: Long = 60): CmdResult {
        val exe = executable ?: return CmdResult(-1, "", "adb not found")
        return exec(listOf(exe) + args, timeoutSeconds)
    }

    fun devices(): List<DeviceInfo> {
        val out = run("devices", "-l", timeoutSeconds = 15)
        if (!out.ok) return emptyList()
        return parseDevices(out.stdout)
    }

    /** Pure — unit-tested against captured `adb devices -l` output. */
    fun parseDevices(stdout: String): List<DeviceInfo> =
        stdout.lineSequence()
            .drop(1)
            .filter { it.isNotBlank() && !it.startsWith("*") }
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                DeviceInfo(
                    serial = parts[0],
                    state = parts[1],
                    model = parts.firstOrNull { it.startsWith("model:") }
                        ?.removePrefix("model:")?.replace('_', ' '),
                )
            }
            .toList()

    fun ls(serial: String, path: String): List<FileNode> {
        val out = run("-s", serial, "shell", "ls", "-A1p", path, timeoutSeconds = 15)
        if (!out.ok) return emptyList()
        return out.stdout.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val isDir = line.endsWith("/")
                FileNode(
                    name = if (isDir) line.dropLast(1) else line,
                    isDirectory = isDir
                )
            }.toList()
    }

    fun companionInstalled(serial: String): Boolean =
        run("-s", serial, "shell", "pm", "path", COMPANION_PACKAGE,
            timeoutSeconds = 15).stdout.contains("package:")

    fun push(serial: String, local: File, remotePath: String): CmdResult =
        run("-s", serial, "push", local.absolutePath, remotePath,
            timeoutSeconds = 120)

    fun forward(serial: String, local: Int, remote: Int): CmdResult =
        run("-s", serial, "forward", "tcp:$local", "tcp:$remote",
            timeoutSeconds = 15)

    internal fun exec(command: List<String>, timeoutSeconds: Long): CmdResult =
        runCatching {
            val proc = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return CmdResult(-1, out, "timed out after ${timeoutSeconds}s")
            }
            CmdResult(proc.exitValue(), out, err)
        }.getOrElse { CmdResult(-1, "", it.message ?: "failed to start") }
}
