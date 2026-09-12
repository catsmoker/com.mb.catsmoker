package com.catsmoker.app.features.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.theme.CatsmokerTheme

@Composable
fun AboutRoute(onBack: () -> Unit) {
    AboutScreen(onBack = onBack)
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    ScreenScaffold(title = stringResource(R.string.about_header_title), subtitle = stringResource(R.string.core_about_subtitle), onBack = onBack) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            // Header
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.padding(12.dp))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = stringResource(R.string.app_name), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(text = "v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Social Links Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val githubUrl = stringResource(R.string.url_github)
                    val webUrl = stringResource(R.string.url_webpage)
                    val discordUrl = stringResource(R.string.url_discord)
                    val telegramUrl = stringResource(R.string.url_telegram)
                    val paypalUrl = stringResource(R.string.url_paypal)

                    SocialIcon(
                        painter = painterResource(R.drawable.ic_github),
                        onClick = { uriHandler.openUri(githubUrl) }
                    )
                    SocialIcon(
                        imageVector = Icons.Default.Language,
                        onClick = { uriHandler.openUri(webUrl) }
                    )
                    SocialIcon(
                        painter = painterResource(R.drawable.ic_discord),
                        onClick = { uriHandler.openUri(discordUrl) }
                    )
                    SocialIcon(
                        painter = painterResource(R.drawable.ic_telegram),
                        onClick = { uriHandler.openUri(telegramUrl) }
                    )
                    SocialIcon(
                        painter = painterResource(R.drawable.ic_paypal),
                        onClick = { uriHandler.openUri(paypalUrl) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.about_header_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.core_about_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SocialIcon(
    onClick: () -> Unit,
    painter: androidx.compose.ui.graphics.painter.Painter? = null,
    imageVector: ImageVector? = null
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        if (painter != null) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        } else if (imageVector != null) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun AboutPreview() {
    CatsmokerTheme {
        AboutScreen(onBack = {})
    }
}
