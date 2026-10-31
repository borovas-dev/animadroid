package com.borovas.animadroid.feature.recommend

import com.borovas.animadroid.feature.recommend.ModelState.Downloading.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStateTest {
    private val fileOk = ModelSpec.SIZE_BYTES

    @Test fun nothingMeansNotInstalled() = assertEquals(ModelState.NotInstalled, reduceModelState(null, null, null, null))

    @Test fun smallStrayFileIsNotReady() = assertEquals(ModelState.NotInstalled, reduceModelState(null, null, null, 10L))

    @Test fun runningDownload() {
        val s = reduceModelState(DownloadSnapshot(WorkPhase.RUNNING, bytes = 1_294_073_856), null, null, null) as ModelState.Downloading
        assertEquals(Phase.DOWNLOADING, s.phase)
        assertEquals(0.5f, s.fraction!!, 0.001f)
    }

    @Test fun verifyingPhase() {
        val s = reduceModelState(DownloadSnapshot(WorkPhase.RUNNING, bytes = fileOk, verifying = true), null, null, null) as ModelState.Downloading
        assertEquals(Phase.VERIFYING, s.phase)
    }

    @Test fun enqueuedMeansWaitingForNetworkAndIsIndeterminate() {
        val s = reduceModelState(DownloadSnapshot(WorkPhase.ENQUEUED, bytes = 5), null, null, null) as ModelState.Downloading
        assertEquals(Phase.WAITING_FOR_NETWORK, s.phase)
        assertEquals(null, s.fraction)
    }

    @Test fun succeededWithFileIsReady() =
        assertEquals(ModelState.Ready(fileOk), reduceModelState(DownloadSnapshot(WorkPhase.SUCCEEDED), null, null, fileOk))

    @Test fun failedBecomesErrorWithMessage() {
        val s = reduceModelState(DownloadSnapshot(WorkPhase.FAILED, error = "Checksum mismatch"), null, null, null)
        assertEquals(ModelState.Error("Checksum mismatch"), s)
    }

    @Test fun cancelledKeepsNotInstalled() =
        assertEquals(ModelState.NotInstalled, reduceModelState(DownloadSnapshot(WorkPhase.CANCELLED), null, null, null))

    @Test fun importingWinsAndImportErrorSurfaces() {
        val imp = reduceModelState(null, ImportSnapshot(10, 100), null, null) as ModelState.Downloading
        assertEquals(Phase.IMPORTING, imp.phase)
        assertEquals(ModelState.Error("bad"), reduceModelState(null, null, "bad", null))
    }

    @Test fun existingModelBeatsStaleFailure() =
        assertTrue(reduceModelState(DownloadSnapshot(WorkPhase.FAILED, error = "x"), null, null, fileOk) is ModelState.Ready)

    @Test fun requirements() {
        val gb = 1_073_741_824L
        assertTrue(evaluateRequirements(8 * gb, 10 * gb).isEmpty())
        assertTrue(evaluateRequirements((5.6 * gb).toLong(), 10 * gb).isEmpty()) // "6 GB" phones report ~5.6 GiB
        assertEquals(listOf<Class<*>>(RequirementProblem.NotEnoughRam::class.java), evaluateRequirements(4 * gb, 10 * gb).map { it::class.java })
        val space = evaluateRequirements(8 * gb, 2 * gb)
        assertTrue(space.single() is RequirementProblem.NotEnoughSpace)
        // a resumable partial reduces the space needed
        assertTrue(evaluateRequirements(8 * gb, 2 * gb, alreadyDownloadedBytes = 1_500_000_000L).isEmpty())
        assertTrue(space.single().describe().contains("Not enough free storage"))
    }
}
