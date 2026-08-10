package com.milen.grounpringtonesetter.ui.home

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.milen.grounpringtonesetter.R

internal enum class HomeThemeOption {
    CLASSIC,
    DARK_HIGH_CONTRAST,
    LIGHT_HIGH_CONTRAST,
}

internal enum class GroupSortOption {
    CURRENT_ORDER,
    ALPHABETICAL_ASC,
    ALPHABETICAL_DESC,
}

internal data class HomeDisplayPreferences(
    val themeOption: HomeThemeOption = HomeThemeOption.CLASSIC,
    val groupSortOption: GroupSortOption = GroupSortOption.ALPHABETICAL_ASC,
    val showReadOnlyGroups: Boolean = false,
)

internal data class HomeThemeAppearance(
    @param:ColorRes val screenBackgroundColorRes: Int,
    @param:ColorRes val surfaceBackgroundColorRes: Int,
    @param:ColorRes val textColorRes: Int,
    @param:ColorRes val iconTintColorRes: Int,
    @param:ColorRes val inactiveIconTintColorRes: Int,
    @param:ColorRes val actionButtonBackgroundColorRes: Int,
    @param:ColorRes val actionButtonTextColorRes: Int,
    @param:ColorRes val counterBackgroundColorRes: Int,
    @param:ColorRes val counterTextColorRes: Int,
    @param:ColorRes val searchStrokeColorRes: Int,
    @param:ColorRes val searchHintColorRes: Int,
    @param:ColorRes val dialogBackgroundColorRes: Int,
    @param:ColorRes val dialogBorderColorRes: Int,
    @param:ColorRes val dialogActionTextColorRes: Int,
    @param:DrawableRes val groupCardBackgroundRes: Int,
)

internal fun HomeThemeOption.toAppearance(): HomeThemeAppearance = when (this) {
    HomeThemeOption.CLASSIC ->
        HomeThemeAppearance(
            screenBackgroundColorRes = R.color.black,
            surfaceBackgroundColorRes = R.color.home_dark_card_fill,
            textColorRes = R.color.white,
            iconTintColorRes = R.color.white,
            inactiveIconTintColorRes = R.color.home_classic_inactive_icon,
            actionButtonBackgroundColorRes = R.color.purple_500,
            actionButtonTextColorRes = R.color.white,
            counterBackgroundColorRes = R.color.purple_500,
            counterTextColorRes = R.color.white,
            searchStrokeColorRes = R.color.white,
            searchHintColorRes = R.color.white,
            dialogBackgroundColorRes = R.color.home_dark_card_fill,
            dialogBorderColorRes = R.color.white,
            dialogActionTextColorRes = R.color.purple_200,
            groupCardBackgroundRes = R.drawable.rounded_border_textcolored_border
        )

    HomeThemeOption.DARK_HIGH_CONTRAST ->
        HomeThemeAppearance(
            screenBackgroundColorRes = R.color.theme_dark_background,
            surfaceBackgroundColorRes = R.color.theme_dark_surface,
            textColorRes = R.color.theme_dark_text,
            iconTintColorRes = R.color.theme_dark_text,
            inactiveIconTintColorRes = R.color.theme_dark_inactive_icon,
            actionButtonBackgroundColorRes = R.color.theme_dark_accent,
            actionButtonTextColorRes = R.color.theme_dark_accent_text,
            counterBackgroundColorRes = R.color.theme_dark_accent,
            counterTextColorRes = R.color.theme_dark_accent_text,
            searchStrokeColorRes = R.color.theme_dark_border,
            searchHintColorRes = R.color.theme_dark_hint,
            dialogBackgroundColorRes = R.color.theme_dark_dialog_surface,
            dialogBorderColorRes = R.color.theme_dark_border,
            dialogActionTextColorRes = R.color.theme_dark_accent,
            groupCardBackgroundRes = R.drawable.rounded_border_high_contrast_dark
        )

    HomeThemeOption.LIGHT_HIGH_CONTRAST ->
        HomeThemeAppearance(
            screenBackgroundColorRes = R.color.theme_light_background,
            surfaceBackgroundColorRes = R.color.theme_light_surface,
            textColorRes = R.color.theme_light_text,
            iconTintColorRes = R.color.theme_light_text,
            inactiveIconTintColorRes = R.color.theme_light_inactive_icon,
            actionButtonBackgroundColorRes = R.color.theme_light_accent,
            actionButtonTextColorRes = R.color.theme_light_accent_text,
            counterBackgroundColorRes = R.color.theme_light_accent,
            counterTextColorRes = R.color.theme_light_accent_text,
            searchStrokeColorRes = R.color.theme_light_border,
            searchHintColorRes = R.color.theme_light_hint,
            dialogBackgroundColorRes = R.color.theme_light_dialog_surface,
            dialogBorderColorRes = R.color.theme_light_border,
            dialogActionTextColorRes = R.color.theme_light_accent,
            groupCardBackgroundRes = R.drawable.rounded_border_high_contrast_light
        )
}
