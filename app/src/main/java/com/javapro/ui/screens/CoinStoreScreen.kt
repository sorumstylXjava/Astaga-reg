package com.javapro.ui.screens

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService
import androidx.navigation.NavController
import com.javapro.utils.CoinManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class StoreUiState {
    IDLE, REDEEMING, SUCCESS, ERROR
}

private data class CoinPackage(
    val id          : String,
    val label       : String,
    val duration    : String,
    val cost        : Int,
    val badge       : String?,
    val gradient    : List<Color>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinStoreScreen(
    navController : NavController,
    lang          : String = "en"
) {
    val context     = LocalContext.current
    val scope       = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    val packages = listOf(
        CoinPackage(
            id       = CoinManager.PACKAGE_WEEKLY_ID,
            label    = "Weekly",
            duration = "7 Hari Premium",
            cost     = CoinManager.PACKAGE_WEEKLY_COST,
            badge    = null,
            gradient = listOf(Color(0xFF6C63FF), Color(0xFF8B80FF))
        ),
        CoinPackage(
            id       = CoinManager.PACKAGE_MONTHLY_ID,
            label    = "Monthly",
            duration = "30 Hari Premium",
            cost     = CoinManager.PACKAGE_MONTHLY_COST,
            badge    = "POPULER",
            gradient = listOf(Color(0xFF1DB954), Color(0xFF1ED760))
        ),
        CoinPackage(
            id       = CoinManager.PACKAGE_YEARLY_ID,
            label    = "Yearly",
            duration = "365 Hari Premium",
            cost     = CoinManager.PACKAGE_YEARLY_COST,
            badge    = "TERBAIK",
            gradient = listOf(Color(0xFFFF6B35), Color(0xFFFF8C42))
        )
    )

    fun hasActiveNetwork(): Boolean {
        val cm   = context.getSystemService<ConnectivityManager>() ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    var isNetworkAvailable by remember { mutableStateOf(hasActiveNetwork()) }

    DisposableEffect(Unit) {
        val cm = context.getSystemService<ConnectivityManager>()
        if (cm == null) {
            onDispose {}
        } else {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { mainHandler.post { isNetworkAvailable = hasActiveNetwork() } }
                override fun onLost(network: Network) { mainHandler.post { isNetworkAvailable = hasActiveNetwork() } }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { mainHandler.post { isNetworkAvailable = hasActiveNetwork() } }
            }
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            cm.registerNetworkCallback(request, callback)
            onDispose { cm.unregisterNetworkCallback(callback) }
        }
    }

    var uiState          by remember { mutableStateOf(StoreUiState.IDLE) }
    var coinBalance      by remember { mutableIntStateOf(CoinManager.getCachedBalance(context)) }
    var isLoadingBalance by remember { mutableStateOf(false) }
    var selectedPackage  by remember { mutableStateOf<CoinPackage?>(null) }
    var errorMsg         by remember { mutableStateOf("") }
    var successPackageId by remember { mutableStateOf("") }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var packageToConfirm  by remember { mutableStateOf<CoinPackage?>(null) }

    LaunchedEffect(Unit) {
        isLoadingBalance = true
        coinBalance      = CoinManager.fetchBalance(context, forceRefresh = true)
        isLoadingBalance = false
    }

    if (showConfirmDialog && packageToConfirm != null) {
        val pkg = packageToConfirm!!
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false; packageToConfirm = null },
            shape            = RoundedCornerShape(24.dp),
            containerColor   = colorScheme.surface,
            icon = {
                Box(
                    modifier         = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MonetizationOn, null, tint = colorScheme.primary, modifier = Modifier.size(26.dp))
                }
            },
            title = {
                Text(text = "Tukar Koin?", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colorScheme.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text  = "Kamu akan menukar ${pkg.cost} koin untuk ${pkg.duration}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text  = "Saldo setelah: ${coinBalance - pkg.cost} koin",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        val confirmedPkg = packageToConfirm!!
                        packageToConfirm = null
                        selectedPackage  = confirmedPkg
                        uiState          = StoreUiState.REDEEMING
                        scope.launch {
                            when (val res = CoinManager.redeemPackage(context, confirmedPkg.id)) {
                                is CoinManager.RedeemResult.Success -> {
                                    coinBalance     = res.remainingBalance
                                    successPackageId = res.packageId
                                    uiState         = StoreUiState.SUCCESS
                                }
                                is CoinManager.RedeemResult.InsufficientCoins -> {
                                    coinBalance = res.current
                                    errorMsg    = "Koin tidak cukup. Kamu butuh ${res.required} koin, saldo kamu ${res.current} koin."
                                    uiState     = StoreUiState.ERROR
                                }
                                CoinManager.RedeemResult.AlreadyPremium -> {
                                    errorMsg = "Kamu sudah memiliki premium aktif."
                                    uiState  = StoreUiState.ERROR
                                }
                                CoinManager.RedeemResult.TamperDetected -> {
                                    errorMsg = "Terjadi kesalahan keamanan. Coba lagi."
                                    uiState  = StoreUiState.ERROR
                                }
                                CoinManager.RedeemResult.NetworkError -> {
                                    errorMsg = "Koneksi gagal. Periksa internet kamu."
                                    uiState  = StoreUiState.ERROR
                                }
                                CoinManager.RedeemResult.ServerError -> {
                                    errorMsg = "Server error. Coba beberapa saat lagi."
                                    uiState  = StoreUiState.ERROR
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                    shape  = RoundedCornerShape(50.dp)
                ) {
                    Text(text = "Tukar Sekarang", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showConfirmDialog = false; packageToConfirm = null },
                    shape   = RoundedCornerShape(50.dp)
                ) {
                    Text(text = "Batal", color = colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    Scaffold(
        containerColor      = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text  = "Toko Koin",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(24.dp),
                colors   = CardDefaults.cardColors(containerColor = colorScheme.primaryContainer)
            ) {
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier         = Modifier.size(52.dp).clip(CircleShape).background(colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MonetizationOn, null, tint = colorScheme.primary, modifier = Modifier.size(30.dp))
                    }
                    Column {
                        Text(text = "Saldo Koin", style = MaterialTheme.typography.labelMedium, color = colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        if (isLoadingBalance) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = colorScheme.primary)
                        } else {
                            Text(
                                text  = "$coinBalance Koin",
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color = colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { navController.navigate("coin_reward") }) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(text = "Tambah", fontWeight = FontWeight.Bold, color = colorScheme.primary)
                        }
                        TextButton(
                            onClick = {
                                if (!isLoadingBalance) {
                                    scope.launch {
                                        isLoadingBalance = true
                                        CoinManager.invalidateCache(context)
                                        coinBalance      = CoinManager.fetchBalance(context, forceRefresh = true)
                                        isLoadingBalance = false
                                    }
                                }
                            },
                            enabled = !isLoadingBalance
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(13.dp), tint = colorScheme.onPrimaryContainer.copy(alpha = 0.5f))
                            Spacer(Modifier.width(4.dp))
                            Text(text = "Refresh", fontSize = 11.sp, color = colorScheme.onPrimaryContainer.copy(alpha = 0.5f))
                        }
                    }
                }
            }


            AnimatedContent(
                targetState = uiState,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                label = "storeContent"
            ) { state ->
                when (state) {
                    StoreUiState.IDLE, StoreUiState.ERROR -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (state == StoreUiState.ERROR) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape    = RoundedCornerShape(16.dp),
                                    colors   = CardDefaults.cardColors(containerColor = colorScheme.errorContainer)
                                ) {
                                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Icon(Icons.Default.ErrorOutline, null, tint = colorScheme.error, modifier = Modifier.size(20.dp))
                                        Text(text = errorMsg, style = MaterialTheme.typography.bodySmall, color = colorScheme.onErrorContainer)
                                    }
                                }
                            }

                            Text(
                                text       = "Pilih Paket Premium",
                                style      = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color      = colorScheme.onBackground
                            )

                            packages.forEach { pkg ->
                                val canAfford   = coinBalance >= pkg.cost
                                val isSelected  = selectedPackage?.id == pkg.id

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (isSelected) Modifier.border(2.dp, colorScheme.primary, RoundedCornerShape(20.dp))
                                            else Modifier
                                        ),
                                    shape    = RoundedCornerShape(20.dp),
                                    colors   = CardDefaults.cardColors(
                                        containerColor = if (canAfford) colorScheme.surfaceVariant
                                                         else colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Row(
                                        modifier              = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                                        verticalAlignment     = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Box(
                                            modifier         = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(Brush.verticalGradient(pkg.gradient)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector        = when (pkg.id) {
                                                    CoinManager.PACKAGE_WEEKLY_ID  -> Icons.Default.CalendarViewWeek
                                                    CoinManager.PACKAGE_MONTHLY_ID -> Icons.Default.CalendarMonth
                                                    else                           -> Icons.Default.Stars
                                                },
                                                contentDescription = null,
                                                tint               = Color.White,
                                                modifier           = Modifier.size(24.dp)
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(
                                                    text       = pkg.label,
                                                    style      = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                    color      = if (canAfford) colorScheme.onSurface else colorScheme.onSurface.copy(alpha = 0.4f)
                                                )
                                                if (pkg.badge != null) {
                                                    Surface(
                                                        shape = RoundedCornerShape(50.dp),
                                                        color = pkg.gradient.first().copy(alpha = 0.15f)
                                                    ) {
                                                        Text(
                                                            text     = pkg.badge,
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color    = pkg.gradient.first(),
                                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                text  = pkg.duration,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (canAfford) colorScheme.onSurfaceVariant else colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Icon(Icons.Default.MonetizationOn, null, tint = if (canAfford) colorScheme.primary else colorScheme.onSurfaceVariant.copy(alpha = 0.3f), modifier = Modifier.size(14.dp))
                                                Text(
                                                    text       = "${pkg.cost} koin",
                                                    style      = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                    color      = if (canAfford) colorScheme.primary else colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                                )
                                            }
                                        }

                                        Button(
                                            onClick  = {
                                                if (canAfford && isNetworkAvailable) {
                                                    packageToConfirm  = pkg
                                                    showConfirmDialog = true
                                                }
                                            },
                                            enabled  = canAfford && isNetworkAvailable,
                                            shape    = RoundedCornerShape(12.dp),
                                            modifier = Modifier.height(38.dp),
                                            colors   = ButtonDefaults.buttonColors(
                                                containerColor         = colorScheme.primary,
                                                contentColor           = colorScheme.onPrimary,
                                                disabledContainerColor = colorScheme.surfaceContainerHighest,
                                                disabledContentColor   = colorScheme.onSurface.copy(alpha = 0.3f)
                                            ),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            Text(
                                                text       = if (canAfford) "Tukar" else "Kurang",
                                                fontWeight = FontWeight.Bold,
                                                fontSize   = 13.sp
                                            )
                                        }
                                    }
                                }
                            }

                            if (!isNetworkAvailable) {
                                Row(
                                    modifier              = Modifier.fillMaxWidth(),
                                    verticalAlignment     = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.SignalWifiOff, null, tint = colorScheme.error, modifier = Modifier.size(16.dp))
                                    Text(text = "Tidak ada koneksi internet", style = MaterialTheme.typography.bodySmall, color = colorScheme.error)
                                }
                            }
                        }
                    }

                    StoreUiState.REDEEMING -> {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                CircularProgressIndicator(color = colorScheme.primary, strokeWidth = 3.dp, modifier = Modifier.size(48.dp))
                                Text(text = "Memproses penukaran...", style = MaterialTheme.typography.bodyMedium, color = colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    StoreUiState.SUCCESS -> {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape    = RoundedCornerShape(24.dp),
                                colors   = CardDefaults.cardColors(containerColor = colorScheme.secondaryContainer)
                            ) {
                                Column(
                                    modifier            = Modifier.fillMaxWidth().padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, null, tint = colorScheme.secondary, modifier = Modifier.size(56.dp))
                                    Text(
                                        text       = "Premium Aktif! 🎉",
                                        style      = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                        color      = colorScheme.onSecondaryContainer,
                                        textAlign  = TextAlign.Center
                                    )
                                    Text(
                                        text      = packages.firstOrNull { it.id == successPackageId }?.duration ?: "",
                                        style     = MaterialTheme.typography.bodyMedium,
                                        color     = colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text      = "Sisa koin: $coinBalance",
                                        style     = MaterialTheme.typography.labelMedium,
                                        color     = colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            Button(
                                onClick  = { navController.popBackStack() },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape    = RoundedCornerShape(16.dp)
                            ) {
                                Text(text = "Kembali", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
