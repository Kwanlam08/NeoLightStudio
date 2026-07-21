package com.lightstickstudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.shadow.Shadow

private val LocalLightStickBackdrop = compositionLocalOf<LayerBackdrop?> { null }

private const val SERVICE_UUID = "8ec91001-f315-4f60-9fb8-838830daea50"
private const val COLOR_UUID = "8ec91002-f315-4f60-9fb8-838830daea50"
private const val RAINBOW_UUID = "8ec91003-f315-4f60-9fb8-838830daea50"

enum class ThemeMode { FOLLOW_SYSTEM, LIGHT, DARK }
enum class Screen { HOME, CREATE, SETTINGS }

data class Device(val name: String, val address: String, val rssi: Int)
data class UiState(
    val devices: List<Device> = emptyList(),
    val connected: String? = null,
    val status: String = "",
    val colorReady: Boolean = false,
    val rainbowReady: Boolean = false,
    val hue: Float = 170f,
    val brightness: Float = 1f,
    val rainbowCycleSeconds: Int = 180,
    val rainbowPlaying: Boolean = false,
    val musicReactive: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM
)

class LightStickViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("lightstick_prefs", Context.MODE_PRIVATE)
    private val adapter get() = getApplication<Application>().getSystemService(BluetoothManager::class.java).adapter
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var rainbowJob: Job? = null
    private var musicJob: Job? = null
    private var audioRecord: android.media.AudioRecord? = null
    private val found = linkedMapOf<String, Device>()
    private val _state = MutableStateFlow(UiState(
        themeMode = try { ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.FOLLOW_SYSTEM.name)!!) } catch (_: Exception) { ThemeMode.FOLLOW_SYSTEM }
    ))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun s(id: Int): String = getApplication<Application>().getString(id)
    private fun s(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    init { _state.update { it.copy(status = s(R.string.status_ready)) } }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _state.update { it.copy(themeMode = mode) }
    }

    @SuppressLint("MissingPermission") fun scan() {
        scanner?.stopScan(scanCallback); found.clear(); _state.update { it.copy(devices = emptyList(), status = s(R.string.status_scanning)) }
        scanner = adapter?.bluetoothLeScanner; scanner?.startScan(scanCallback)
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            val d = Device(result.device.name ?: s(R.string.unnamed_device), result.device.address, result.rssi)
            found[d.address] = d
            _state.update { it.copy(devices = found.values.toList()) }
        }
        override fun onScanFailed(code: Int) { _state.update { it.copy(status = s(R.string.status_scan_failed, code)) } }
    }
    @SuppressLint("MissingPermission") fun connect(address: String) {
        scanner?.stopScan(scanCallback); gatt?.close(); _state.update { it.copy(status = s(R.string.status_connecting), colorReady = false, rainbowReady = false) }
        gatt = adapter?.getRemoteDevice(address)?.connectGatt(getApplication(), false, callback, BluetoothDevice.TRANSPORT_LE)
    }
    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission") override fun onConnectionStateChange(g: BluetoothGatt, code: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) { _state.update { it.copy(connected = g.device.name ?: g.device.address, status = s(R.string.status_connected_preparing)) }; g.discoverServices() }
            else _state.update { it.copy(connected = null, colorReady = false, rainbowReady = false, status = s(R.string.status_disconnected)) }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, code: Int) {
            val service = g.getService(UUID.fromString(SERVICE_UUID))
            _state.update { it.copy(colorReady = service?.getCharacteristic(UUID.fromString(COLOR_UUID)) != null, rainbowReady = service?.getCharacteristic(UUID.fromString(RAINBOW_UUID)) != null, status = s(R.string.status_ready_control)) }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, code: Int) { _state.update { it.copy(status = if (code == BluetoothGatt.GATT_SUCCESS) s(R.string.status_updated) else s(R.string.status_write_failed, code)) } }
    }
    fun setColor(hue: Float = _state.value.hue, brightness: Float = _state.value.brightness) {
        val h = hue.coerceIn(0f, 360f); val b = brightness.coerceIn(0.05f, 1f); _state.update { it.copy(hue = h, brightness = b) }
        val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f)); val packet = ByteArray(20).also { p -> p[0] = 1; p[1] = 15; p[3] = (((rgb shr 16) and 255) * b).toInt().toByte(); p[4] = (((rgb shr 8) and 255) * b).toInt().toByte(); p[5] = ((rgb and 255) * b).toInt().toByte() }
        write(COLOR_UUID, packet, s(R.string.status_syncing))
    }
    fun setRainbowCycleSeconds(value: Float) { _state.update { it.copy(rainbowCycleSeconds = value.toInt().coerceIn(60, 600)) } }
    fun toggleSlowRainbow() {
        if (rainbowJob != null) { rainbowJob?.cancel(); rainbowJob = null; _state.update { it.copy(rainbowPlaying = false, status = s(R.string.status_gradient_stopped)) }; return }
        if (!_state.value.colorReady) return
        rainbowJob = viewModelScope.launch {
            _state.update { it.copy(rainbowPlaying = true, status = s(R.string.status_gradient_playing)) }
            var step = 0
            while (isActive) { sendRainbowFrame(step * 3f, _state.value.brightness); step = (step + 1) % 120; delay((_state.value.rainbowCycleSeconds * 1000L) / 120L) }
        }
    }
    fun playExitRainbow() {
        val packet = ByteArray(20).also { it[0] = 1; it[1] = 15; it[3] = 7; it[4] = 13 }
        write(RAINBOW_UUID, packet, s(R.string.status_rainbow_playing))
    }
    fun toggleMusicReactive() {
        if (musicJob != null) { stopMusicReactive(); return }
        val rate = 44100; val minimum = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
        val record = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, minimum)
        if (record.state != android.media.AudioRecord.STATE_INITIALIZED) { _state.update { it.copy(status = s(R.string.status_mic_failed)) }; return }
        audioRecord = record; record.startRecording()
        musicJob = viewModelScope.launch(Dispatchers.Default) {
            val samples = ShortArray(1024); _state.update { it.copy(musicReactive = true, status = s(R.string.status_music_on)) }
            while (isActive) { val n = record.read(samples, 0, samples.size); if (n > 0) { var energy = 0.0; for (i in 0 until n) energy += samples[i].toDouble() * samples[i]; val level = (kotlin.math.sqrt(energy / n) / 8000.0).coerceIn(0.0, 1.0).toFloat(); sendRainbowFrame(_state.value.hue, 0.12f + level * 0.88f) }; delay(140) }
        }
    }
    private fun stopMusicReactive() { musicJob?.cancel(); musicJob = null; audioRecord?.stop(); audioRecord?.release(); audioRecord = null; _state.update { it.copy(musicReactive = false, status = s(R.string.status_music_off)) } }
    private fun sendRainbowFrame(hue: Float, brightness: Float) {
        val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue % 360f, 1f, 1f)); val packet = ByteArray(20).also { p -> p[0] = 1; p[1] = 15; p[3] = (((rgb shr 16) and 255) * brightness).toInt().toByte(); p[4] = (((rgb shr 8) and 255) * brightness).toInt().toByte(); p[5] = ((rgb and 255) * brightness).toInt().toByte() }; write(COLOR_UUID, packet, "")
    }
    @SuppressLint("MissingPermission") private fun write(uuid: String, packet: ByteArray, pending: String) {
        val connection = gatt; val characteristic = connection?.getService(UUID.fromString(SERVICE_UUID))?.getCharacteristic(UUID.fromString(uuid))
        if (connection == null || characteristic == null) { _state.update { it.copy(status = s(R.string.status_no_channel)) }; return }; if (pending.isNotEmpty()) _state.update { it.copy(status = pending) }
        if (Build.VERSION.SDK_INT >= 33) { if (connection.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) != BluetoothStatusCodes.SUCCESS) _state.update { it.copy(status = s(R.string.status_write_error)) } }
        else { characteristic.value = packet; characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; if (!connection.writeCharacteristic(characteristic)) _state.update { it.copy(status = s(R.string.status_write_error)) } }
    }
    override fun onCleared() { rainbowJob?.cancel(); stopMusicReactive(); gatt?.close() }
}

