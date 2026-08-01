package au.com.shiftyjelly.pocketcasts.podcasts.view.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import au.com.shiftyjelly.pocketcasts.compose.AppTheme
import au.com.shiftyjelly.pocketcasts.compose.components.FormFieldDialog
import au.com.shiftyjelly.pocketcasts.compose.components.SettingInfoRow
import au.com.shiftyjelly.pocketcasts.compose.components.SettingRow
import au.com.shiftyjelly.pocketcasts.compose.components.SettingSection
import au.com.shiftyjelly.pocketcasts.compose.preview.ThemePreviewParameterProvider
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme.ThemeType
import au.com.shiftyjelly.pocketcasts.images.R as IR
import au.com.shiftyjelly.pocketcasts.localization.R as LR

@Composable
internal fun PodcastSettingsArchiveTitleFiltersPage(
    podcast: Podcast,
    onAddTitleFilter: (String) -> Unit,
    onEditTitleFilter: (Int, String) -> Unit,
    onRemoveTitleFilter: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editedFilter by remember { mutableStateOf<EditedTitleFilter?>(null) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .fillMaxSize(),
    ) {
        SettingSection(
            showDivider = false,
        ) {
            podcast.autoArchiveTitleFilters.forEachIndexed { index, filter ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingRow(
                        primaryText = filter,
                        horizontalPadding = 0.dp,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                role = Role.Button,
                                onClick = { editedFilter = EditedTitleFilter(index, filter) },
                            ),
                    )
                    IconButton(
                        onClick = { onRemoveTitleFilter(index) },
                    ) {
                        Icon(
                            painter = painterResource(IR.drawable.ic_delete),
                            contentDescription = stringResource(LR.string.podcast_settings_auto_archive_title_filters_remove),
                            tint = MaterialTheme.theme.colors.primaryIcon02,
                        )
                    }
                }
            }
            SettingRow(
                primaryText = stringResource(LR.string.podcast_settings_auto_archive_title_filters_add),
                icon = painterResource(IR.drawable.ic_add_black_24dp),
                iconTint = MaterialTheme.theme.colors.primaryIcon01,
                modifier = Modifier.clickable(
                    role = Role.Button,
                    onClick = { editedFilter = EditedTitleFilter(index = null, value = "") },
                ),
            )
            SettingInfoRow(
                text = stringResource(LR.string.podcast_settings_auto_archive_title_filters_summary),
            )
        }
    }

    editedFilter?.let { edited ->
        FormFieldDialog(
            title = stringResource(LR.string.podcast_settings_auto_archive_title_filters_dialog_title),
            placeholder = stringResource(LR.string.podcast_settings_auto_archive_title_filters_dialog_placeholder),
            initialValue = edited.value,
            keyboardType = KeyboardType.Text,
            onConfirm = { value ->
                if (edited.index == null) {
                    onAddTitleFilter(value)
                } else {
                    onEditTitleFilter(edited.index, value)
                }
            },
            onDismissRequest = { editedFilter = null },
            isSaveEnabled = { value -> value.isNotBlank() },
        )
    }
}

private data class EditedTitleFilter(
    val index: Int?,
    val value: String,
)

@Preview
@Composable
private fun PodcastSettingsArchiveTitleFiltersPagePreview(
    @PreviewParameter(ThemePreviewParameterProvider::class) themeType: ThemeType,
) {
    AppTheme(themeType) {
        PodcastSettingsArchiveTitleFiltersPage(
            podcast = Podcast(
                uuid = "uuid",
                autoArchiveTitleFilters = listOf("It Could Happen Here Weekly"),
            ),
            onAddTitleFilter = {},
            onEditTitleFilter = { _, _ -> },
            onRemoveTitleFilter = {},
            modifier = Modifier.background(MaterialTheme.theme.colors.primaryUi02),
        )
    }
}
