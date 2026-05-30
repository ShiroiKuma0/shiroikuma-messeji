package org.fossify.messages.activities

import com.google.android.material.appbar.MaterialToolbar
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.messages.R
import org.fossify.messages.extensions.ThemeSlot
import org.fossify.messages.extensions.themeColor

open class SimpleActivity : BaseSimpleActivity() {
    // Apply the configurable "Settings screen" header + back-arrow colours to a screen's toolbar.
    // Call after setupTopAppBar(). Skip on screens whose toolbar isn't on the primary colour (e.g. the thread).
    fun applyThemeChrome(toolbar: MaterialToolbar) {
        toolbar.setTitleTextColor(themeColor(ThemeSlot.SETTINGS_TITLE))
        toolbar.navigationIcon?.applyColorFilter(themeColor(ThemeSlot.SETTINGS_BACK))
    }

    override fun getAppIconIDs() = arrayListOf(
        R.mipmap.ic_launcher_red,
        R.mipmap.ic_launcher_pink,
        R.mipmap.ic_launcher_purple,
        R.mipmap.ic_launcher_deep_purple,
        R.mipmap.ic_launcher_indigo,
        R.mipmap.ic_launcher_blue,
        R.mipmap.ic_launcher_light_blue,
        R.mipmap.ic_launcher_cyan,
        R.mipmap.ic_launcher_teal,
        R.mipmap.ic_launcher,
        R.mipmap.ic_launcher_light_green,
        R.mipmap.ic_launcher_lime,
        R.mipmap.ic_launcher_yellow,
        R.mipmap.ic_launcher_amber,
        R.mipmap.ic_launcher_orange,
        R.mipmap.ic_launcher_deep_orange,
        R.mipmap.ic_launcher_brown,
        R.mipmap.ic_launcher_blue_grey,
        R.mipmap.ic_launcher_grey_black
    )

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun getRepositoryName() = "Messages"
}
