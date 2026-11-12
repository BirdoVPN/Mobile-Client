package app.birdo.vpn.ui.viewmodel

import app.birdo.vpn.data.repository.RotatePrepareResponse
import app.birdo.vpn.data.repository.RotationFailure
import app.birdo.vpn.data.repository.RotationResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MR-1707: where each answer takes the user. */
class ReplaceNumberFlowTest {

    private val current = "111111111111111111111111"
    private val fresh = "222222222222222222222222"

    private class Fake : RotationGateway {
        var prepareAnswer: RotationResult<RotatePrepareResponse> =
            RotationResult.Ok(RotatePrepareResponse(ok = true, newNumber = "222222222222222222222222", expiresInSeconds = 600))
        var confirmAnswer: RotationResult<Unit> = RotationResult.Ok(Unit)
        var prepareCalls = 0
        var confirmCalls = 0
        var lastPrepareCurrent: String? = null
        override suspend fun prepare(currentNumber: String, password: String?, twoFactorCode: String?): RotationResult<RotatePrepareResponse> {
            prepareCalls++
            lastPrepareCurrent = currentNumber
            return prepareAnswer
        }
        override suspend fun confirm(newNumber: String): RotationResult<Unit> {
            confirmCalls++
            return confirmAnswer
        }
    }

    private fun refused(f: RotationFailure) = RotationResult.Refused(f)

    private suspend fun toConfirm(flow: ReplaceNumberFlow) {
        flow.prepare(current, null, null)
        flow.setSavedAcknowledged(true)
        flow.continueToConfirm()
    }

    @Test
    fun `the happy path walks proof, save, confirm, done and drops the number`() = runTest {
        val gw = Fake()
        val flow = ReplaceNumberFlow(gw)
        assertEquals(ReplaceStep.PROOF, flow.state.value.step)
        flow.prepare("1111 1111 1111 1111 1111 1111", null, null)
        assertEquals("separators are stripped", current, gw.lastPrepareCurrent)
        assertEquals(ReplaceStep.SAVE, flow.state.value.step)
        assertEquals(fresh, flow.newNumberDigits())
        flow.setSavedAcknowledged(true)
        flow.continueToConfirm()
        assertEquals(ReplaceStep.CONFIRM, flow.state.value.step)
        flow.confirm(fresh)
        assertEquals(ReplaceStep.DONE, flow.state.value.step)
        assertFalse("the number is dropped once it is replaced", flow.holdsNumber())
    }

    @Test
    fun `the saved box starts unticked and gates the way on`() = runTest {
        val flow = ReplaceNumberFlow(Fake())
        flow.prepare(current, null, null)
        assertFalse(flow.state.value.savedAcknowledged)
        flow.continueToConfirm()
        assertEquals("cannot continue unticked", ReplaceStep.SAVE, flow.state.value.step)
        flow.setSavedAcknowledged(true)
        flow.continueToConfirm()
        assertEquals(ReplaceStep.CONFIRM, flow.state.value.step)
        flow.showNumberAgain()
        assertEquals(ReplaceStep.SAVE, flow.state.value.step)
        assertFalse("unticked again on every arrival", flow.state.value.savedAcknowledged)
    }

    @Test
    fun `a 409 not pending or account changed restarts at step one and drops the number`() = runTest {
        for (f in listOf(RotationFailure.NOT_PENDING, RotationFailure.ACCOUNT_CHANGED)) {
            val gw = Fake()
            val flow = ReplaceNumberFlow(gw)
            toConfirm(flow)
            gw.confirmAnswer = refused(f)
            flow.confirm(fresh)
            assertEquals(ReplaceStep.PROOF, flow.state.value.step)
            assertEquals(f, flow.state.value.failure)
            assertFalse(flow.holdsNumber())
        }
    }

