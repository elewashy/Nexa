package com.elewashy.nexa.feature.settings.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.elewashy.nexa.BuildConfig
import com.elewashy.nexa.R
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.common.AppTopBar
import com.elewashy.nexa.ui.components.common.FramedAppIcon
import com.elewashy.nexa.ui.components.settings.ExpressiveListIcon
import com.elewashy.nexa.ui.components.settings.ListSection
import com.elewashy.nexa.ui.components.settings.SettingsListItem
import com.elewashy.nexa.ui.icons.Description
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.brands.Github
import compose.icons.fontawesomeicons.brands.Telegram
import compose.icons.fontawesomeicons.solid.Bug
import compose.icons.fontawesomeicons.solid.ScaleBalanced
import compose.icons.fontawesomeicons.solid.User
import compose.icons.fontawesomeicons.solid.Users

private const val DEVELOPER_NAME = "Mohamed Al-Aweshi"
private const val DEVELOPER_AVATAR_URL = "https://github.com/elewashy.png"
private const val DEVELOPER_GITHUB_URL = "https://github.com/elewashy"
private const val TELEGRAM_CHANNEL_URL = "https://t.me/Elewashy"
private const val REPO_URL = "https://github.com/elewashy/Nexa"
private const val ISSUES_URL = "$REPO_URL/issues/new/choose"
private const val LICENSE_URL = "$REPO_URL/blob/main/LICENSE"

private val DeveloperAvatarSize = 110.dp
private val CardCornerRadius = 32.dp

