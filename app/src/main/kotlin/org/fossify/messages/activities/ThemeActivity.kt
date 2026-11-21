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
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import com.google.android.material.chip.ChipGroup
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.commons.R as CR
import org.fossify.messages.databinding.ActivityThemeBinding
import org.fossify.messages.databinding.ItemThemeActionBinding
import org.fossify.messages.databinding.ItemThemeColorBinding
import org.fossify.messages.databinding.ItemThemeSliderBinding
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
import org.fossify.messages.extensions.alignThreadTextSizes
import org.fossify.messages.extensions.clearOwnAvatar
import org.fossify.messages.extensions.importOwnAvatar
import org.fossify.messages.extensions.ownAvatarPath
import org.fossify.messages.extensions.fontDisplayName
import org.fossify.messages.extensions.importFont
import org.fossify.messages.extensions.pillBackground
import org.fossify.messages.extensions.resetThemeColor
import org.fossify.messages.extensions.setThemeColor
import org.fossify.messages.extensions.showFontSample
import org.fossify.messages.extensions.themeColor
import org.fossify.messages.helpers.EXIM_WARN_COLOR
import org.fossify.messages.helpers.MenuPills
import org.fossify.messages.helpers.ROUTE_RIN_PREFIX
import org.fossify.messages.helpers.Rindenwa
import org.fossify.messages.helpers.routeLabel
import org.fossify.messages.dialogs.pickCallRoute
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.helpers.THREAD_AVATAR_MAX_DP
import org.fossify.messages.helpers.THREAD_AVATAR_MIN_DP
import org.fossify.messages.helpers.MAX_FONT_SIZE_SP
import org.fossify.messages.helpers.SettingsEximport
import org.fossify.messages.helpers.SimSelectorSize
import org.fossify.messages.helpers.TAB_TEXT_SCALE_CHOICES
import org.fossify.messages.helpers.SwipeAction
import org.fossify.messages.helpers.simSelectorSizeOf
import org.fossify.messages.helpers.swipeActionOf

// kxkb indent ladder: section headings at 36dp (in XML), their rows one step in at 72dp,
// sub-headings at 54dp (in XML), their rows at 90dp — so rows sit at (base + level * step).
private const val ROW_INDENT_BASE_DP = 54
private const val ROW_INDENT_STEP_DP = 18

// A row description: 85 % size, in the dim-gold note colour — never a faded text colour.
private const val DESCRIPTION_TEXT_SCALE = 0.85f

// Every row is only as tall as its content: no minimum height, this much above and below.
private const val ROW_PADDING_VERTICAL_DP = 2

// The rule down the left edge of a row that carries an explanation.
private const val RULE_WIDTH_DP = 2f
private const val RULE_GAP_DP = 10f

// The icon before a row's label.
private const val ROW_ICON_DP = 20
private const val ROW_ICON_GAP_DP = 10

// A folding section and the chip index above them.
private const val SECTION_GAP_DP = 10
private const val SECTION_HEADER_PAD_DP = 8
private const val SECTION_UNDERLINE_DP = 2
private const val SECTION_UNDERLINE_GAP_DP = 2
private const val SECTION_CHEVRON_SP = 22f
private const val SECTION_TEXT_SP = 20f
private const val SECTION_START_DP = 36
private const val INDEX_GAP_DP = 6
private const val INDEX_TEXT_SP = 13f
private const val INDEX_PAD_H_DP = 10
private const val INDEX_PAD_V_DP = 5
private const val INDEX_DRAGGED_ALPHA = 0.3f

/** A top-level section of the page: its stored [key] (never rename one), its heading, and what builds its rows. */
private class UiSection(val key: String, val title: String, val fill: () -> Unit)

/**
 * The icon a row of the page carries, coloured by what the row does. The colours are the menu icon
 * roles, so a row and a menu line that do the same wear the same colour.
 */
