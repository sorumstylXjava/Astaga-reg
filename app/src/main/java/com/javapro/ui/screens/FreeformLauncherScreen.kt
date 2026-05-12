package com.javapro.ui.screens

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.javapro.R
import com.javapro.utils.ShizukuManager
import com.javapro.utils.TweakExecutor
import kotlinx.coroutines.launch

private val FreeformPurple = Color(0xFF7C4DFF)

private data class SizePreset(val labelRes: Int, val widthDp: Int, val heightDp: Int)

private val SIZE_PRESETS = listOf(
    SizePreset(R.string.freeform_size_small,  320, 480),
    SizePreset(R.string.freeform_size_medium, 400, 600),
    SizePreset(R.string.freeform_size_large,  520, 760)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeformLauncherScreen(navController: NavController, lang: String) {
    val context     = LocalContext.current
    val scope       = rememberCoroutineScope()
    val isRooted    = remember { TweakExecutor.checkRoot() }
    val isShizuku   = remember { ShizukuManager.isAvailable() }
    val hasAccess   = isRooted || isShizuku
    val isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    var packageInput    by remember { mutableStateOf("") }
    var selectedPreset  by remember { mutableStateOf(1) }
    var launchResult    by remember { mutableStateOf<Boolean?>(null) }
    var enableStatus    by remember { mutableStateOf<Boolean?>(null) }
    var isEnabling      by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (hasAccess && isSupported) {
            isEnabling = true
            TweakExecutor.execute("settings put global enable_freeform_support 1")
            TweakExecutor.execute("settings put global force_resizable_activities 1")
            isEnabling   = false
            enableStatus = true
        }
    }

    fun doLaunch() {
        val pkg = packageInput.trim()
        if (pkg.isBlank()) return
        if (enableStatus != true) {
            Toast.makeText(context, context.getString(R.string.freeform_launch_requires_enabled), Toast.LENGTH_SHORT).show()
            return
        }
        val preset = SIZE_PRESETS[selectedPreset]
        val result = try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val density = context.resources.displayMetrics.density
                val pxW     = (preset.widthDp  * density).toInt()
                val pxH     = (preset.heightDp * density).toInt()
                val screenW = context.resources.displayMetrics.widthPixels
                val screenH = context.resources.displayMetrics.heightPixels
                val left    = (screenW - pxW) / 2
                val top     = (screenH - pxH) / 2
                val options = ActivityOptions.makeFreeformWindowOptions()
                options.setLaunchBounds(Rect(left, top, left + pxW, top + pxH))
                context.startActivity(intent, options.toBundle())
            } else {
                context.startActivity(intent)
            }
            true
        } catch (e: Exception) { false }

        launchResult = result
        Toast.makeText(
            context,
            context.getString(if (result) R.string.freeform_launch_ok else R.string.freeform_launch_failed),
            Toast.LENGTH_SHORT
        ).show()
    }

    Scaffold(
        containerColor      = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title          = { Text(stringResource(R.string.freeform_title), fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Default.ArrowBack, null, tint = MaterialTheme.colorScheme.onSurface) } },
                colors         = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            if (!isSupported) {
                FwFeedback(Icons.Default.Warning, stringResource(R.string.freeform_failed), Color(0xFFEF5350))
            }

            FwLabel(Icons.Default.SettingsSuggest, stringResource(R.string.freeform_status_off).uppercase(), MaterialTheme.colorScheme.tertiary)
            FwCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.4f)), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (hasAccess) FreeformPurple.copy(0.12f) else Color(0xFFEF5350).copy(0.12f))
                                .border(BorderStroke(1.dp, if (hasAccess) FreeformPurple.copy(0.3f) else Color(0xFFEF5350).copy(0.3f)), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(if (hasAccess) Icons.Default.CheckCircle else Icons.Default.Warning, null, tint = if (hasAccess) FreeformPurple else Color(0xFFEF5350), modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                if (enableStatus == true) stringResource(R.string.freeform_status_on)
                                else if (!hasAccess) stringResource(R.string.freeform_no_access)
                                else stringResource(R.string.freeform_status_off),
                                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.freeform_toggle_desc),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isRooted) FwBadge("ROOT", Color(0xFF4CAF50))
                        if (!isRooted && isShizuku) FwBadge("SHIZUKU", FreeformPurple)
                        if (!hasAccess) FwBadge(stringResource(R.string.freeform_no_access), Color(0xFFEF5350))
                    }

                    if (isEnabling) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = FreeformPurple)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.freeform_status_off), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    AnimatedVisibility(enableStatus == true && !isEnabling, enter = fadeIn(), exit = fadeOut()) {
                        FwFeedback(Icons.Default.CheckCircle, stringResource(R.string.freeform_enabled_ok), Color(0xFF66BB6A))
                    }

                    if (hasAccess && isSupported) {
                        Surface(
                            onClick  = {
                                scope.launch {
                                    isEnabling   = true
                                    enableStatus = null
                                    TweakExecutor.execute("settings put global enable_freeform_support 1")
                                    TweakExecutor.execute("settings put global force_resizable_activities 1")
                                    isEnabling   = false
                                    enableStatus = true
                                }
                            },
                            shape    = RoundedCornerShape(12.dp),
                            color    = FreeformPurple.copy(0.1f),
                            border   = BorderStroke(0.8.dp, FreeformPurple.copy(0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Refresh, null, tint = FreeformPurple, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.freeform_status_on), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = FreeformPurple)
                            }
                        }
                    }
                }
            }

            FwLabel(Icons.Default.AspectRatio, stringResource(R.string.freeform_window_size_title).uppercase(), FreeformPurple)
            FwCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.freeform_size_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SIZE_PRESETS.forEachIndexed { index, preset ->
                            val isSelected = selectedPreset == index
                            Surface(
                                onClick  = { selectedPreset = index },
                                shape    = RoundedCornerShape(10.dp),
                                color    = if (isSelected) FreeformPurple else MaterialTheme.colorScheme.surfaceContainerHigh,
                                border   = BorderStroke(0.8.dp, if (isSelected) FreeformPurple else MaterialTheme.colorScheme.outlineVariant.copy(0.4f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    Modifier.padding(vertical = 10.dp),
                                    horizontalAlignment     = Alignment.CenterHorizontally,
                                    verticalArrangement     = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(stringResource(preset.labelRes), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface)
                                    Text("${preset.widthDp}×${preset.heightDp}", fontSize = 9.sp, color = if (isSelected) Color.White.copy(0.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            FwLabel(Icons.Default.OpenInNew, stringResource(R.string.freeform_launch_title).uppercase(), FreeformPurple)
            FwCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value         = packageInput,
                        onValueChange = { packageInput = it; launchResult = null },
                        label         = { Text(stringResource(R.string.freeform_pkg_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        placeholder   = { Text("com.example.app", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f), fontSize = 12.sp) },
                        singleLine    = true,
                        leadingIcon   = { Icon(Icons.Default.Android, null, tint = FreeformPurple, modifier = Modifier.size(18.dp)) },
                        shape         = RoundedCornerShape(12.dp),
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = FreeformPurple.copy(0.6f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(0.4f),
                            focusedTextColor     = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor   = MaterialTheme.colorScheme.onSurface,
                            cursorColor          = MaterialTheme.colorScheme.onSurface
                        )
                    )

                    AnimatedVisibility(launchResult == true, enter = fadeIn(), exit = fadeOut()) {
                        FwFeedback(Icons.Default.CheckCircle, stringResource(R.string.freeform_launch_ok), Color(0xFF66BB6A))
                    }
                    AnimatedVisibility(launchResult == false, enter = fadeIn(), exit = fadeOut()) {
                        FwFeedback(Icons.Default.Warning, stringResource(R.string.freeform_launch_failed), Color(0xFFEF5350))
                    }

                    Surface(
                        onClick  = { if (isSupported && packageInput.isNotBlank()) doLaunch() },
                        enabled  = isSupported && packageInput.isNotBlank(),
                        shape    = RoundedCornerShape(12.dp),
                        color    = if (isSupported && packageInput.isNotBlank()) FreeformPurple else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.OpenInNew, null, tint = if (isSupported && packageInput.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.freeform_launch_btn), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (isSupported && packageInput.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            FwLabel(Icons.Default.Lightbulb, "TIPS", MaterialTheme.colorScheme.tertiary)
            FwCard {
                FwFeedback(Icons.Default.Info, stringResource(R.string.freeform_tip), MaterialTheme.colorScheme.tertiary)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun FwCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.4f)), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) { Column(content = content) }
}

@Composable
private fun FwLabel(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 2.dp, top = 4.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, color = tint, letterSpacing = 1.2.sp)
    }
}

@Composable
private fun FwBadge(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(0.12f), border = BorderStroke(0.8.dp, color.copy(0.4f))) {
        Text(text, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), letterSpacing = 0.5.sp)
    }
}

@Composable
private fun FwFeedback(icon: ImageVector, text: String, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(0.08f))
            .border(BorderStroke(0.5.dp, color.copy(0.3f)), RoundedCornerShape(10.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, fontSize = 11.sp, color = color, lineHeight = 15.sp)
    }
}
