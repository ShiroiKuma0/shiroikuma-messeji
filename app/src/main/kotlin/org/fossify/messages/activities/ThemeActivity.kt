package org.fossify.messages.activities

import android.os.Bundle
import android.widget.ImageView
import org.fossify.commons.dialogs.ColorPickerDialog
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.databinding.ActivityThemeBinding
import org.fossify.messages.databinding.ItemThemeColorBinding
import org.fossify.messages.databinding.ItemThemeSectionBinding
import org.fossify.messages.databinding.ItemThemeSubgroupBinding
import org.fossify.messages.extensions.ThemeGroup
import org.fossify.messages.extensions.ThemeSection
import org.fossify.messages.extensions.ThemeSlot
import org.fossify.messages.extensions.resetThemeColor
import org.fossify.messages.extensions.setThemeColor
import org.fossify.messages.extensions.themeColor

class ThemeActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityThemeBinding::inflate)
    private val previews = HashMap<ThemeSlot, ImageView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.themeNestedScrollview))
        setupMaterialScrollListener(binding.themeNestedScrollview, binding.themeAppbar)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.themeAppbar, NavigationIcon.Arrow)
        buildRows()
    }

    private fun buildRows() {
        binding.themeHolder.removeAllViews()
        previews.clear()

        val primaryColor = getProperPrimaryColor()

        ThemeSection.entries.forEach { section ->
            addSectionHeader(getString(section.labelRes), primaryColor)
            val groups = ThemeGroup.entries.filter { it.section == section }
            val showSubgroups = groups.size > 1
            groups.forEach { group ->
                if (showSubgroups) {
                    addSubgroupHeader(getString(group.labelRes), primaryColor)
                }
                addColorRows(group, indent = showSubgroups)
            }
        }
    }

    private fun addSectionHeader(label: String, primaryColor: Int) {
        val item = ItemThemeSectionBinding.inflate(layoutInflater, binding.themeHolder, false)
        item.themeSectionLabel.text = label
        item.themeSectionLabel.setTextColor(primaryColor)
        item.themeSectionDivider.setBackgroundColor(primaryColor)
        binding.themeHolder.addView(item.root)
    }

    private fun addSubgroupHeader(label: String, primaryColor: Int) {
        val item = ItemThemeSubgroupBinding.inflate(layoutInflater, binding.themeHolder, false)
        item.themeSubgroupLabel.text = label
        item.themeSubgroupLabel.setTextColor(primaryColor)
        item.themeSubgroupUnderline.setBackgroundColor(primaryColor)
        binding.themeHolder.addView(item.root)
    }

    private fun addColorRows(group: ThemeGroup, indent: Boolean) {
        val textColor = getProperTextColor()
        val indentPx = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
        ThemeSlot.entries.filter { it.group == group }.forEach { slot ->
            val row = ItemThemeColorBinding.inflate(layoutInflater, binding.themeHolder, false)
            row.themeColorLabel.text = getString(slot.labelRes)
            row.themeColorLabel.setTextColor(textColor)
            row.themeColorPreview.background.setTint(themeColor(slot))
            row.root.setOnClickListener { openPicker(slot) }
            if (indent) {
                // indent rows that sit under a subgroup header to reinforce the hierarchy
                row.root.setPaddingRelative(
                    row.root.paddingStart + indentPx,
                    row.root.paddingTop,
                    row.root.paddingEnd,
                    row.root.paddingBottom
                )
            }
            previews[slot] = row.themeColorPreview
            binding.themeHolder.addView(row.root)
        }
    }

    private fun openPicker(slot: ThemeSlot) {
        ColorPickerDialog(this, themeColor(slot), addDefaultColorButton = true) { wasPositive, color ->
            if (wasPositive) {
                setThemeColor(slot, color)
            } else {
                resetThemeColor(slot)
            }

            if (slot.isFoundation) {
                // foundation cascades into the chrome + every inheriting preview
                recreate()
            } else {
                previews[slot]?.background?.setTint(themeColor(slot))
            }
        }
    }
}
