package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Words and dates for the code that builds UI state outside Compose (mappers, presenters, the
 * ViewModel). Compose code uses `stringResource` directly. Behind an interface so JVM tests can
 * resolve the same `strings.xml` without an Android runtime.
 */
interface Texts {
    fun string(@StringRes id: Int, vararg args: Any): String

    /** [args] are the complete format arguments, as for `Resources.getQuantityString`. */
    fun plural(@PluralsRes id: Int, quantity: Int, vararg args: Any): String

    /** "Oct 21" in the user's locale. */
    fun monthDay(date: LocalDate): String

    /** "Oct 21, 2025" in the user's locale. */
    fun fullDate(date: LocalDate): String

    /** "2:34 PM" or "14:34" in the user's locale. */
    fun timeOfDay(time: LocalDateTime): String
}

class ResourceTexts(context: Context) : Texts {
    /**
     * The app is English only, so on a device set to another language the resources are read with a
     * US English configuration: plural rules (Chinese has no "one"), and dates, then follow English
     * instead of mixing that language's words and rules into English text. An English device keeps
     * its own region (en-GB gives "28 Oct").
     */
    private val resources = context.applicationContext.let { app ->
        val configuration = app.resources.configuration
        if (configuration.locales[0].language == "en") {
            app.resources
        } else {
            app.createConfigurationContext(android.content.res.Configuration(configuration).apply { setLocale(Locale.US) }).resources
        }
    }

    private fun locale(): Locale = resources.configuration.locales[0]

    override fun string(id: Int, vararg args: Any) = resources.getString(id, *args)

    override fun plural(id: Int, quantity: Int, vararg args: Any) = resources.getQuantityString(id, quantity, *args)

    private fun format(skeleton: String, time: LocalDateTime): String {
        val date = Date.from(time.atZone(ZoneId.systemDefault()).toInstant())
        return android.icu.text.DateFormat.getInstanceForSkeleton(skeleton, locale()).format(date)
    }

    override fun monthDay(date: LocalDate) = format("MMMd", date.atStartOfDay())

    override fun fullDate(date: LocalDate) = format("yMMMd", date.atStartOfDay())

    override fun timeOfDay(time: LocalDateTime) = format("jm", time)
}
