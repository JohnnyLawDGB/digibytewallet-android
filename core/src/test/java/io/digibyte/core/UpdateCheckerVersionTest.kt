package io.digibyte.core

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version order the in-app updater uses for the four-part patch releases of the final
 * io.digibyte version. A v4.0.87 install must be offered 4.0.87.1, a 4.0.87.1 install must be
 * offered 4.0.87.2, and neither must be offered anything older than, or equal to, itself.
 */
class UpdateCheckerVersionTest {

    private val checker = UpdateChecker(OkHttpClient())

    @Test fun a_patch_release_is_newer_than_the_version_it_patches() {
        assertTrue(checker.isNewer("4.0.87.1", "4.0.87"))
    }

    @Test fun nothing_older_or_equal_is_offered_to_a_patch_release() {
        assertFalse(checker.isNewer("4.0.87", "4.0.87.1"))
        assertFalse(checker.isNewer("4.0.87.1", "4.0.87.1"))
        assertFalse(checker.isNewer("4.0.86", "4.0.87.1"))
        assertFalse(checker.isNewer("4.0.87-beta", "4.0.87.1"))
    }

    @Test fun a_later_three_part_version_is_still_newer_than_a_patch_release() {
        assertTrue(checker.isNewer("4.0.88", "4.0.87.1"))
        assertTrue(checker.isNewer("4.0.87.2", "4.0.87.1"))
    }

    // One four-part version to the next is the comparison 4.0.87.1 never had to make.
    @Test fun nothing_older_or_equal_is_offered_to_the_second_patch_release() {
        assertFalse(checker.isNewer("4.0.87.1", "4.0.87.2"))
        assertFalse(checker.isNewer("4.0.87.2", "4.0.87.2"))
        assertFalse(checker.isNewer("4.0.87", "4.0.87.2"))
        assertFalse(checker.isNewer("4.0.87.2-beta", "4.0.87.2"))
    }
}
