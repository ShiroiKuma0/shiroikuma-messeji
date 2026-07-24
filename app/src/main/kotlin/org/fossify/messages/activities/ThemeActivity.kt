package org.fossify.messages.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityThemeBinding
import org.fossify.messages.databinding.ItemThemeColorBinding
import org.fossify.messages.databinding.ItemThemeSectionBinding
import org.fossify.messages.databinding.ItemThemeSubgroupBinding
import org.fossify.messages.databinding.ItemThemeSwitchBinding
import org.fossify.messages.databinding.ItemThemeTextBinding
import org.fossify.messages.databinding.ItemThemeValueBinding
import org.fossify.messages.BuildConfig
import org.fossify.messages.dialogs.AlphaColorPickerDialog
import org.fossify.messages.dialogs.ExportImportDialog
import org.fossify.messages.dialogs.FontPickerDialog
import org.fossify.messages.extensions.FontWeightOption
import org.fossify.messages.extensions.MessageTimeFormat
import org.fossify.messages.extensions.messageTimeFormatOf
import org.fossify.messages.extensions.ThemeGroup
import org.fossify.messages.extensions.ThemeSection
import org.fossify.messages.extensions.ThemeSlot
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.fontDisplayName
import org.fossify.messages.extensions.importFont
import org.fossify.messages.extensions.resetThemeColor
import org.fossify.messages.extensions.setThemeColor
import org.fossify.messages.extensions.showFontSample
import org.fossify.messages.extensions.themeColor
import org.fossify.messages.helpers.EXIM_WARN_COLOR
import org.fossify.messages.helpers.MAX_FONT_SIZE_SP
import org.fossify.messages.helpers.SettingsEximport

// kxkb indent ladder: section headings at 36dp (in XML), their rows one step in at 72dp,
// sub-headings at 54dp (in XML), their rows at 90dp — so rows sit at (base + level * step).
private const val ROW_INDENT_BASE_DP = 54
private const val ROW_INDENT_STEP_DP = 18