private enum class RowIcon(val res: Int, val role: ThemeSlot) {
    SETTINGS(CR.drawable.ic_settings_cog_vector, ThemeSlot.MENU_ICON_VIEW),
    TOGGLE(CR.drawable.ic_check_circle_vector, ThemeSlot.MENU_ICON_VIEW),
    VALUE(CR.drawable.ic_edit_vector, ThemeSlot.MENU_ICON_VIEW),
    COLOR(R.drawable.ic_palette_vector, ThemeSlot.MENU_ICON_VIEW),
    TEXT(R.drawable.ic_text_fields_vector, ThemeSlot.MENU_ICON_VIEW),
    WEIGHT(R.drawable.ic_format_bold_vector, ThemeSlot.MENU_ICON_VIEW),
    SIZE(R.drawable.ic_tune_vector, ThemeSlot.MENU_ICON_VIEW),
    TIME(CR.drawable.ic_clock_vector, ThemeSlot.MENU_ICON_VIEW),
    RESET(CR.drawable.ic_reset_vector, ThemeSlot.MENU_ICON_VIEW),
    SECRET(CR.drawable.ic_lock_vector, ThemeSlot.MENU_ICON_BLOCK),
    CALL(CR.drawable.ic_phone_vector, ThemeSlot.MENU_ICON_CALL),
    SHARE(CR.drawable.ic_share_vector, ThemeSlot.MENU_ICON_SHARE),
    DIRECTORY(CR.drawable.ic_folder_vector, ThemeSlot.MENU_ICON_SHARE),
    AUTOMATION(CR.drawable.ic_code_vector, ThemeSlot.MENU_ICON_SHARE),
    MESSAGE(CR.drawable.ic_mail_vector, ThemeSlot.MENU_ICON_MESSAGE),
    PICTURE(R.drawable.ic_image_vector, ThemeSlot.MENU_ICON_CONTACT),
    PERSON(CR.drawable.ic_person_vector, ThemeSlot.MENU_ICON_CONTACT),
    REMOVE(CR.drawable.ic_delete_vector, ThemeSlot.MENU_WARNING),
}

/** The thin rule down the left edge of a row that carries an explanation, [x] px in from its start. */
private class LeftRule(color: Int, private val x: Float, private val width: Float) : Drawable() {
    private val paint = Paint().apply { this.color = color }
    override fun draw(canvas: Canvas) {
        val left = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right - x - width else bounds.left + x
        canvas.drawRect(left, bounds.top.toFloat(), left + width, bounds.bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

// How many hex characters of the automation token stay visible at each end.
private const val TOKEN_ABBREVIATION_EDGE = 8

@Suppress("TooManyFunctions")
class ThemeActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityThemeBinding::inflate)
    private val previews = HashMap<ThemeSlot, ImageView>()

    // Where the row builders add their rows: the body of the section being filled.
    private lateinit var body: ViewGroup

    // The page's sections in their shown order, and each one's views by its key.
    private var sections: List<UiSection> = emptyList()
    private val sectionWrappers = HashMap<String, View>()
    private val sectionBodies = HashMap<String, LinearLayout>()
    private val sectionChevrons = HashMap<String, TextView>()
    private val sectionsFilled = HashSet<String>()

    private var pendingFontSlot: ThemeSlot? = null
    private var pendingFontBinding: ItemThemeTextBinding? = null

    private val fontImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onFontImported(uri)
    }

