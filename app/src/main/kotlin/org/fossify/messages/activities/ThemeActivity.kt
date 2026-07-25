package org.fossify.messages.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityThemeBinding
import org.fossify.messages.databinding.ItemThemeColorBinding
import org.fossify.messages.databinding.ItemThemeSectionBinding
import org.fossify.messages.databinding.ItemThemeSubgroupBinding
import org.fossify.messages.databinding.ItemThemeSwitchBinding
import org.fossify.messages.databinding.ItemThemeTextBinding
import org.fossify.messages.databinding.ItemThemeTokenBinding
import org.fossify.messages.databinding.ItemThemeValueBinding
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

// A row description: 85 % size, 60 % opacity — the same dimmed second line the sister apps use.
private const val DESCRIPTION_TEXT_SCALE = 0.85f
private const val DESCRIPTION_ALPHA = 0.6f

// How many hex characters of the automation token stay visible at each end.
private const val TOKEN_ABBREVIATION_EDGE = 8

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

        // Automation sits directly below the export row it drives — the placement every sister app
        // shares, so 白い熊 finds it where backup lives.
        addAutomationSubgroup(primaryColor)
    }

    private fun openExportImport() {
        eximDialog = ExportImportDialog(
            activity = this,
            onPickDirectory = { eximDirPicker.launch(SettingsEximport.getDirUri(this)) },
            onSaveAs = { suggestedName -> eximSaveAsLauncher.launch(suggestedName) },
            onPickImportFile = {
                eximImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            },
            onChainClosed = { finish() },
        )
    }

    // ---- Automation: a subgroup of Export / Import, since the automation intent drives that very
    // export headlessly (see receivers/StateExportReceiver) ----

    private fun addAutomationSubgroup(primaryColor: Int) {
        addSubgroupHeader(getString(R.string.automation), primaryColor)

        // Two rows, in the order every sister app uses: the master switch (default OFF), then the token.
        addSwitchRow(
            labelRes = R.string.enable_automation,
            checked = config.automationEnabled,
            indentLevel = 2,
            description = getString(R.string.enable_automation_desc),
        ) {
            config.automationEnabled = it
        }

        addTokenRow()

        // All-files access: needed so an automation broadcast can write to an arbitrary absolute path
        // (e.g. 白い熊's archive folder) outside Download/Documents. API 30+ only.
        if (isRPlus()) {
            val granted = Environment.isExternalStorageManager()
            val state = getString(if (granted) R.string.all_files_access_granted else R.string.all_files_access_needed)
            addValueRow(R.string.all_files_access, state, indentLevel = 2) { openAllFilesAccessSettings() }
        }
    }

    /**
     * The automation-token row: label plus the abbreviated token, tapping anywhere copies the full
     * token, and a Regenerate action on the right warns before invalidating pasted copies.
     */
    private fun addTokenRow() {
        val row = ItemThemeTokenBinding.inflate(layoutInflater, binding.themeHolder, false)
        row.themeTokenLabel.text = getString(R.string.automation_token)
        row.themeTokenLabel.setTextColor(getProperTextColor())
        row.themeTokenValue.text = abbreviateToken(config.automationToken)
        row.themeTokenValue.setTextColor(getProperTextColor())
        row.themeTokenRegenerate.text = getString(R.string.automation_token_regenerate)
        row.themeTokenRegenerate.setTextColor(getProperPrimaryColor())
        row.root.setOnClickListener {
            // Not commons' copyToClipboard: that one toasts the value itself, which would put the
            // full secret back on screen right after we deliberately abbreviated it.
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(getString(R.string.automation_token), config.automationToken))
            toast(R.string.automation_token_copied)
        }
        row.themeTokenRegenerate.setOnClickListener {
            ConfirmationDialog(
                activity = this,
                message = getString(R.string.automation_token_regenerate_warning),
                positive = R.string.automation_token_regenerate,
                negative = org.fossify.commons.R.string.cancel,
            ) {
                row.themeTokenValue.text = abbreviateToken(config.regenerateAutomationToken())
                toast(R.string.automation_token_regenerated)
            }
        }
        indentRow(row.root, level = 2)
        binding.themeHolder.addView(row.root)
    }

    private fun abbreviateToken(token: String): String =
        if (token.length <= TOKEN_ABBREVIATION_EDGE * 2) {
            token
        } else {
            token.take(TOKEN_ABBREVIATION_EDGE) + "…" + token.takeLast(TOKEN_ABBREVIATION_EDGE)
        }

    // Two OEM-dependent Settings screens, tried in order: the per-app one, then the system-wide list.
    // The first failure is expected on ROMs that lack the per-app screen, so it is deliberately silent.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun openAllFilesAccessSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                showErrorToast(e2)
            }
        }
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
        indentLevel: Int = 1,
        onClick: (TextView) -> Unit,
    ) {
        val row = ItemThemeValueBinding.inflate(layoutInflater, binding.themeHolder, false)
        row.themeValueLabel.text = getString(labelRes)
        row.themeValueLabel.setTextColor(getProperTextColor())
        row.themeValue.setTextColor(valueColor ?: getProperTextColor())
        row.themeValue.text = value
        row.root.setOnClickListener { onClick(row.themeValue) }
        indentRow(row.root, indentLevel)
        binding.themeHolder.addView(row.root)
    }

    private fun addSwitchRow(
        @StringRes labelRes: Int,
        checked: Boolean,
        indentLevel: Int = 1,
        description: String? = null,
        onToggle: (Boolean) -> Unit,
    ) {
        val row = ItemThemeSwitchBinding.inflate(layoutInflater, binding.themeHolder, false)
        val title = getString(labelRes)
        row.themeSwitchLabel.text = if (description == null) title else titleWithDescription(title, description)
        row.themeSwitchLabel.setTextColor(getProperTextColor())
        row.themeSwitch.isChecked = checked
        row.root.setOnClickListener {
            row.themeSwitch.toggle()
            onToggle(row.themeSwitch.isChecked)
        }
        indentRow(row.root, indentLevel)
        binding.themeHolder.addView(row.root)
    }

    // A row's explanation, as a smaller dimmed line below its title — the value rows' styling, without
    // needing a second view in the switch layout.
    private fun titleWithDescription(title: String, description: String): CharSequence =
        SpannableStringBuilder(title).apply {
            append("\n")
            val start = length
            append(description)
            val dimmed = ForegroundColorSpan(getProperTextColor().adjustAlpha(DESCRIPTION_ALPHA))
            setSpan(RelativeSizeSpan(DESCRIPTION_TEXT_SCALE), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(dimmed, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