/**
 * About screen. All content is static build/app metadata, so the screen is a
 * stateless composable with no ViewModel; navigation is hoisted to the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBackClick: () -> Unit,
    onContributorsClick: () -> Unit,
    onLicensesClick: () -> Unit,
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.about),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = adaptiveInfo.horizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))

            AppHeaderCard(
                appVersion = BuildConfig.VERSION_NAME,
                isDebugBuild = BuildConfig.DEBUG,
                modifier = Modifier
                    .widthIn(max = adaptiveInfo.contentMaxWidth)
                    .fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            DeveloperHeroCard(
                developerName = DEVELOPER_NAME,
                developerAvatarUrl = DEVELOPER_AVATAR_URL,
                developerGithubUrl = DEVELOPER_GITHUB_URL,
                telegramChannelUrl = TELEGRAM_CHANNEL_URL,
                modifier = Modifier
                    .widthIn(max = adaptiveInfo.contentMaxWidth)
                    .fillMaxWidth(),
            )

            Spacer(Modifier.height(32.dp))

            ProjectSection(
                issuesUrl = ISSUES_URL,
                onContributorsClick = onContributorsClick,
                onLicensesClick = onLicensesClick,
                modifier = Modifier.widthIn(max = adaptiveInfo.listMaxWidth),
            )

            Spacer(Modifier.height(16.dp))

            CommunitySection(
                telegramChannelUrl = TELEGRAM_CHANNEL_URL,
                repoUrl = REPO_URL,
                licenseUrl = LICENSE_URL,
                modifier = Modifier.widthIn(max = adaptiveInfo.listMaxWidth),
            )

            Spacer(Modifier.height(48.dp))

            Text(
                text = stringResource(R.string.stands_with_palestine),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun AppHeaderCard(
    appVersion: String,
    isDebugBuild: Boolean,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FramedAppIcon(size = 64.dp)

                Spacer(Modifier.width(20.dp))

                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = (-0.5).sp,
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Badge(
                            text = appVersion,
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                        if (isDebugBuild) {
                            Badge(
                                text = "DEBUG",
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.nexa_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Badge(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeveloperHeroCard(
    developerName: String,
    developerAvatarUrl: String,
    developerGithubUrl: String,
    telegramChannelUrl: String,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    // toShape() is @Composable in Material3 Expressive — call it inline.
    val avatarShape = MaterialShapes.Cookie9Sided.toShape()

    ElevatedCard(
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                DeveloperAvatar(
                    avatarUrl = developerAvatarUrl,
                    contentDescription = developerName,
                    shape = avatarShape,
                )

                Column(verticalArrangement = Arrangement.Center) {
                    Text(
                        text = developerName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = (-0.5).sp,
                    )
                    Text(
                        text = stringResource(R.string.lead_developer),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = { uriHandler.openUri(developerGithubUrl) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(
                        imageVector = FontAwesomeIcons.Brands.Github,
                        contentDescription = stringResource(R.string.github),
                    )
                }
                FilledTonalButton(
                    onClick = { uriHandler.openUri(telegramChannelUrl) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(
                        imageVector = FontAwesomeIcons.Brands.Telegram,
                        contentDescription = stringResource(R.string.telegram),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeveloperAvatar(
    avatarUrl: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
) {
    val fallback = rememberVectorPainter(FontAwesomeIcons.Solid.User)
    Surface(
        modifier = modifier.size(DeveloperAvatarSize),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 4.dp,
    ) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            placeholder = fallback,
            fallback = fallback,
            error = fallback,
        )
    }
}

@Composable
private fun ProjectSection(
    issuesUrl: String,
    onContributorsClick: () -> Unit,
    onLicensesClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    ListSection(
        title = stringResource(R.string.project),
        modifier = modifier,
    ) {
        SettingsListItem(
            modifier = Modifier.fillMaxWidth(),
            headlineContent = stringResource(R.string.report_issue),
            supportingContent = stringResource(R.string.report_issue_description),
            leadingContent = {
                ExpressiveListIcon(icon = FontAwesomeIcons.Solid.Bug)
            },
            onClick = { uriHandler.openUri(issuesUrl) },
        )
        SettingsListItem(
            modifier = Modifier.fillMaxWidth(),
            headlineContent = stringResource(R.string.contributors),
            supportingContent = stringResource(R.string.contributors_description),
            leadingContent = {
                ExpressiveListIcon(icon = FontAwesomeIcons.Solid.Users)
            },
            onClick = onContributorsClick,
        )
        SettingsListItem(
            modifier = Modifier.fillMaxWidth(),
            headlineContent = stringResource(R.string.opensource_licenses),
            supportingContent = stringResource(R.string.opensource_licenses_description),
            leadingContent = {
                ExpressiveListIcon(icon = FontAwesomeIcons.Solid.ScaleBalanced)
            },
            onClick = onLicensesClick,
        )
    }
}

@Composable
private fun CommunitySection(
    telegramChannelUrl: String,
    repoUrl: String,
    licenseUrl: String,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    ListSection(
        title = stringResource(R.string.community_and_info),
        modifier = modifier,
    ) {
        SettingsListItem(
            modifier = Modifier.fillMaxWidth(),
            headlineContent = stringResource(R.string.telegram_channel),
            leadingContent = {
                ExpressiveListIcon(icon = FontAwesomeIcons.Brands.Telegram)
            },
            onClick = { uriHandler.openUri(telegramChannelUrl) },
        )
        SettingsListItem(
            modifier = Modifier.fillMaxWidth(),
            headlineContent = stringResource(R.string.view_source_code),
            supportingContent = stringResource(R.string.view_source_code_description),
            leadingContent = {
                ExpressiveListIcon(icon = FontAwesomeIcons.Brands.Github)
            },
            onClick = { uriHandler.openUri(repoUrl) },
        )
        SettingsListItem(
            modifier = Modifier.fillMaxWidth(),
            headlineContent = stringResource(R.string.app_license),
            supportingContent = stringResource(R.string.app_license_description),
            leadingContent = {
                ExpressiveListIcon(icon = Description)
            },
            onClick = { uriHandler.openUri(licenseUrl) },
        )
    }
}

@PreviewLightDark
@Composable
private fun AboutScreenPreview() {
    MaterialTheme {
        AboutScreen(
            onBackClick = {},
            onContributorsClick = {},
            onLicensesClick = {},
        )
    }
}
