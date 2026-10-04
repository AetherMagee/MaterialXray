package com.material.xray.core.runtime

import com.material.xray.core.common.log.LogBuffer
import com.material.xray.core.connection.RootTunDevice
import com.material.xray.core.connection.XrayProcessBinary
import com.material.xray.core.root.RootShell
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayProcessSupervisorTest {

    @Test
    fun `start quotes paths and returns launched pid`() = runTest {
        val commands = FakeRootCommandRunner(
            resultForCommand = { command ->
                assertTrue(command.contains("cd '/tmp/xray bin'"))
                assertTrue(command.contains("export XRAY_LOCATION_ASSET='/proc/self/cwd'"))
                assertTrue(command.contains("SSL_CERT_FILE='/proc/self/cwd/xray-ca-certificates.pem'"))
                assertFalse(command.contains("xray.location.asset"))
                assertFalse(command.contains(" env "))
                assertTrue(command.contains("> '/tmp/runtime dir/xray.log' 2>&1 & fi"))
                assertFalse(command.contains("su -g"))
                assertTrue(command.contains("pidof libxray.so"))
                assertTrue(command.contains("cat -v \"/proc/\$1/cmdline\""))
                assertFalse(command.contains("tr "))
                assertTrue(command.contains("printf '%s' \"\$found\""))
                RootShell.Result(exitCode = 0, output = "1234", error = "")
            },
        )
        val supervisor = supervisor(commandRunner = commands)

        val pid = supervisor.start("/tmp/xray bin")

        assertEquals(1234, pid)
        assertEquals(0, ProcessBuilder("sh", "-n", "-c", commands.commands.single()).start().waitFor())
    }

    @Test
    fun `start sandboxes the core as its own uid with the app gid and the config as stdin`() = runTest {
        val commands = FakeRootCommandRunner(
            resultForCommand = { RootShell.Result(exitCode = 0, output = "1234", error = "") },
        )

        supervisor(commandRunner = commands).start("/tmp/xray bin")

        val command = commands.commands.single()
        assertTrue(
            command.contains(
                "exec '/tmp/native lib==/libxrayroot.so' $ROOT_CORE_UID 12345 '/tmp/config dir/config.json' - - " +
                    "'/tmp/native lib==/libxray.so' run -c stdin:",
            ),
        )
        assertTrue(command.contains("$ROOT_CORE_UID:12345:12345|0:12345:12345) return 0"))
    }

    @Test
    fun `start opens the working directory to the core read-only apart from its sockets`() = runTest {
        val commands = FakeRootCommandRunner(
            resultForCommand = { RootShell.Result(exitCode = 0, output = "1234", error = "") },
        )

        supervisor(commandRunner = commands).start("/tmp/xray bin")

        val command = commands.commands.single()
        assertTrue(
            command.startsWith(
                "if chmod -R g+rX '/tmp/xray bin' && chmod g-w '/tmp/xray bin' && chmod g+w '/tmp/xray bin/sockets'; then ",
            ),
        )
        // The bundled core sits in the installer's library directory, which needs no grant.
        assertFalse(command.contains("libxray.so' && "))
        assertFalse(command.contains("chmod g+x"))
    }

    @Test
    fun `start lets the core exec a core installed in the app's storage`() = runTest {
        val commands = FakeRootCommandRunner(
            resultForCommand = { RootShell.Result(exitCode = 0, output = "1234", error = "") },
        )
        val supervisor = supervisor(
            commandRunner = commands,
            xrayBinary = FakeXrayProcessBinary(binaryPath = "/tmp/runtime dir/cores/v1/libxray.so"),
        )

        supervisor.start("/tmp/xray bin")

        assertTrue(
            commands.commands.single().contains(
                "chmod g+x '/tmp/runtime dir/cores/v1' '/tmp/runtime dir/cores/v1/libxray.so'; then ",
            ),
        )
    }

    @Test
    fun `TUN start has the root launcher create the interface`() = runTest {
        val commands = FakeRootCommandRunner(
            resultForCommand = { command ->
                assertTrue(
                    command.contains(
                        "'/tmp/native lib==/libxrayroot.so' $ROOT_CORE_UID 12345 '/tmp/config dir/config.json' 'wlan1' 1400 " +
                            "'/tmp/native lib==/libxray.so' run -c stdin:",
                    ),
                )
                RootShell.Result(exitCode = 0, output = "1234", error = "")
            },
        )

        val pid = supervisor(commandRunner = commands).start("/tmp/xray bin", tun = RootTunDevice("wlan1", 1400))

        assertEquals(1234, pid)
    }

    @Test
    fun `process liveness and kill reject invalid pids without shelling out`() = runTest {
        val commands = FakeRootCommandRunner()
        val supervisor = supervisor(commandRunner = commands)

        assertFalse(supervisor.isAlive(0))
        assertFalse(supervisor.kill(-1))
        assertTrue(commands.commands.isEmpty())
    }

    @Test
    fun `root liveness verifies process state and active config identity`() = runTest {
        val commands = FakeRootCommandRunner()

        assertTrue(supervisor(commandRunner = commands).isAlive(42))

        val command = commands.commands.single()
        assertTrue(command.contains("kill -0 42"))
        assertTrue(command.contains("/^State:/"))
        assertTrue(command.contains("!= Z"))
        assertTrue(command.endsWith("is_owned 42"))
        assertTrue(command.contains("*'/tmp/config dir/config.json'*"))
        assertFalse(command.contains("tr "))
        assertEquals(0, ProcessBuilder("sh", "-n", "-c", command).start().waitFor())
    }

    @Test
    fun `readResidentMemoryMb rounds statm resident pages up to megabytes`() = runTest {
        val supervisor = supervisor(
            commandRunner = FakeRootCommandRunner(
                resultForCommand = { command ->
                    assertTrue(command.contains("awk '{ print \$2; exit }' /proc/42/statm"))
                    RootShell.Result(exitCode = 0, output = "513", error = "")
                },
            ),
        )

        assertEquals(3L, supervisor.readResidentMemoryMb(42))
    }

    @Test
    fun `readResidentMemoryMb returns null for unavailable statm`() = runTest {
        val supervisor = supervisor(
            commandRunner = FakeRootCommandRunner(
                resultForCommand = {
                    RootShell.Result(exitCode = 1, output = "", error = "missing")
                },
            ),
        )

        assertNull(supervisor.readResidentMemoryMb(42))
    }

    @Test
    fun `readResidentMemoryMb supports 16 KiB memory pages`() = runTest {
        val supervisor = supervisor(
            environment = FakeRuntimeEnvironment(memoryPageSizeKb = 16L),
            commandRunner = FakeRootCommandRunner(
                resultForCommand = {
                    RootShell.Result(exitCode = 0, output = "129", error = "")
                },
            ),
        )

        assertEquals(3L, supervisor.readResidentMemoryMb(42))
    }

    @Test
    fun `readResidentMemoryMb does not guess an unavailable page size`() = runTest {
        val supervisor = supervisor(
            environment = FakeRuntimeEnvironment(memoryPageSizeKb = null),
            commandRunner = FakeRootCommandRunner(
                resultForCommand = {
                    RootShell.Result(exitCode = 0, output = "513", error = "")
                },
            ),
        )

        assertNull(supervisor.readResidentMemoryMb(42))
    }

    @Test
    fun `statm parser reads resident pages without splitting the full line`() {
        assertEquals(15_950L, parseStatmResidentPages("3102804 15950 7671 3740 0 31099 0"))
        assertEquals(15_950L, parseStatmResidentPages("3102804\t15950\t7671"))
        val bytes = "3102804 15950 7671\n".toByteArray()
        assertEquals(15_950L, parseStatmResidentPages(bytes, bytes.size))
        assertNull(parseStatmResidentPages("3102804"))
        assertNull(parseStatmResidentPages(bytes, 0))
        assertNull(parseStatmResidentPages(null))
    }

    @Test
    fun `ensureNativeRuntimeExemptions requests battery and network allowlists`() = runTest {
        val environment = FakeRuntimeEnvironment(ignoringBatteryOptimizations = false)
        val commands = FakeRootCommandRunner()
        val log = LogBuffer()

        supervisor(environment = environment, commandRunner = commands, log = log)
            .ensureNativeRuntimeExemptions()

        assertEquals(
            listOf(
                "cmd deviceidle whitelist +'com.material.xray'",
                "cmd netpolicy add restrict-background-whitelist 12345",
            ),
            commands.commands,
        )
        assertTrue(log.entries.value.any { it.message.contains("Requested device idle whitelist") })
        assertTrue(log.entries.value.any { it.message.contains("background-data allowlist") })
    }

    @Test
    fun `user process stop waits for graceful exit`() = runTest {
        val launcher = FakeUserXrayProcessLauncher(
            alive = { pid, killedSignals -> pid == 42 && 15 !in killedSignals },
        )
        val supervisor = userSupervisor(processLauncher = launcher)
        supervisor.start(binDir = "/tmp/xray bin", tunFd = 89)

        supervisor.stop()

        assertEquals(listOf(15), launcher.killedSignals)
    }

    @Test
    fun `user process passes the CA bundle to xray`() = runTest {
        val directory = Files.createTempDirectory("user-xray-process-test").toFile()
        val launcher = FakeUserXrayProcessLauncher()
        val supervisor = userSupervisor(
            environment = FakeRuntimeEnvironment(filesDir = directory),
            processLauncher = launcher,
        )

        try {
            supervisor.start(binDir = "/tmp/xray bin", tunFd = 89)

            assertEquals(
                File("/tmp/xray bin", XRAY_CERTIFICATE_BUNDLE_FILE).absolutePath,
                launcher.startedEnvironment?.get("SSL_CERT_FILE"),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `user process stop escalates to kill when process remains alive`() = runTest {
        val launcher = FakeUserXrayProcessLauncher(
            alive = { pid, killedSignals -> pid == 42 && 9 !in killedSignals },
        )
        val supervisor = userSupervisor(processLauncher = launcher)
        supervisor.start(binDir = "/tmp/xray bin", tunFd = 89)

        supervisor.stop()

        assertEquals(listOf(15, 9), launcher.killedSignals)
    }

    @Test
    fun `user process destruction requests stop without waiting`() = runTest {
        val launcher = FakeUserXrayProcessLauncher()
        val supervisor = userSupervisor(processLauncher = launcher)
        supervisor.start(binDir = "/tmp/xray bin", tunFd = 89)

        supervisor.requestStop()

        assertEquals(listOf(15), launcher.killedSignals)
    }

    @Test
    fun `user process drops the tracked pid once the probe observes death`() = runTest {
        val launcher = FakeUserXrayProcessLauncher(alive = { _, _ -> false })
        val supervisor = userSupervisor(processLauncher = launcher)
        supervisor.start(binDir = "/tmp/xray bin", tunFd = 89)

        assertFalse(supervisor.isAlive(42))
        supervisor.stop()
        supervisor.requestStop()

        assertEquals(emptyList<Int>(), launcher.killedSignals)
    }

    @Test
    fun `user process cleanup can stop an orphan from persisted state`() = runTest {
        val launcher = FakeUserXrayProcessLauncher(
            alive = { pid, killedSignals -> pid == 77 && 9 !in killedSignals },
        )
        val supervisor = userSupervisor(processLauncher = launcher)

        supervisor.stopOrphan(77)

        assertEquals(listOf(15, 9), launcher.killedSignals)
    }

    private fun supervisor(
        environment: XrayRuntimeEnvironment = FakeRuntimeEnvironment(),
        commandRunner: FakeRootCommandRunner = FakeRootCommandRunner(),
        log: LogBuffer = LogBuffer(),
        xrayBinary: XrayProcessBinary = FakeXrayProcessBinary(),
    ) = XrayProcessSupervisor(
        environment = environment,
        commandRunner = commandRunner,
        xrayBinary = xrayBinary,
        log = log,
    )

    private fun userSupervisor(
        environment: XrayRuntimeEnvironment = FakeRuntimeEnvironment(),
        processLauncher: UserXrayProcessLauncher = FakeUserXrayProcessLauncher(),
    ) = UserXrayProcessSupervisor(
        environment = environment,
        xrayBinary = FakeXrayProcessBinary(),
        processLauncher = processLauncher,
    )

    private class FakeRootCommandRunner(
        private val resultForCommand: (String) -> RootShell.Result = {
            RootShell.Result(exitCode = 0, output = "", error = "")
        },
    ) : RootCommandRunner {
        val commands = mutableListOf<String>()

        override suspend fun execute(command: String): RootShell.Result {
            commands += command
            return resultForCommand(command)
        }
    }

    private class FakeXrayProcessBinary(
        // Installer paths contain '=', which the launch command must not mistake for assignments.
        override val binaryPath: String = "/tmp/native lib==/libxray.so",
    ) : XrayProcessBinary {
        override val userCommand: List<String> = listOf(binaryPath)
        override val rootLauncherPath: String = "/tmp/native lib==/libxrayroot.so"

        override fun configPath(): String = "/tmp/config dir/config.json"
    }

    private class FakeRuntimeEnvironment(
        override val filesDir: File = File("/tmp/runtime dir"),
        override val packageName: String = "com.material.xray",
        override val packageUid: Int = 12345,
        override val memoryPageSizeKb: Long? = 4L,
        private val ignoringBatteryOptimizations: Boolean = true,
        private val lowPowerStandbyExempt: Boolean = false,
    ) : XrayRuntimeEnvironment {
        override fun isIgnoringBatteryOptimizations(): Boolean = ignoringBatteryOptimizations

        override fun isExemptFromLowPowerStandby(): Boolean = lowPowerStandbyExempt
    }

    private class FakeUserXrayProcessLauncher(
        private val alive: (pid: Int, killedSignals: List<Int>) -> Boolean = { pid, _ -> pid == 42 },
    ) : UserXrayProcessLauncher {
        val killedSignals = mutableListOf<Int>()
        var startedEnvironment: Map<String, String>? = null

        override fun start(
            command: List<String>,
            configPath: String,
            workingDir: String,
            logPath: String,
            tunFd: Int,
            environment: Map<String, String>,
        ): Int {
            startedEnvironment = environment
            return 42
        }

        override fun isAlive(pid: Int): Boolean = alive(pid, killedSignals)

        override fun kill(pid: Int, signal: Int): Boolean {
            killedSignals += signal
            return true
        }
    }
}
