package com.javapro.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.javapro.utils.CoinManager
import com.javapro.utils.PremiumManager
import kotlinx.coroutines.delay

@Composable
fun CoinStoreSettingItem(navController: NavController, context: Context = LocalContext.current) {
    var coinBalance by remember { mutableIntStateOf(CoinManager.getCachedBalance(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            coinBalance = CoinManager.getCachedBalance(context)
            delay(5_000L)
        }
    }

    if (remember { PremiumManager.isPremium(context) }) return

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { navController.navigate("coin_store") },
        headlineContent = {
            Text(
                text  = "Toko Koin",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Text(
                text  = "Tonton iklan, kumpulkan koin, tukar premium",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Box(
                modifier         = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = Icons.Filled.MonetizationOn,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.primary,
                    modifier           = Modifier.size(22.dp)
                )
            }
        },
        trailingContent = {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (coinBalance > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor   = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text       = "$coinBalance",
                            style      = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
    )
}
