package com.gameocr.app.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gameocr.app.R

internal const val THIRD_PARTY_NOTICES_ASSET = "third_party_notices.txt"
internal const val APACHE_LICENSE_ASSET = "apache_license_2_0.txt"

internal data class LegalNoticeSection(
    @StringRes val titleRes: Int,
    val assetName: String,
)

internal val LEGAL_NOTICE_SECTIONS = listOf(
    LegalNoticeSection(R.string.settings_about_app_license_title, APACHE_LICENSE_ASSET),
    LegalNoticeSection(R.string.settings_about_third_party_notices_title, THIRD_PARTY_NOTICES_ASSET),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalNoticesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val unavailable = stringResource(R.string.settings_about_licenses_unavailable)
    val sections = remember(context, unavailable) {
        LEGAL_NOTICE_SECTIONS.map { section ->
            section to runCatching {
                context.assets.open(section.assetName).bufferedReader().use { it.readText() }
            }.getOrDefault(unavailable)
        }
    }

    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_about_licenses_page_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_about_third_party_notice_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sections.forEach { (section, text) ->
                Text(
                    text = stringResource(section.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SelectionContainer {
                    Text(
                        text = text,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
