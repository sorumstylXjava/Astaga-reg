package com.javapro.ui.screens

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.javapro.utils.CoinManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService
import androidx.navigation.NavController
import com.javapro.utils.AdWatchValidator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class CoinUiState {
    IDLE, LOADING_AD, LOADING_API, SUCCESS, ERROR, FRAUD
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinRewardScreen(
    navController : NavController,
    onWatchAd     : (onAdStarted: () -> Unit, onAdFinished: (AdWatchResult) -> Unit) -> Unit,
    lang          : String = "en"
) {
    val context     = LocalContext.current
    val scope       = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

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
                override fun onAvailable(network: Network) {
                    mainHandler.post { isNetworkAvailable = hasActiveNetwork() }
                }
                override fun onLost(network: Network) {
                    mainHandler.post { isNetworkAvailable = hasActiveNetwork() }
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    mainHandler.post { isNetworkAvailable = hasActiveNetwork() }
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            onDispose { cm.unregisterNetworkCallback(callback) }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000L)
            isNetworkAvailable = hasActiveNetwork()
        }
    }

    var uiState          by remember { mutableStateOf(CoinUiState.IDLE) }
    var coinBalance      by remember { mutableIntStateOf(CoinManager.getCachedBalance(context)) }
    var lastEarnedCoins  by remember { mutableIntStateOf(0) }
    var errorMsg         by remember { mutableStateOf("") }
    var isLoadingBalance by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoadingBalance = true
        coinBalance      = CoinManager.fetchBalance(context, forceRefresh = true)
        isLoadingBalance = false
    }

    fun watchAd() {
        if (!isNetworkAvailable) {
            Toast.makeText(context, "Tidak ada koneksi internet", Toast.LENGTH_SHORT).show()
            return
        }
        if (uiState == CoinUiState.LOADING_AD || uiState == CoinUiState.LOADING_API) return

        uiState = CoinUiState.LOADING_AD
        onWatchAd(
            { AdWatchValidator.markAdStart(context) },
            { result ->
                when (result) {
                    AdWatchResult.COMPLETED -> {
                        scope.launch {
                            uiState = CoinUiState.LOADING_API
                            when (val res = CoinManager.earnCoins(context)) {
                                is CoinManager.EarnResult.Success -> {
                                    lastEarnedCoins = res.coinsEarned
                                    coinBalance     = res.newBalance
                                    uiState         = CoinUiState.SUCCESS
                                }
                                CoinManager.EarnResult.AdFraudDetected -> {
                                    uiState  = CoinUiState.FRAUD
                                    errorMsg = "Iklan tidak valid. Coba lagi."
                                }
                                CoinManager.EarnResult.TamperDetected -> {
                                    uiState  = CoinUiState.ERROR
                                    errorMsg = "Terjadi kesalahan keamanan."
                                }
                                CoinManager.EarnResult.NetworkError -> {
                                    uiState  = CoinUiState.ERROR
                                    errorMsg = "Koneksi gagal. Coba lagi."
                                }
                                CoinManager.EarnResult.ServerError -> {
                                    uiState  = CoinUiState.ERROR
                                    errorMsg = "Server error. Coba beberapa saat lagi."
                                }
                            }
                        }
                    }
                    AdWatchResult.SKIPPED -> {
                        uiState = CoinUiState.IDLE
                        AdWatchValidator.clearAdStart(context)
                    }
                    AdWatchResult.UNAVAILABLE -> {
                        uiState  = CoinUiState.ERROR
                        errorMsg = "Iklan tidak tersedia saat ini."
                        AdWatchValidator.clearAdStart(context)
                    }
                }
            }
        )
    }

    Scaffold(
        containerColor      = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text       = "Kumpulkan Koin",
                        style      = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color      = colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = colorScheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate("coin_store") }) {
                        Icon(Icons.Default.Store, null, tint = colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
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
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier         = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector        = Icons.Default.MonetizationOn,
                            contentDescription = null,
                            tint               = colorScheme.primary,
                            modifier           = Modifier.size(30.dp)
                        )
                    }
                    Column {
                        Text(
                            text       = "Saldo Koin",
                            style      = MaterialTheme.typography.labelMedium,
                            color      = colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        if (isLoadingBalance) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color       = colorScheme.primary
                            )
                        } else {
                            Text(
                                text       = "$coinBalance Koin",
                                style      = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color      = colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { navController.navigate("coin_store") }) {
                        Text(text = "Tukar", fontWeight = FontWeight.Bold, color = colorScheme.primary)
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(20.dp),
                colors   = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier            = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text       = "Cara Mendapatkan Koin",
                        style      = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color      = colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.PlayCircleFilled, null, tint = colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text(
                            text  = "Tonton 1 iklan → dapat ${CoinManager.COIN_PER_AD_MIN}–${CoinManager.COIN_PER_AD_MAX} koin (random)",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Store, null, tint = colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text(
                            text  = "Tukar koin di toko untuk premium",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            when (uiState) {
                CoinUiState.IDLE, CoinUiState.FRAUD -> {
                    if (uiState == CoinUiState.FRAUD) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape    = RoundedCornerShape(16.dp),
                            colors   = CardDefaults.cardColors(containerColor = colorScheme.errorContainer)
                        ) {
                            Row(
                                modifier          = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.Warning, null, tint = colorScheme.error, modifier = Modifier.size(20.dp))
                                Text(text = errorMsg, style = MaterialTheme.typography.bodySmall, color = colorScheme.onErrorContainer)
                            }
                        }
                    }
                    Button(
                        onClick  = { watchAd() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape    = RoundedCornerShape(16.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = colorScheme.primary,
                            contentColor   = colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Default.PlayCircleFilled, null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(text = "Tonton Iklan", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }

                    if (!isNetworkAvailable) {
                        Row(
                            modifier          = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.SignalWifiOff, null, tint = colorScheme.error, modifier = Modifier.size(16.dp))
                            Text(
                                text  = "Tidak ada koneksi internet",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.error
                            )
                        }
                    }
                }

                CoinUiState.LOADING_AD -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(16.dp),
                        colors   = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier              = Modifier.fillMaxWidth().padding(20.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = colorScheme.primary)
                            Text(text = "Memuat iklan...", style = MaterialTheme.typography.bodyMedium, color = colorScheme.onSurfaceVariant)
                        }
                    }
                }

                CoinUiState.LOADING_API -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(16.dp),
                        colors   = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier              = Modifier.fillMaxWidth().padding(20.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = colorScheme.primary)
                            Text(text = "Memproses koin...", style = MaterialTheme.typography.bodyMedium, color = colorScheme.onSurfaceVariant)
                        }
                    }
                }

                CoinUiState.SUCCESS -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(20.dp),
                        colors   = CardDefaults.cardColors(containerColor = colorScheme.secondaryContainer)
                    ) {
                        Column(
                            modifier            = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, null, tint = colorScheme.secondary, modifier = Modifier.size(40.dp))
                            Text(
                                text       = "+$lastEarnedCoins Koin",
                                style      = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color      = colorScheme.onSecondaryContainer
                            )
                            Text(
                                text      = "Berhasil! Saldo kamu sekarang $coinBalance koin",
                                style     = MaterialTheme.typography.bodySmall,
                                color     = colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Button(
                        onClick  = { uiState = CoinUiState.IDLE },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape    = RoundedCornerShape(16.dp)
                    ) {
                        Text(text = "Tonton Lagi", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick  = { navController.navigate("coin_store") },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape    = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Store, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(text = "Ke Toko Koin", fontWeight = FontWeight.SemiBold)
                    }
                }

                CoinUiState.ERROR -> {
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
                    Button(
                        onClick  = { uiState = CoinUiState.IDLE },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape    = RoundedCornerShape(16.dp)
                    ) {
                        Text(text = "Coba Lagi", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
