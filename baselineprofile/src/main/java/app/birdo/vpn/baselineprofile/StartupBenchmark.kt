package app.birdo.vpn.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * MEASURES cold start, so a claim about the profile's effect can be checked
 * instead of asserted.
 *
 * This class is the reason #358 insists the profile be generated rather than
 * written: without a before/after number there is no way to tell a profile that
 * helps from one that hurts, and a profile that hurts looks exactly like one
 * that helps from the outside.
 *
 * THIS WILL REFUSE TO RUN ON THE MANAGED DEVICE, AND THAT IS CORRECT.
 * androidx.benchmark fails with `ERRORS (not suppressed): EMULATOR` on any
 * emulator, because emulator timings are not merely noisy -- an improvement
 * measured there can be a regression on real hardware. Recording a profile on an
 * emulator is fine, since the class list is the same; MEASURING one there is not.
 *
 * So the honest run is on a phone:
 *
 *   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 *     -Pandroid.testInstrumentationRunnerArguments.class=app.birdo.vpn.baselineprofile.StartupBenchmark
 *
 * The suppression flag exists and is deliberately NOT set in this module's build
 * file. Setting it there would make emulator numbers the default output of a task
 * called "benchmark", and #358 exists because startup figures with no device
 * behind them reached a pull-request description. Anyone who wants a rough
 * same-machine comparison can opt in per invocation:
 *
 *   -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
 *
 * and must then label the result an EMULATOR figure, never a device one.
 *
 * Whatever the device, only the comparison BETWEEN the two compilation modes
 * below, on one machine in one run, means anything. Absolute milliseconds do not
 * travel between machines.
 */
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** Cold start with no AOT compilation at all -- the floor. */
    @Test
    fun startupNoCompilation() = measure(CompilationMode.None())

    /**
     * Cold start with ONLY the baseline profile compiled -- what a user gets on
     * first launch after installing from Play.
     */
    @Test
    fun startupBaselineProfile() = measure(
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require, warmupIterations = 0)
    )

    private fun measure(mode: CompilationMode) = rule.measureRepeated(
        packageName = "app.birdo.vpn",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg("app.birdo.vpn").depth(0)), 10_000L)
    }
}