    private val ownAvatarPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (!importOwnAvatar(uri)) toast(CR.string.unknown_error_occurred)
            buildRows()
        }
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
        alignThreadTextSizes()
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

    // The page: the way to the app's own settings, the chip index, then the sections — each a folding
    // block, folded unless 白い熊 opened it, in the order 白い熊 dragged the chips into. A folded
    // section's rows are not built until it is first opened.
    private fun buildRows() {
        binding.themeHolder.removeAllViews()
        previews.clear()
        sectionWrappers.clear()
        sectionBodies.clear()
        sectionChevrons.clear()
        sectionsFilled.clear()
        body = binding.themeHolder

        val primaryColor = getProperPrimaryColor()

        // the link to the app's own settings: the page's first, thick pill; it is not a section
        addActionRow(getString(CR.string.settings), main = true, opens = true, indentLevel = -1, icon = RowIcon.SETTINGS) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // The keys are stored (order and fold state): never rename one.
        val built = ArrayList<UiSection>()
        built.add(UiSection("exim", getString(R.string.eim_section)) { addEximportSection(primaryColor) })
        built.add(UiSection("format", getString(R.string.format_section)) { addFormatSection(primaryColor) })
        built.add(UiSection("swipe", getString(R.string.swipe_section)) { addSwipeSection(primaryColor) })
        built.add(UiSection("tabs", getString(R.string.tabs_section)) { addTabsSection(primaryColor) })
        built.add(UiSection("bubbles", getString(R.string.bubbles_section)) { addBubblesSection(primaryColor) })
        built.add(UiSection("compose", getString(R.string.compose_section)) { addComposeSection(primaryColor) })
        built.add(UiSection("routes", getString(R.string.call_routes)) { addRoutesSection() })
        ThemeSection.entries.forEach { section ->
            built.add(UiSection("theme_" + section.name.lowercase(java.util.Locale.ROOT), getString(section.labelRes)) {
                val groups = ThemeGroup.entries.filter { it.section == section }
                val showSubgroups = groups.size > 1
                groups.forEach { group ->
                    if (showSubgroups) {
                        // subgroup header sits one level in; its rows another level in
                        addSubgroupHeader(getString(group.labelRes), primaryColor)
                        addGroupSlots(group, indentLevel = 2)
                    } else {
                        addGroupSlots(group, indentLevel = 1)
                    }
                }
            })
        }
        built.add(UiSection("maintenance", getString(R.string.maintenance_section)) { addMaintenanceSection() })
        sections = orderedSections(built)

        addSectionIndex(primaryColor)
        sections.forEachIndexed { index, section -> addSection(section, index == 0, primaryColor) }
    }

    /** The stored order applied to [built]: stored keys first, then sections a later build added, at their built place. */
    private fun orderedSections(built: List<UiSection>): List<UiSection> {
        val byKey = built.associateBy { it.key }
        val stored = config.uiSectionOrder.split(',').mapNotNull { byKey[it] }.distinct()
        return stored + built.filter { it !in stored }
    }

    // The jump index: a wrapping grid of small outlined chips, one per section. A tap unfolds the
    // section and scrolls to it; a long-press lifts the chip, the chips reorder live under the finger,
    // and the sections follow on drop.
    private fun addSectionIndex(accent: Int) {
        val density = resources.displayMetrics.density
        val margin = resources.getDimensionPixelSize(CR.dimen.activity_margin)
        val gap = (INDEX_GAP_DP * density).toInt()
        val group = ChipGroup(this).apply {
            chipSpacingHorizontal = gap
            chipSpacingVertical = gap
            setPaddingRelative(margin, gap, margin, gap)
        }
        sections.forEach { section ->
            group.addView(TextView(this).apply {
                text = section.title
                tag = section.key
                setTextColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, INDEX_TEXT_SP)
                val h = (INDEX_PAD_H_DP * density).toInt()
                val v = (INDEX_PAD_V_DP * density).toInt()
                setPadding(h, v, h, v)
                background = pillBackground(accent)
                setOnClickListener { jumpToSection(section.key) }
                setOnLongClickListener { chip ->
                    chip.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    chip.startDragAndDrop(null, View.DragShadowBuilder(chip), chip, 0)
                    chip.alpha = INDEX_DRAGGED_ALPHA
                    true
                }
            })
        }

        val orderBefore = sections.map { it.key }
        // The chip last moved past: the same chip is not swapped again until the finger has been over the
        // dragged one, or two chips of unequal width would swap back and forth under a still finger.
        var lastTarget: View? = null
        group.setOnDragListener { _, event ->
            val dragged = event.localState as? View
            if (dragged == null || dragged.parent !== group) return@setOnDragListener false
            when (event.action) {
                DragEvent.ACTION_DRAG_LOCATION -> {
                    val target = (0 until group.childCount).map { group.getChildAt(it) }
                        .firstOrNull { event.x >= it.left && event.x < it.right && event.y >= it.top && event.y < it.bottom }
                    if (target === dragged) {
                        lastTarget = null
                    } else if (target != null && target !== lastTarget) {
                        val to = group.indexOfChild(target)
                        group.removeView(dragged)
                        group.addView(dragged, to)
                        lastTarget = target
                    }
                }

                DragEvent.ACTION_DRAG_ENDED -> {
                    dragged.alpha = 1f
                    val order = (0 until group.childCount).map { group.getChildAt(it).tag as String }
                    if (order != orderBefore) {
                        config.uiSectionOrder = order.joinToString(",")
                        // not from inside the drag dispatch: the rebuild removes the view being dispatched to
                        group.post { buildRows() }
                    }
                }
            }
            true
        }
        binding.themeHolder.addView(group)
    }

    // A folding section: the 1 px darker-accent line above it (every section but the first; it stays
    // whether the section is folded or open), its bold heading underlined as wide as its text with ›
    // folded / ⌄ open at the right, and its rows in a body that folds away.
    private fun addSection(section: UiSection, first: Boolean, accent: Int) {
        val density = resources.displayMetrics.density
        val margin = resources.getDimensionPixelSize(CR.dimen.activity_margin)
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (!first) {
            wrapper.addView(
                View(this).apply { setBackgroundColor(MenuPills.divider(this@ThemeActivity)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    .apply { topMargin = (SECTION_GAP_DP * density).toInt() },
            )
        }

        val label = TextView(this).apply {
            text = section.title
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SECTION_TEXT_SP)
            setTypeface(typeface, Typeface.BOLD)
        }
        val labelColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // as wide as the column, which is as wide as the label's text
            addView(
                View(this@ThemeActivity).apply { setBackgroundColor(accent) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (SECTION_UNDERLINE_DP * density).toInt())
                    .apply { topMargin = (SECTION_UNDERLINE_GAP_DP * density).toInt() },
            )
        }
        val chevron = TextView(this).apply {
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SECTION_CHEVRON_SP)
            includeFontPadding = false
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val v = (SECTION_HEADER_PAD_DP * density).toInt()
            setPaddingRelative((SECTION_START_DP * density).toInt(), v, margin, v)
            setBackgroundResource(CR.drawable.ripple_background)
            addView(labelColumn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(this@ThemeActivity), LinearLayout.LayoutParams(0, 0, 1f))
            addView(chevron)
            setOnClickListener { setSectionOpen(section.key, section.key !in config.uiSectionsOpen) }
        }
        wrapper.addView(header)

        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(inner)
        binding.themeHolder.addView(wrapper)

        sectionWrappers[section.key] = wrapper
        sectionBodies[section.key] = inner
        sectionChevrons[section.key] = chevron
        showSectionState(section.key, section.key in config.uiSectionsOpen)
    }

    private fun setSectionOpen(key: String, open: Boolean) {
        val stored = config.uiSectionsOpen
        config.uiSectionsOpen = if (open) stored + key else stored - key
        showSectionState(key, open)
    }

    private fun showSectionState(key: String, open: Boolean) {
        val inner = sectionBodies[key] ?: return
        if (open && sectionsFilled.add(key)) {
            // rows are added to `body`; it points here only while this section's own rows are built
            body = inner
            sections.firstOrNull { it.key == key }?.fill?.invoke()
            body = binding.themeHolder
        }
        inner.beVisibleIf(open)
        sectionChevrons[key]?.text = if (open) "⌄" else "›"
    }

    /** A tap on an index chip: unfold the section and scroll to it. */
    private fun jumpToSection(key: String) {
        val wrapper = sectionWrappers[key] ?: return
        setSectionOpen(key, true)
        binding.themeHolder.post { binding.themeNestedScrollview.smoothScrollTo(0, wrapper.top) }
    }

    // How the call button of a conversation places its call when the contact has no route of its own,
    // and which 臨 account a plain 臨 route goes through.
    private fun addRoutesSection() {
        val phoneDecides = getString(R.string.route_phone_decides)
        addValueRow(
            R.string.route_default_label,
            routeLabel(config.defaultCallRoute, phoneDecides),
            icon = RowIcon.CALL,
        ) { valueView ->
            pickCallRoute(getString(R.string.route_default_label), config.defaultCallRoute, phoneDecides) { route ->
                config.defaultCallRoute = route
                valueView.text = routeLabel(route, phoneDecides)
            }
        }
    }

    // Housekeeping for the page itself.
    private fun addMaintenanceSection() {
        addActionRow(
            label = getString(R.string.reset_section_order),
            description = getString(R.string.reset_section_order_desc),
            icon = RowIcon.RESET,
        ) {
            config.uiSectionOrder = ""
            buildRows()
        }
    }

    private fun addGroupSlots(group: ThemeGroup, indentLevel: Int) {
        ThemeSlot.entries.filter { it.group == group }.forEach { slot ->
            if (slot.hasFont) addTextSlot(slot, indentLevel) else addColorSlot(slot, indentLevel)
        }
    }

    // Export/Import of every settable item, the first section of the page. The row beneath the
    // heading shows the newest export in the configured directory (queried on page open) and
    // opens the category panel.
    private fun addEximportSection(primaryColor: Int) {
        val (status, isWarning) = SettingsEximport.lastExportStatus(this)
        addActionRow(
            label = getString(R.string.eim_row_label),
            description = status,
            descriptionColor = if (isWarning) EXIM_WARN_COLOR else null,
            main = true,
            opens = true,
            icon = RowIcon.SHARE,
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

        // Three rows, in the order every sister app uses (contract v2).
        //
        // The master switch ships ON, so this app answers the 保存復元 batch out of the box — there is
        // nothing for 白い熊 to turn on and nothing to paste. It stays a switch rather than being
        // removed because it is the only way to close this app off, and a feature that can be turned
        // on but never off is one 白い熊 cannot retreat from.
        addSwitchRow(
            labelRes = R.string.enable_automation,
            checked = config.automationEnabled,
            indentLevel = 2,
            description = getString(R.string.enable_automation_desc),
            icon = RowIcon.AUTOMATION,
        ) {
            config.automationEnabled = it
        }

        // The token requirement ships OFF: a pasted secret cannot survive the wipe this surface now
        // exists to recover from, and a gate that only works once the phone is set up is no gate for
        // setting the phone up.
        addSwitchRow(
            labelRes = R.string.automation_require_token,
            checked = config.automationRequireToken,
            indentLevel = 2,
            description = getString(R.string.automation_require_token_desc),
            icon = RowIcon.SECRET,
        ) {
            config.automationRequireToken = it
            // the token row below appears and disappears with this switch
            buildRows()
        }

        // Hidden while the token is not being asked for: a 48-character secret sitting under an off
        // switch only invites 白い熊 to paste it somewhere it will do nothing.
        if (config.automationRequireToken) {
            addTokenRow()
        }

        // All-files access: needed so an automation broadcast can write to an arbitrary absolute path
        // (e.g. 白い熊's archive folder) outside Download/Documents. API 30+ only.
        if (isRPlus()) {
            val granted = Environment.isExternalStorageManager()
            val state = getString(if (granted) R.string.all_files_access_granted else R.string.all_files_access_needed)
            addActionRow(getString(R.string.all_files_access), state, opens = true, indentLevel = 2, icon = RowIcon.DIRECTORY) {
                openAllFilesAccessSettings()
            }
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
        row.themeTokenLabel.setRowIcon(RowIcon.SECRET)
        row.themeTokenValue.text = abbreviateToken(config.automationToken)
        row.themeTokenValue.setTextColor(getProperTextColor())
        row.themeTokenRegenerate.text = getString(R.string.automation_token_regenerate)
        row.themeTokenRegenerate.setTextColor(getProperPrimaryColor())
        // acts in place after its warning: a regular pill, no ›
        row.themeTokenRegenerate.background = pillBackground(getProperPrimaryColor())
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
                negative = CR.string.cancel,
            ) {
                row.themeTokenValue.text = abbreviateToken(config.regenerateAutomationToken())
                toast(R.string.automation_token_regenerated)
            }
        }
        indentRow(row.root, level = 2)
        body.addView(row.root)
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
        addValueRow(
            R.string.format_time_label,
            getString(messageTimeFormatOf(config.messageTimeFormat).labelRes),
            icon = RowIcon.TIME,
        ) { valueView ->
            val items = ArrayList(MessageTimeFormat.entries.map { RadioItem(it.ordinal, getString(it.labelRes)) })
            RadioGroupDialog(this, items, config.messageTimeFormat) {
                config.messageTimeFormat = it as Int
                valueView.text = getString(messageTimeFormatOf(config.messageTimeFormat).labelRes)
            }
        }
        addSwitchRow(R.string.use_imperial_date, config.useImperialDate, icon = RowIcon.TIME) { config.useImperialDate = it }
    }

    // Conversation-list swipe gestures: one action per direction, picked from SwipeAction.
    private fun addSwipeSection(primaryColor: Int) {
        addSwipeRow(R.string.swipe_right_label, { config.swipeRightAction }) { config.swipeRightAction = it }
        addSwipeRow(R.string.swipe_left_label, { config.swipeLeftAction }) { config.swipeLeftAction = it }
        addSwitchRow(
            labelRes = R.string.swipe_delete_confirm,
            checked = config.swipeDeleteConfirm,
            description = getString(R.string.swipe_delete_confirm_desc),
        ) { config.swipeDeleteConfirm = it }
    }

    // The conversation list's filing tabs: their text size, in percent of the stock size.
    private fun addTabsSection(primaryColor: Int) {
        addValueRow(R.string.tab_text_size_label, "${config.tabTextScale} %", icon = RowIcon.SIZE) { valueView ->
            val items = ArrayList(TAB_TEXT_SCALE_CHOICES.map { RadioItem(it, "$it %") })
            RadioGroupDialog(this, items, config.tabTextScale) {
                config.tabTextScale = it as Int
                valueView.text = "${config.tabTextScale} %"
            }
        }
    }

    // The thread screen's compose bar: how big the SIM selector next to the send button is drawn.
    private fun addComposeSection(primaryColor: Int) {
        addValueRow(
            R.string.sim_selector_size_label,
            getString(simSelectorSizeOf(config.simSelectorSize).labelRes),
            icon = RowIcon.SIZE,
        ) { valueView ->
            val items = ArrayList(SimSelectorSize.entries.map { RadioItem(it.ordinal, getString(it.labelRes)) })
            RadioGroupDialog(this, items, simSelectorSizeOf(config.simSelectorSize).ordinal) {
                config.simSelectorSize = it as Int
                valueView.text = getString(simSelectorSizeOf(config.simSelectorSize).labelRes)
            }
        }
    }

    // A conversation's bubbles: the sizes of the message text, of the time and SIM badge in the footer
    // and of the avatars, and the picture beside 白い熊's own messages.
    private fun addBubblesSection(@Suppress("UNUSED_PARAMETER") primaryColor: Int) {
        val received = ThemeSlot.THREAD_RECEIVED_TEXT.key
        addSlider(
            label = getString(R.string.bubble_text_size),
            value = config.getFontSize(received),
            max = MAX_FONT_SIZE_SP,
            icon = RowIcon.SIZE,
            valueLabel = ::sizeLabel,
        ) {
            config.setFontSize(received, it)
            config.setFontSize(ThemeSlot.THREAD_SENT_TEXT.key, it)
        }
        addSlider(
            label = getString(R.string.bubble_time_size),
            value = config.getFontSize(ThemeSlot.THREAD_DATE.key),
            max = MAX_FONT_SIZE_SP,
            icon = RowIcon.TIME,
            valueLabel = { if (it > 0) "$it sp" else getString(R.string.bubble_time_size_auto) },
        ) {
            config.setFontSize(ThemeSlot.THREAD_DATE.key, it)
        }
        addSlider(
            label = getString(R.string.bubble_avatar_size),
            value = config.threadAvatarSize - THREAD_AVATAR_MIN_DP,
            max = THREAD_AVATAR_MAX_DP - THREAD_AVATAR_MIN_DP,
            icon = RowIcon.PERSON,
            valueLabel = { "${it + THREAD_AVATAR_MIN_DP} dp" },
        ) {
            config.threadAvatarSize = it + THREAD_AVATAR_MIN_DP
        }

        val picked = ownAvatarPath().isNotEmpty()
        addActionRow(
            label = getString(R.string.own_avatar_choose),
            description = getString(if (picked) R.string.own_avatar_set else R.string.own_avatar_unset),
            main = true,
            opens = true,
            icon = RowIcon.PICTURE,
        ) {
            ownAvatarPicker.launch(arrayOf("image/*"))
        }
        if (picked) {
            addActionRow(label = getString(R.string.own_avatar_remove), icon = RowIcon.REMOVE, destructive = true) {
                clearOwnAvatar()
                buildRows()
            }
        }
    }

    @Suppress("EmptyFunctionBlock", "LongParameterList") // SeekBar's start/stop-tracking callbacks are no-ops
    private fun addSlider(
        label: String,
        value: Int,
        max: Int,
        icon: RowIcon,
        indentLevel: Int = 1,
        valueLabel: (Int) -> String,
        onChange: (Int) -> Unit,
    ) {
        val row = ItemThemeSliderBinding.inflate(layoutInflater, binding.themeHolder, false)
        row.themeSliderLabel.text = label
        row.themeSliderLabel.setTextColor(getProperTextColor())
        row.themeSliderLabel.setRowIcon(icon)
        row.themeSliderValue.setTextColor(getProperTextColor())
        row.themeSliderSeekbar.max = max
        row.themeSliderSeekbar.progress = value.coerceIn(0, max)
        row.themeSliderValue.text = valueLabel(row.themeSliderSeekbar.progress)
        row.themeSliderSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
                row.themeSliderValue.text = valueLabel(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        indentRow(row.root, indentLevel)
        body.addView(row.root)
    }

    private fun addSwipeRow(@StringRes labelRes: Int, current: () -> Int, store: (Int) -> Unit) {
        addValueRow(labelRes, getString(swipeActionOf(current()).labelRes), icon = RowIcon.MESSAGE) { valueView ->
            val items = ArrayList(SwipeAction.entries.map { RadioItem(it.ordinal, getString(it.labelRes)) })
            RadioGroupDialog(this, items, swipeActionOf(current()).ordinal) {
                store(it as Int)
                valueView.text = getString(swipeActionOf(current()).labelRes)
            }
        }
    }

    private fun addValueRow(
        @StringRes labelRes: Int,
        value: String,
        valueColor: Int? = null,
        indentLevel: Int = 1,
        icon: RowIcon = RowIcon.VALUE,
        onClick: (TextView) -> Unit,
    ) {
        val row = ItemThemeValueBinding.inflate(layoutInflater, binding.themeHolder, false)
        row.themeValueLabel.text = getString(labelRes)
        row.themeValueLabel.setTextColor(getProperTextColor())
        row.themeValueLabel.setRowIcon(icon)
        row.themeValue.setTextColor(valueColor ?: getProperTextColor())
        row.themeValue.text = value
        row.root.setOnClickListener { onClick(row.themeValue) }
        indentRow(row.root, indentLevel)
        body.addView(row.root)
    }

    /**
     * A row that does something when tapped, as an outlined pill hugging its label: [main] is the
     * thick, bold pill of its section's main action, [opens] adds the › of a row that opens a screen,
     * a panel or a picker. The [description] sits beneath the pill and is not part of the touch target.
     */
    @Suppress("LongParameterList")
    private fun addActionRow(
        label: String,
        description: String? = null,
        descriptionColor: Int? = null,
        main: Boolean = false,
        opens: Boolean = false,
        indentLevel: Int = 1,
        icon: RowIcon = RowIcon.SETTINGS,
        destructive: Boolean = false,
        onClick: () -> Unit,
    ) {
        // a destructive action lends its red to frame and label, still unfilled
        val accent = if (destructive) themeColor(ThemeSlot.MENU_WARNING) else getProperPrimaryColor()
        val row = ItemThemeActionBinding.inflate(layoutInflater, binding.themeHolder, false)
        row.themeActionLabel.text = label
        row.themeActionLabel.setTextColor(accent)
        row.themeActionLabel.setRowIcon(icon)
        row.themeActionLabel.setTypeface(row.themeActionLabel.typeface, if (main) Typeface.BOLD else Typeface.NORMAL)
        row.themeActionChevron.setTextColor(accent)
        row.themeActionChevron.setTypeface(null, if (main) Typeface.BOLD else Typeface.NORMAL)
        row.themeActionChevron.beGoneIf(!opens)
        row.themeActionPill.background = pillBackground(accent, main)
        row.themeActionPill.setOnClickListener { onClick() }
        row.themeActionDescription.beGoneIf(description == null)
        row.themeActionDescription.text = description
        row.themeActionDescription.setTextColor(descriptionColor ?: themeColor(ThemeSlot.SETTINGS_NOTE))
        indentRow(row.root, indentLevel, explained = description != null)
        body.addView(row.root)
    }

    private fun addSwitchRow(
        @StringRes labelRes: Int,
        checked: Boolean,
        indentLevel: Int = 1,
        description: String? = null,
        icon: RowIcon = RowIcon.TOGGLE,
        onToggle: (Boolean) -> Unit,
    ) {
        val row = ItemThemeSwitchBinding.inflate(layoutInflater, binding.themeHolder, false)
        val title = getString(labelRes)
        row.themeSwitchLabel.text = if (description == null) title else titleWithDescription(title, description)
        row.themeSwitchLabel.setTextColor(getProperTextColor())
        row.themeSwitchLabel.setRowIcon(icon)
        row.themeSwitch.minimumHeight = 0
        row.themeSwitch.minHeight = 0
        row.themeSwitch.isChecked = checked
        row.root.setOnClickListener {
            row.themeSwitch.toggle()
            onToggle(row.themeSwitch.isChecked)
        }
        indentRow(row.root, indentLevel, explained = description != null)
        body.addView(row.root)
    }

    // A row's explanation: the title in bold, then a smaller line in the dim-gold note colour below it.
    private fun titleWithDescription(title: String, description: String): CharSequence =
        SpannableStringBuilder(title).apply {
            append("\n")
            val start = length
            append(description)
            val dimmed = ForegroundColorSpan(themeColor(ThemeSlot.SETTINGS_NOTE))
            setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(DESCRIPTION_TEXT_SCALE), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(dimmed, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private fun addSubgroupHeader(label: String, primaryColor: Int) {
        val item = ItemThemeSubgroupBinding.inflate(layoutInflater, binding.themeHolder, false)
        item.themeSubgroupLabel.text = label
        item.themeSubgroupLabel.setTextColor(primaryColor)
        item.themeSubgroupUnderline.setBackgroundColor(primaryColor)
        body.addView(item.root)
    }

    private fun addColorSlot(slot: ThemeSlot, indentLevel: Int) {
        val row = ItemThemeColorBinding.inflate(layoutInflater, binding.themeHolder, false)
        row.themeColorLabel.text = getString(slot.labelRes)
        row.themeColorLabel.setTextColor(getProperTextColor())
        row.themeColorLabel.setRowIcon(RowIcon.COLOR)
        row.themeColorPreview.background.setTint(themeColor(slot))
        row.root.setOnClickListener { openColorPicker(slot) }
        indentRow(row.root, indentLevel)
        previews[slot] = row.themeColorPreview
        body.addView(row.root)
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
        b.themeTextLabel.setRowIcon(RowIcon.TEXT)
        b.themeTextFontTitle.setRowIcon(RowIcon.TEXT)
        b.themeTextWeightTitle.setRowIcon(RowIcon.WEIGHT)
        b.themeTextSizeTitle.setRowIcon(RowIcon.SIZE)

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
        body.addView(b.root)
    }

    private fun indentStepPx() = (ROW_INDENT_STEP_DP * resources.displayMetrics.density).toInt()

    // Place a row's content on the kxkb ladder (base + level*step: 72dp under a section, 90dp under
    // a subgroup). contentHasInset = true for the text block whose inner header already carries the
    // base label inset. Every row passes through here, so this is also where the vertical rhythm is
    // set: no minimum height and [ROW_PADDING_VERTICAL_DP] above and below, instead of the commons row
    // style's 20dp. An [explained] row gets the dim-gold rule down its left edge.
    private fun indentRow(view: View, level: Int, contentHasInset: Boolean = false, explained: Boolean = false) {
        val density = resources.displayMetrics.density
        val baseInset = resources.getDimensionPixelSize(CR.dimen.settings_label_start_margin)
        val base = (ROW_INDENT_BASE_DP * density).toInt()
        val start = base + level * indentStepPx() - (if (contentHasInset) baseInset else 0)
        val vertical = (ROW_PADDING_VERTICAL_DP * density).toInt()
        view.minimumHeight = 0
        if (contentHasInset && view is ViewGroup) {
            // a text element is a column of rows: each of them is tightened, the column itself is not padded
            view.setPaddingRelative(start, 0, view.paddingEnd, 0)
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                child.minimumHeight = 0
                child.setPaddingRelative(child.paddingStart, vertical, child.paddingEnd, vertical)
            }
        } else {
            val keep = view.id == R.id.theme_action_holder // a pill row keeps its own 3dp around the pill
            view.setPaddingRelative(start, if (keep) view.paddingTop else vertical, view.paddingEnd, if (keep) view.paddingBottom else vertical)
        }
        if (explained) {
            view.foreground = LeftRule(
                themeColor(ThemeSlot.SETTINGS_NOTE), start - RULE_GAP_DP * density, RULE_WIDTH_DP * density
            )
        }
    }

    // The row's icon, before its label and in the colour of what the row does.
    private fun TextView.setRowIcon(icon: RowIcon) {
        val density = resources.displayMetrics.density
        val size = (ROW_ICON_DP * density).toInt()
        val drawable = resources.getColoredDrawableWithColor(icon.res, themeColor(icon.role))
        drawable.setBounds(0, 0, size, size)
        setCompoundDrawablesRelative(drawable, null, null, null)
        compoundDrawablePadding = (ROW_ICON_GAP_DP * density).toInt()
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
