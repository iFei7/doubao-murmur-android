package com.doubao.murmur.tv.app.login

import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsSessionToken

/**
 * Empty [CustomTabsService] implementation.
 *
 * Declaring a service of this type is the documented prerequisite for sharing the session data
 * of a Custom Tab with the application's own WebView: when the login URL is opened with a
 * session created through `CustomTabsClient.bindCustomTabsService`, a supporting browser stores
 * that tab's cookies and localStorage in a profile backed by **this app's** data directory.
 * `CookieManager` (the global jar) and the offscreen WebView of [LoginActivity] then read back
 * exactly what the user typed into the Custom Tab - that is the whole collection mechanism of
 * the Custom Tabs login route.
 *
 * The service itself carries no logic. The browser binds to it only to validate the session
 * association; every protocol callback returns the "not supported" default, and [warmup] and
 * [newSession] simply acknowledge so the provider considers the session usable. No credential
 * ever passes through this class - it is a manifest-level declaration, not a data channel.
 */
class LoginCustomTabsService : CustomTabsService() {

    override fun warmup(flags: Long): Boolean = true

    override fun newSession(sessionToken: CustomTabsSessionToken): Boolean = true

    override fun mayLaunchUrl(
        sessionToken: CustomTabsSessionToken,
        url: Uri?,
        extras: Bundle?,
        otherLikelyBundles: List<Bundle>?,
    ): Boolean = false

    override fun requestPostMessageChannel(
        sessionToken: CustomTabsSessionToken,
        postMessageOrigin: Uri,
    ): Boolean = false

    override fun postMessage(
        sessionToken: CustomTabsSessionToken,
        message: String,
        extras: Bundle?,
    ): Int = RESULT_FAILURE_DISALLOWED

    override fun validateRelationship(
        sessionToken: CustomTabsSessionToken,
        relation: Int,
        origin: Uri,
        extras: Bundle?,
    ): Boolean = false

    override fun updateVisuals(
        sessionToken: CustomTabsSessionToken,
        bundle: Bundle?,
    ): Boolean = false

    override fun receiveFile(
        sessionToken: CustomTabsSessionToken,
        uri: Uri,
        purpose: Int,
        extras: Bundle?,
    ): Boolean = false

    override fun extraCommand(commandName: String, args: Bundle?): Bundle? = null
}