@Suppress("TooManyFunctions")
class ThemeActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityThemeBinding::inflate)
    private val previews = HashMap<ThemeSlot, ImageView>()

    private var pendingFontSlot: ThemeSlot? = null
    private var pendingFontBinding: ItemThemeTextBinding? = null

    private val fontImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onFontImported(uri)
    }

    // Export/Import panel plumbing: the SAF pickers live on the activity, the panel drives them.
    private var eximDialog: ExportImportDialog? = null

    private val eximDirPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            SettingsEximport.setDirUri(this, uri)
            eximDialog?.refreshStatus()
        }
    }

    private val eximSaveAsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                eximDialog?.exportToUri(uri)
            }
        }

    private val eximImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            eximDialog?.importFrom(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.themeNestedScrollview))
        setupMaterialScrollListener(binding.themeNestedScrollview, binding.themeAppbar)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.themeAppbar, NavigationIcon.Arrow)
        applyThemeChrome(binding.themeToolbar)
        buildRows()
    }

    private fun buildRows() {
        binding.themeHolder.removeAllViews()
        previews.clear()

        val primaryColor = getProperPrimaryColor()

        addEximportSection(primaryColor)
        addFormatSection(primaryColor)

        ThemeSection.entries.forEach { section ->
            addSectionHeader(getString(section.labelRes), primaryColor)
            val groups = ThemeGroup.entries.filter { it.section == section }
            val showSubgroups = groups.size > 1
            groups.forEach { group ->
                if (showSubgroups) {
                    // subgroup header sits one level in; its rows another level in
                    addSubgroupHeader(getString(group.labelRes), primaryColor)
                    addGroupSlots(group, indentLevel = 2)
                } else {
                    // section with no subgroups: its rows sit one level in
                    addGroupSlots(group, indentLevel = 1)
                }
            }
        }
    }

    private fun addGroupSlots(group: ThemeGroup, indentLevel: Int) {
        ThemeSlot.entries.filter { it.group == group }.forEach { slot ->
            if (slot.hasFont) addTextSlot(slot, indentLevel) else addColorSlot(slot, indentLevel)
        }
    }

    private fun addSectionHeader(label: String, primaryColor: Int, isFirst: Boolean = false) {
        val item = ItemThemeSectionBinding.inflate(layoutInflater, binding.themeHolder, false)
        item.themeSectionLabel.text = label
        item.themeSectionLabel.setTextColor(primaryColor)
        item.themeSectionDivider.setBackgroundColor(primaryColor)
        // the 1px hairline separates this section from the previous one; the first section has none
        item.themeSectionSeparator.setBackgroundColor(primaryColor)
        item.themeSectionSeparator.beGoneIf(isFirst)
        binding.themeHolder.addView(item.root)
    }

    // Export/Import of every settable item, the first section of the page. The row beneath the
    // heading shows the newest export in the configured directory (queried on page open) and
    // opens the category panel.
    private fun addEximportSection(primaryColor: Int) {
        addSectionHeader(getString(R.string.eim_section), primaryColor, isFirst = true)
        val (status, isWarning) = SettingsEximport.lastExportStatus(this)
        addValueRow(
            labelRes = R.string.eim_row_label,
            value = status,
            valueColor = if (isWarning) EXIM_WARN_COLOR else null,
        ) { openExportImport() }
    }

    private fun openExportImport() {
        eximDialog = ExportImportDialog(
            activity = this,
            versionName = BuildConfig.VERSION_NAME,
            onPickDirectory = { eximDirPicker.launch(SettingsEximport.getDirUri(this)) },
            onSaveAs = { suggestedName -> eximSaveAsLauncher.launch(suggestedName) },
            onPickImportFile = {
                eximImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            },
            onChainClosed = { finish() },
        )
    }

    // Date & time display formats, shown above the colour sections. Time-of-day (today's messages)
    // is a 4-way picker defaulting to the kanji clock; the imperial-era toggle controls earlier dates.
    private fun addFormatSection(primaryColor: Int) {
        addSectionHeader(getString(R.string.format_section), primaryColor)
        addValueRow(
            R.string.format_time_label,
            getString(messageTimeFormatOf(config.messageTimeFormat).labelRes)
        ) { valueView ->
            val items = ArrayList(MessageTimeFormat.entries.map { RadioItem(it.ordinal, getString(it.labelRes)) })
            RadioGroupDialog(this, items, config.messageTimeFormat) {
                config.messageTimeFormat = it as Int
                valueView.text = getString(messageTimeFormatOf(config.messageTimeFormat).labelRes)
            }
        }
        addSwitchRow(R.string.use_imperial_date, config.useImperialDate) { config.useImperialDate = it }
    }

    private fun addValueRow(
        @StringRes labelRes: Int,
        value: String,
        valueColor: Int? = null,
        onClick: (TextView) -> Unit,
    ) {
        val row = ItemThemeValueBinding.inflate(layoutInflater, binding.themeHolder, false)
        row.themeValueLabel.text = getString(labelRes)
        row.themeValueLabel.setTextColor(getProperTextColor())
        row.themeValue.setTextColor(valueColor ?: getProperTextColor())
        row.themeValue.text = value
        row.root.setOnClickListener { onClick(row.themeValue) }
        indentRow(row.root, level = 1)
        binding.themeHolder.addView(row.root)
    }

    private fun addSwitchRow(@StringRes labelRes: Int, checked: Boolean, onToggle: (Boolean) -> Unit) {
        val row = ItemThemeSwitchBinding.inflate(layoutInflater, binding.themeHolder, false)
        row.themeSwitchLabel.text = getString(labelRes)
        row.themeSwitchLabel.setTextColor(getProperTextColor())
        row.themeSwitch.isChecked = checked
        row.root.setOnClickListener {
            row.themeSwitch.toggle()
            onToggle(row.themeSwitch.isChecked)
        }
        indentRow(row.root, level = 1)
        binding.themeHolder.addView(row.root)
    }

    private fun addSubgroupHeader(label: String, primaryColor: Int) {
        val item = ItemThemeSubgroupBinding.inflate(layoutInflater, binding.themeHolder, false)
        item.themeSubgroupLabel.text = label
        item.themeSubgroupLabel.setTextColor(primaryColor)
        item.themeSubgroupUnderline.setBackgroundColor(primaryColor)
        binding.themeHolder.addView(item.root)
    }

    private fun addColorSlot(slot: ThemeSlot, indentLevel: Int) {
        val row = ItemThemeColorBinding.inflate(layoutInflater, binding.themeHolder, false)
        row.themeColorLabel.text = getString(slot.labelRes)
        row.themeColorLabel.setTextColor(getProperTextColor())
        row.themeColorPreview.background.setTint(themeColor(slot))
        row.root.setOnClickListener { openColorPicker(slot) }
        indentRow(row.root, indentLevel)
        previews[slot] = row.themeColorPreview
        binding.themeHolder.addView(row.root)
    }

    @Suppress("EmptyFunctionBlock") // SeekBar's start/stop-tracking callbacks are intentionally no-ops
    private fun addTextSlot(slot: ThemeSlot, indentLevel: Int) {
        val textColor = getProperTextColor()
        val b = ItemThemeTextBinding.inflate(layoutInflater, binding.themeHolder, false)
        b.themeTextLabel.text = getString(slot.labelRes)
        listOf(
            b.themeTextLabel, b.themeTextFontTitle, b.themeTextFontValue,
            b.themeTextWeightTitle, b.themeTextWeightValue, b.themeTextSizeTitle, b.themeTextSizeValue
        ).forEach { it.setTextColor(textColor) }

        b.themeTextColorPreview.background.setTint(themeColor(slot))
        b.themeTextFontValue.text = fontDisplayName(config.getFontFamily(slot.key))
        b.themeTextWeightValue.text = getString(FontWeightOption.fromValue(config.getFontWeight(slot.key)).labelRes)
        b.themeTextSizeSeekbar.max = MAX_FONT_SIZE_SP
        b.themeTextSizeSeekbar.progress = config.getFontSize(slot.key)
        b.themeTextSizeValue.text = sizeLabel(config.getFontSize(slot.key))
        refreshSample(b, slot)

        b.themeTextColorRow.setOnClickListener { openTextColorPicker(slot, b) }
        b.themeTextFontRow.setOnClickListener { openFontPicker(slot, b) }
        b.themeTextWeightRow.setOnClickListener { openWeightPicker(slot, b) }
        b.themeTextSizeSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                config.setFontSize(slot.key, progress)
                b.themeTextSizeValue.text = sizeLabel(progress)
                refreshSample(b, slot)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        indentRow(b.root, indentLevel, contentHasInset = true)
        indentTextControls(b)
        binding.themeHolder.addView(b.root)
    }

    private fun indentStepPx() = (ROW_INDENT_STEP_DP * resources.displayMetrics.density).toInt()

    // Place a row's content on the kxkb ladder (base + level*step: 72dp under a section, 90dp under
    // a subgroup). contentHasInset = true for the text block whose inner header already carries the
    // base label inset.
    private fun indentRow(view: android.view.View, level: Int, contentHasInset: Boolean = false) {
        val baseInset = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.settings_label_start_margin)
        val base = (ROW_INDENT_BASE_DP * resources.displayMetrics.density).toInt()
        val start = base + level * indentStepPx() - (if (contentHasInset) baseInset else 0)
        view.setPaddingRelative(start, view.paddingTop, view.paddingEnd, view.paddingBottom)
    }

    // Indent a text element's font/weight/size/sample controls one full step past its name.
    private fun indentTextControls(b: ItemThemeTextBinding) {
        val step = indentStepPx()
        listOf(b.themeTextFontRow, b.themeTextWeightRow, b.themeTextSizeRow, b.themeTextSample).forEach {
            it.setPaddingRelative(it.paddingStart + step, it.paddingTop, it.paddingEnd, it.paddingBottom)
        }
    }

    private fun refreshSample(b: ItemThemeTextBinding, slot: ThemeSlot) {
        b.themeTextSample.showFontSample(
            config.getFontFamily(slot.key),
            config.getFontWeight(slot.key),
            config.getFontSize(slot.key),
            themeColor(slot)
        )
    }

    private fun sizeLabel(sp: Int) = if (sp > 0) "$sp sp" else getString(R.string.theme_size_default)

    private fun openColorPicker(slot: ThemeSlot) {
        AlphaColorPickerDialog(this, themeColor(slot), addDefaultColorButton = true) { wasPositive, color ->
            if (wasPositive) setThemeColor(slot, color) else resetThemeColor(slot)
            if (slot.isFoundation) {
                // foundation cascades into the chrome + every inheriting preview
                recreate()
            } else {
                previews[slot]?.background?.setTint(themeColor(slot))
            }
        }
    }

    private fun openTextColorPicker(slot: ThemeSlot, b: ItemThemeTextBinding) {
        AlphaColorPickerDialog(this, themeColor(slot), addDefaultColorButton = true) { wasPositive, color ->
            if (wasPositive) setThemeColor(slot, color) else resetThemeColor(slot)
            b.themeTextColorPreview.background.setTint(themeColor(slot))
            refreshSample(b, slot)
        }
    }

    private fun openFontPicker(slot: ThemeSlot, b: ItemThemeTextBinding) {
        FontPickerDialog(
            activity = this,
            onAddFont = {
                pendingFontSlot = slot
                pendingFontBinding = b
                fontImportLauncher.launch(arrayOf("*/*"))
            },
            onPick = { fileName ->
                config.setFontFamily(slot.key, fileName)
                b.themeTextFontValue.text = fontDisplayName(fileName)
                refreshSample(b, slot)
            }
        )
    }

    private fun openWeightPicker(slot: ThemeSlot, b: ItemThemeTextBinding) {
        val items = ArrayList(FontWeightOption.entries.map { RadioItem(it.value, getString(it.labelRes)) })
        RadioGroupDialog(this, items, config.getFontWeight(slot.key)) {
            val weight = it as Int
            config.setFontWeight(slot.key, weight)
            b.themeTextWeightValue.text = getString(FontWeightOption.fromValue(weight).labelRes)
            refreshSample(b, slot)
        }
    }

    private fun onFontImported(uri: Uri?) {
        val slot = pendingFontSlot
        val b = pendingFontBinding
        pendingFontSlot = null
        pendingFontBinding = null
        if (uri == null || slot == null) {
            return
        }

        val fileName = importFont(uri)
        if (fileName == null) {
            toast(R.string.font_invalid)
            return
        }

        config.setFontFamily(slot.key, fileName)
        b?.themeTextFontValue?.text = fontDisplayName(fileName)
        if (b != null) {
            refreshSample(b, slot)
        }
    }
}
