package io.github.katznmeik.cinexperience.core.program

import android.content.SharedPreferences

/** Stores the [DownloadPermission]. Blocking; call it on an IO dispatcher. */
internal interface ProgramSettings {
    /** The stored permission; [DownloadPermission.NOT_ASKED] if none (or an unknown value) is stored. */
    fun readPermission(): DownloadPermission

    /** Returns false if it couldn't be stored. */
    fun writePermission(permission: DownloadPermission): Boolean
}

/**
 * The download permission in shared preferences (`program.xml`), like the app's
 * other settings; covered by Auto Backup (spec §8). Anything unexpected reads as
 * [DownloadPermission.NOT_ASKED], which never downloads.
 */
internal class SharedPreferencesProgramSettings(private val prefs: SharedPreferences) : ProgramSettings {

    override fun readPermission(): DownloadPermission = when (prefs.getString(KEY_PERMISSION, null)) {
        VALUE_ALLOWED -> DownloadPermission.ALLOWED
        VALUE_OFF -> DownloadPermission.OFF
        else -> DownloadPermission.NOT_ASKED
    }

    override fun writePermission(permission: DownloadPermission): Boolean {
        val editor = prefs.edit()
        when (permission) {
            DownloadPermission.NOT_ASKED -> editor.remove(KEY_PERMISSION)
            DownloadPermission.ALLOWED -> editor.putString(KEY_PERMISSION, VALUE_ALLOWED)
            DownloadPermission.OFF -> editor.putString(KEY_PERMISSION, VALUE_OFF)
        }
        return editor.commit()
    }

    companion object {
        const val PREFERENCES_NAME: String = "program"
        private const val KEY_PERMISSION = "download_permission"
        private const val VALUE_ALLOWED = "allowed"
        private const val VALUE_OFF = "off"
    }
}
