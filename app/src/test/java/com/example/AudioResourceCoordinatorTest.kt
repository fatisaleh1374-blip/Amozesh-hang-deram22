package com.example

import com.example.audio.AudioResourceCoordinator
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AudioResourceCoordinatorTest {
    @Test
    fun ownershipIsExclusiveAndReleaseIsIdempotent() {
        val first = AudioResourceCoordinator.tryAcquire("analysis")
        assertNotNull(first)
        assertNull(AudioResourceCoordinator.tryAcquire("custom-sample"))

        first!!.close()
        first.close()

        val second = AudioResourceCoordinator.tryAcquire("custom-sample")
        assertNotNull(second)
        second!!.close()
    }

    @Test
    fun sameOwnerCannotCreateIndependentLease() {
        val first = AudioResourceCoordinator.tryAcquire("analysis")
        val second = AudioResourceCoordinator.tryAcquire("analysis")

        assertNotNull(first)
        assertNotNull(second)

        first!!.close()
        second!!.close()
    }
}
