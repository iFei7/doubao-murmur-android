package com.doubao.murmur.tv.bridge

/**
 * Caller allow-list for the exported bridge service.
 *
 * The service spends the viewer's doubao session on every utterance, so binding must be limited to
 * the one app that is supposed to use it: the patched Gboard. No signature check is possible — the
 * patched Gboard is re-signed with a different key — therefore the package name, resolved from the
 * calling uid by `PackageManager.getPackagesForUid`, is the contract.
 *
 * Kept as a pure function so the JVM tests can cover null/empty/foreign uid results.
 */
object BridgeCallerVerifier {

    /** Package name of Gboard; the patched build keeps it so that the ROM's IME picker works. */
    const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"

    /** True when [packages] (the packages sharing the calling uid) contains Gboard. */
    fun isAllowed(packages: Array<String>?): Boolean =
        packages != null && packages.any { it == GBOARD_PACKAGE }
}