class MainActivity : ComponentActivity() {
    private lateinit var model: LightStickViewModel
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.scan() }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) model.toggleMusicReactive() }
    override fun onCreate(state: Bundle?) { super.onCreate(state); model = ViewModelProvider(this)[LightStickViewModel::class.java]; setContent { StudioTheme(model) { StudioScreen(model, this) } } }
    fun requestBluetooth() = permissions.launch(if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
    fun toggleMusicReactive() { if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) model.toggleMusicReactive() else audioPermission.launch(Manifest.permission.RECORD_AUDIO) }
}

@Composable private fun StudioTheme(model: LightStickViewModel, content: @Composable () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val dark = when (state.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.FOLLOW_SYSTEM -> systemDark
    }
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(
            primary = Color(0xFFA99BFF),
            secondary = Color(0xFF62E6D5),
            background = Color(0xFF0F0F23),
            surface = Color(0xFF1A1933)
        ) else lightColorScheme(
            primary = Color(0xFF5647B8),
            secondary = Color(0xFF007D70),
            background = Color(0xFFF7F6FC),
            surface = Color(0xFFFFFFFF)
        ),
        content = content
    )
}

@Composable private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    val backdrop = LocalLightStickBackdrop.current
    val shape = RoundedCornerShape(28.dp)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val surface = surfaceColor.copy(alpha = if (backdrop != null) 0.14f else if (isDark) 0.88f else 0.90f)
    val borderColor = onSurface.copy(alpha = if (isDark) 0.18f else 0.10f)
    val modifier = if (backdrop != null) Modifier.fillMaxWidth().drawBackdrop(backdrop = backdrop, shape = { shape }, effects = { vibrancy(); backdropBlur(18.dp.toPx()) }, shadow = { Shadow(radius = 16.dp, color = Color.Black.copy(alpha = .22f), alpha = .8f) }, onDrawSurface = { drawRect(surface) }) else Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.90f), shape)
    Box(modifier.border(1.dp, borderColor, shape).padding(20.dp)) { Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun StudioScreen(model: LightStickViewModel, activity: MainActivity) {
    val state by model.state.collectAsStateWithLifecycle()
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    val backdrop = rememberLayerBackdrop()
    CompositionLocalProvider(LocalLightStickBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(MaterialTheme.colorScheme.background))
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    CenterAlignedTopAppBar(
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.app_title), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
                                Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = Color.Transparent) {
                        NavigationBarItem(selected = currentScreen == Screen.HOME, onClick = { currentScreen = Screen.HOME }, icon = { Icon(Icons.Default.Home, contentDescription = null) }, label = { Text(stringResource(R.string.nav_home)) })
                        NavigationBarItem(selected = currentScreen == Screen.CREATE, onClick = { currentScreen = Screen.CREATE }, icon = { Icon(Icons.Default.MusicNote, contentDescription = null) }, label = { Text(stringResource(R.string.nav_create)) })
                        NavigationBarItem(selected = currentScreen == Screen.SETTINGS, onClick = { currentScreen = Screen.SETTINGS }, icon = { Icon(Icons.Default.Settings, contentDescription = null) }, label = { Text(stringResource(R.string.nav_settings)) })
                    }
                }
            ) { inset ->
                when (currentScreen) {
                    Screen.HOME -> HomeScreen(Modifier.padding(inset), model, activity)
                    Screen.CREATE -> CreateScreen(Modifier.padding(inset), model, activity)
                    Screen.SETTINGS -> SettingsScreen(Modifier.padding(inset), model)
                }
            }
        }
    }
}

