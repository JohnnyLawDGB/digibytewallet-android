package io.digibyte.core

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version order the in-app updater uses for 4.0.87.1, a four-part patch release of the
 * final io.digibyte version. A v4.0.87 install must be offered it, and a 4.0.87.1 install must
 * not be offered 4.0.87 (or itself) back.
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
}
