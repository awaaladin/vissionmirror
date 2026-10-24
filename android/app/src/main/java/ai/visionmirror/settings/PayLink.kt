package ai.visionmirror.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Paying happens on our website, in the browser. The app only opens the page (with the sign-in email filled in). */
object PayLink {
    const val SITE = "https://visionmirror-app.vercel.app"

    fun url(email: String?): String =
        "$SITE/pay" + (email?.takeIf { it.isNotBlank() }?.let { "?from=app&email=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "?from=app")

    /** True when a browser was opened. */
    fun open(context: Context, email: String?): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url(email))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
