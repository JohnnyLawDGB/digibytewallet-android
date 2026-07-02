package io.digibyte.core.recovery

/** Which scanned profiles to sweep. Own-seed recovery leaves native funds in
 *  place (already this wallet's); foreign-seed recovery takes everything funded
 *  incl. native. Pure + JVM-testable; the ViewModel calls this after scanning. */
fun sweepSet(done: RecoveryScanService.State.Done, isForeign: Boolean):
    List<RecoveryScanService.ProfileResult> =
    if (isForeign) done.allWithFunds else done.nonNativeWithFunds