    @Test
    fun `a 403 disabled lands on step one`() = runTest {
        val gw = Fake()
        val flow = ReplaceNumberFlow(gw)
        gw.prepareAnswer = refused(RotationFailure.DISABLED)
        flow.prepare(current, null, null)
        assertEquals(ReplaceStep.PROOF, flow.state.value.step)
        assertEquals(RotationFailure.DISABLED, flow.state.value.failure)
    }

    @Test
    fun `a 400 mismatch from the server stays on step three with the number held`() = runTest {
        val gw = Fake()
        val flow = ReplaceNumberFlow(gw)
        toConfirm(flow)
        gw.confirmAnswer = refused(RotationFailure.MISMATCH)
        flow.confirm(fresh)
        assertEquals(ReplaceStep.CONFIRM, flow.state.value.step)
        assertEquals(RotationFailure.MISMATCH, flow.state.value.failure)
        assertTrue(flow.holdsNumber())
    }

    @Test
    fun `a wrong re-typed number is caught locally without spending a server attempt`() = runTest {
        val gw = Fake()
        val flow = ReplaceNumberFlow(gw)
        toConfirm(flow)
        flow.confirm("333333333333333333333333")
        assertEquals(0, gw.confirmCalls)
        assertEquals(RotationFailure.MISMATCH, flow.state.value.failure)
        assertEquals(ReplaceStep.CONFIRM, flow.state.value.step)
    }

    @Test
    fun `a lost confirm keeps step three and the number, and a retry is allowed`() = runTest {
        val gw = Fake()
        val flow = ReplaceNumberFlow(gw)
        toConfirm(flow)
        gw.confirmAnswer = refused(RotationFailure.NETWORK)
        flow.confirm(fresh)
        assertEquals(ReplaceStep.CONFIRM, flow.state.value.step)
        assertEquals(RotationFailure.NETWORK, flow.state.value.failure)
        assertFalse(flow.state.value.busy)
        assertTrue("the number is still in memory", flow.holdsNumber())
        assertEquals(fresh, flow.newNumberDigits())

        gw.confirmAnswer = RotationResult.Ok(Unit)
        flow.confirm(fresh)
        assertEquals(2, gw.confirmCalls)
        assertEquals(ReplaceStep.DONE, flow.state.value.step)
    }

    @Test
    fun `a handler 401, a 429 and other refusals are shown where the user is`() = runTest {
        val shown = listOf(
            RotationFailure.PROOF_INVALID,
            RotationFailure.TOO_MANY,
            RotationFailure.REJECTED,
            RotationFailure.SESSION_EXPIRED,
        )
        for (f in shown) {
            val gw = Fake()
            val flow = ReplaceNumberFlow(gw)
            gw.prepareAnswer = refused(f)
            flow.prepare(current, null, null)
            assertEquals(ReplaceStep.PROOF, flow.state.value.step)
            assertEquals(f, flow.state.value.failure)
            assertFalse(flow.state.value.busy)
        }
        val gw = Fake()
        val flow = ReplaceNumberFlow(gw)
        toConfirm(flow)
        gw.confirmAnswer = refused(RotationFailure.TOO_MANY)
        flow.confirm(fresh)
        assertEquals(ReplaceStep.CONFIRM, flow.state.value.step)
        assertTrue(flow.holdsNumber())
    }

    @Test
    fun `dismissing clears the number and the state`() = runTest {
        val flow = ReplaceNumberFlow(Fake())
        toConfirm(flow)
        flow.clear()
        assertFalse(flow.holdsNumber())
        assertEquals("", flow.newNumberDigits())
        assertEquals(ReplaceNumberState(), flow.state.value)
    }

    @Test
    fun `an empty new number from the server is refused`() = runTest {
        val gw = Fake()
        val flow = ReplaceNumberFlow(gw)
        gw.prepareAnswer = RotationResult.Ok(RotatePrepareResponse(ok = true, newNumber = "", expiresInSeconds = 600))
        flow.prepare(current, null, null)
        assertEquals(RotationFailure.REJECTED, flow.state.value.failure)
        assertFalse(flow.holdsNumber())
    }
}