@Composable private fun HomeScreen(modifier: Modifier, model: LightStickViewModel, activity: MainActivity) {
    val state by model.state.collectAsStateWithLifecycle()
    LazyColumn(modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Text(state.status, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium) }
        if (state.connected == null) {
            item { Button(onClick = activity::requestBluetooth, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(20.dp)) { Text(stringResource(R.string.scan_button)) } }
            item { Text(stringResource(R.string.scan_hint), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), style = MaterialTheme.typography.bodySmall) }
            items(state.devices, key = { it.address }) { d ->
                GlassCard {
                    Text(d.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text("${d.address} · ${d.rssi} dBm", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                    Button(onClick = { model.connect(d.address) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect_button)) }
                }
            }
        } else {
            item {
                GlassCard {
                    Text(stringResource(R.string.connected_label), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    Text(state.connected!!, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.nct_v2_label), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                }
            }
            item {
                GlassCard {
                    Text(stringResource(R.string.exit_rainbow_title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.exit_rainbow_desc), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f))
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = model::playExitRainbow, enabled = state.rainbowReady, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(20.dp)) {
                        Text(stringResource(R.string.exit_rainbow_button), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable private fun CreateScreen(modifier: Modifier, model: LightStickViewModel, activity: MainActivity) {
    val state by model.state.collectAsStateWithLifecycle()
    LazyColumn(modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (state.connected == null) {
            item { GlassCard { Text(stringResource(R.string.please_connect_first), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium) } }
        } else {
            item {
                GlassCard {
                    Text(stringResource(R.string.gradient_title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.gradient_desc), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.cycle_seconds, state.rainbowCycleSeconds), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    Slider(value = state.rainbowCycleSeconds.toFloat(), onValueChange = model::setRainbowCycleSeconds, valueRange = 60f..600f, steps = 53, enabled = state.colorReady)
                    Button(onClick = model::toggleSlowRainbow, enabled = state.colorReady, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (state.rainbowPlaying) R.string.stop_gradient else R.string.play_gradient))
                    }
                }
            }
            item {
                GlassCard {
                    Text(stringResource(R.string.music_title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.music_desc), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (state.musicReactive) R.string.music_on else R.string.music_off), color = if (state.musicReactive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                        Switch(checked = state.musicReactive, onCheckedChange = { activity.toggleMusicReactive() }, enabled = state.colorReady)
                    }
                }
            }
            item {
                GlassCard {
                    Text(stringResource(R.string.color_brightness_title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.brightness_label, (state.brightness * 100).toInt()), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
                    Slider(value = state.brightness, onValueChange = { model.setColor(brightness = it) }, valueRange = .05f..1f, enabled = state.colorReady)
                    Text(stringResource(R.string.hue_label), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
                    Slider(value = state.hue, onValueChange = { model.setColor(hue = it) }, valueRange = 0f..360f, enabled = state.colorReady)
                    Spacer(Modifier.height(4.dp))
                    val colorPresets = listOf(
                        stringResource(R.string.color_red) to 0f,
                        stringResource(R.string.color_yellow) to 50f,
                        stringResource(R.string.color_cyan) to 180f,
                        stringResource(R.string.color_purple) to 280f
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        colorPresets.forEach { (name, hue) -> OutlinedButton(onClick = { model.setColor(hue = hue) }, enabled = state.colorReady, modifier = Modifier.weight(1f)) { Text(name) } }
                    }
                }
            }
        }
    }
}

@Composable private fun SettingsScreen(modifier: Modifier, model: LightStickViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val followLabel = stringResource(R.string.theme_follow_system)
    val lightLabel = stringResource(R.string.theme_light)
    val darkLabel = stringResource(R.string.theme_dark)
    val labels = mapOf(ThemeMode.FOLLOW_SYSTEM to followLabel, ThemeMode.LIGHT to lightLabel, ThemeMode.DARK to darkLabel)
    LazyColumn(modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            GlassCard {
                Text(stringResource(R.string.appearance_title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                ThemeMode.entries.forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(labels[mode]!!, color = MaterialTheme.colorScheme.onSurface)
                        RadioButton(selected = state.themeMode == mode, onClick = { model.setThemeMode(mode) })
                    }
                }
            }
        }
        item {
            GlassCard {
                Text(stringResource(R.string.about_title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.about_app_name), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.about_desc), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.about_warning), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
