package com.vrshield

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.*
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // ── UI views ──────────────────────────────────────────────────────────────
    private lateinit var btnScan: Button
    private lateinit var btnClear: Button
    private lateinit var btnHistory: Button
    private lateinit var btnBackground: Button
    private lateinit var tvStatusText: TextView
    private lateinit var tvStatusSub: TextView
    private lateinit var viewStatusDot: View
    private lateinit var tvMetricDetected: TextView
    private lateinit var tvMetricHigh: TextView
    private lateinit var tvMetricCycles: TextView
    private lateinit var tvMetricBT: TextView
    private lateinit var lvVRDevices: ListView
    private lateinit var lvAllDevices: ListView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var tvVRHeader: TextView
    private lateinit var tvAllHeader: TextView

    // ── BT ────────────────────────────────────────────────────────────────────
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var scanning = false
    private var backgroundRunning = false
    private val handler = Handler(Looper.getMainLooper())

    // ── Data ──────────────────────────────────────────────────────────────────
    private val vrDevices    = mutableMapOf<String, VRDevice>()
    private val allDevices   = mutableMapOf<String, BTDevice>()
    private val deviceGroups = mutableMapOf<String, DeviceGroup>()
    private val history      = mutableListOf<VRDevice>()

    private val vrListAdapter  by lazy { ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf()) }
    private val allListAdapter by lazy { ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf()) }

    // ── Settings ──────────────────────────────────────────────────────────────
    private var rssiThreshold = -75
    private var notificationCooldownMs = 10_000L
    private var lastNotificationTime = 0L
    private var scanCycles = 0

    // ── Constants ─────────────────────────────────────────────────────────────
    private val CHANNEL   = "vr_alerts"
    private val PREFS     = "VRShieldPrefs"
    private val HIST_KEY  = "detection_history"
    private val PERM_CODE = 100
    private val BT_CODE   = 101
    private val NOTIF_REQ = 102
    private val SCAN_CYCLE_MS = 8_000L

    // ── Scan callback ─────────────────────────────────────────────────────────
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(t: Int, r: ScanResult) { processScanResult(r) }
        override fun onBatchScanResults(rs: MutableList<ScanResult>) { rs.forEach { processScanResult(it) } }
        override fun onScanFailed(errorCode: Int) { Log.e("VRShield", "Scan failed: $errorCode") }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        btnScan          = findViewById(R.id.btnScan)
        btnClear         = findViewById(R.id.btnClear)
        btnHistory       = findViewById(R.id.btnHistory)
        btnBackground    = findViewById(R.id.btnBackground)
        tvStatusText     = findViewById(R.id.tvStatusText)
        tvStatusSub      = findViewById(R.id.tvStatusSub)
        viewStatusDot    = findViewById(R.id.viewStatusDot)
        tvMetricDetected = findViewById(R.id.tvMetricDetected)
        tvMetricHigh     = findViewById(R.id.tvMetricHigh)
        tvMetricCycles   = findViewById(R.id.tvMetricCycles)
        tvMetricBT       = findViewById(R.id.tvMetricBT)
        lvVRDevices      = findViewById(R.id.lvVRDevices)
        lvAllDevices     = findViewById(R.id.lvAllDevices)
        tvLog            = findViewById(R.id.tvLog)
        scrollLog        = findViewById(R.id.scrollLog)
        tvVRHeader       = findViewById(R.id.tvVRHeader)
        tvAllHeader      = findViewById(R.id.tvAllHeader)

        lvVRDevices.adapter  = vrListAdapter
        lvAllDevices.adapter = allListAdapter

        lvVRDevices.setOnItemClickListener  { _, _, pos, _ -> vrDevices.values.toList().getOrNull(pos)?.let { showVRDetail(it) } }
        lvAllDevices.setOnItemClickListener { _, _, pos, _ -> deviceGroups.values.toList().getOrNull(pos)?.let { showGroupDetail(it) } }

        btnScan.setOnClickListener       { if (scanning) stopScan() else checkBTAndScan() }
        btnClear.setOnClickListener      { clearSession() }
        btnHistory.setOnClickListener    { showHistory() }
        btnBackground.setOnClickListener { toggleBackground() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettings() }

        bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        createNotifChannel()
        requestNotifPermission()
        loadSettings()
        loadHistory()
        updateMetrics()
        updateHeaders()

        // UI is fully ready — safe to call appendLog now
        appendLog("VR Shield ready. ${ALL_SIGNATURES.size} signatures loaded.")
        appendLog("RSSI: ${rssiThreshold}dBm  Cooldown: ${notificationCooldownMs}ms")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (scanning) stopScan()
    }

    // ── Logging ───────────────────────────────────────────────────────────────
    // Named appendLog (not log) to avoid any Kotlin name resolution ambiguity
    private fun appendLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        Log.d("VRShield", msg)
        tvLog.append("[$ts] $msg\n")
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    // ── Bluetooth ─────────────────────────────────────────────────────────────
    private fun checkBTAndScan() {
        if (!hasPermissions()) { requestPermissions(); return }
        val bt = bluetoothAdapter ?: run { alert("No Bluetooth", "Device has no Bluetooth."); return }
        if (!bt.isEnabled) {
            appendLog("Bluetooth off — requesting enable...")
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), BT_CODE)
            return
        }
        startScan()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rq: Int, rc: Int, data: Intent?) {
        super.onActivityResult(rq, rc, data)
        if (rq == BT_CODE) {
            if (rc == Activity.RESULT_OK) {
                appendLog("Bluetooth enabled.")
                handler.postDelayed({ startScan() }, 500)
            } else {
                alert("Bluetooth Required", "Please enable Bluetooth and try again.")
            }
        }
    }

    private fun startScan() {
        bleScanner = bluetoothAdapter?.bluetoothLeScanner ?: run { appendLog("No BLE scanner — is BT on?"); return }
        scanning = true
        btnScan.text = "■  Stop scan"
        btnScan.setBackgroundColor(0xFFA32D2D.toInt())
        setStatus(ScanStatus.SCANNING, "Scanning...", "Watching for smart glasses & VR headsets")
        appendLog("─── Scan started ───")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build()
        try { bleScanner?.startScan(null, settings, scanCallback) } catch (e: Exception) { appendLog("Error: ${e.message}") }
        handler.postDelayed({ cycleScan() }, SCAN_CYCLE_MS)
    }

    private fun cycleScan() {
        if (!scanning) return
        scanCycles++
        updateMetrics()
        appendLog("Cycle $scanCycles — ${deviceGroups.size} BT groups · ${vrDevices.size} threats")
        handler.postDelayed({ cycleScan() }, SCAN_CYCLE_MS)
    }

    private fun stopScan() {
        scanning = false
        handler.removeCallbacksAndMessages(null)
        try { if (hasPermissions()) bleScanner?.stopScan(scanCallback) } catch (e: Exception) {}
        btnScan.text = "▶  Start scan"
        btnScan.setBackgroundColor(0xFF1DCA8A.toInt())
        setStatus(
            if (vrDevices.isEmpty()) ScanStatus.IDLE else ScanStatus.THREAT,
            if (vrDevices.isEmpty()) "Scan stopped" else "⚠ ${vrDevices.size} device(s) found!",
            "$scanCycles cycles · ${deviceGroups.size} unique BT groups"
        )
        appendLog("─── Scan stopped ───")
    }

    // ── Background service ────────────────────────────────────────────────────
    private fun toggleBackground() {
        if (!backgroundRunning) {
            if (!hasPermissions()) { requestPermissions(); return }
            val i = Intent(this, ScanService::class.java).apply { action = ScanService.ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            backgroundRunning = true
            btnBackground.text = "Stop background"
            btnBackground.setBackgroundColor(0xFFA32D2D.toInt())
            appendLog("Background scan active — app can now be closed.")
            alert("Background Scan Active",
                "VR Shield is scanning in the background.\n\nYou'll get a notification if smart glasses or a VR headset is detected nearby, even with the app closed.\n\nTap 'Stop background' or the notification Stop button to end it.")
        } else {
            startService(Intent(this, ScanService::class.java).apply { action = ScanService.ACTION_STOP })
            backgroundRunning = false
            btnBackground.text = "Run in background"
            btnBackground.setBackgroundColor(0xFF1E2538.toInt())
            appendLog("Background scan stopped.")
        }
    }

    // ── Detection ─────────────────────────────────────────────────────────────
    private fun processScanResult(result: ScanResult) {
        val mac  = result.device.address ?: return
        val rssi = result.rssi
        if (rssi < rssiThreshold) return

        val name = try {
            if (hasPermissions()) result.device.name ?: "" else ""
        } catch (e: SecurityException) { "" }

        val companyIds = mutableListOf<Int>()
        result.scanRecord?.manufacturerSpecificData?.let { d ->
            for (i in 0 until d.size()) companyIds.add(d.keyAt(i))
        }

        val isNew = !allDevices.containsKey(mac)
        val sig   = matchSig(name, mac, companyIds)
        val dist  = estimateDist(rssi)
        val distL = distLabel(rssi)

        // Only add to BT list if brand new device
        if (isNew) {
            val bt = BTDevice(name, mac, rssi, dist, distL, companyIds)
            allDevices[mac] = bt
            val groupKey   = if (name.isNotEmpty()) name else "unknown_$mac"
            val groupLabel = if (name.isNotEmpty()) name else "Unknown device"
            deviceGroups.getOrPut(groupKey) { DeviceGroup(groupLabel) }.addDevice(bt)
        }

        // If not new AND not a threat — skip UI update entirely (no BT list refresh)
        if (!isNew && sig == null && !vrDevices.containsKey(mac)) return

        runOnUiThread {
            if (isNew) {
                // New BT device — add to list once, never refresh again
                refreshAllList()
                resizeList(lvAllDevices, deviceGroups.size)
            }
            if (sig != null && !vrDevices.containsKey(mac)) {
                // New threat found
                val d = VRDevice(
                    name.ifEmpty { sig.vendor }, sig.vendor, sig.category,
                    mac, rssi, sig.risk, dist, companyIds,
                    buildMatchReason(name, mac, companyIds, sig), sig.note,
                    System.currentTimeMillis()
                )
                vrDevices[mac] = d
                onThreatFound(d)
            } else if (vrDevices.containsKey(mac)) {
                // Known threat — update distance live, no new entry
                vrDevices[mac]?.let { it.rssi = rssi; it.distance = dist }
                refreshVRList()
            }
            updateMetrics()
            updateHeaders()
        }
    }

    private fun matchSig(name: String, mac: String, ids: List<Int>): DeviceSignature? {
        for (sig in ALL_SIGNATURES) {
            val idMatch  = sig.companyIds.isNotEmpty()    && ids.any { it in sig.companyIds }
            val auxMatch = sig.auxCompanyIds.isNotEmpty() && ids.any { it in sig.auxCompanyIds }
            val nameMatch = name.isNotEmpty() && sig.nameKeywords.any { name.contains(it, ignoreCase = true) }
            val macMatch  = sig.macPrefixes.isNotEmpty() && sig.macPrefixes.any { mac.startsWith(it, ignoreCase = true) }

            if (sig.requireBothIdAndName) {
                if (idMatch && nameMatch) return sig
                continue
            }
            if (sig.requireNameForCompanyIdMatch) {
                if (nameMatch || macMatch || (idMatch && auxMatch)) return sig
                continue
            }
            if (idMatch || auxMatch || nameMatch || macMatch) return sig
        }
        return null
    }

    private fun buildMatchReason(name: String, mac: String, ids: List<Int>, sig: DeviceSignature): String {
        val r = mutableListOf<String>()
        if (sig.companyIds.isNotEmpty() && ids.any { it in sig.companyIds })
            r.add("Company ID: ${ids.filter { it in sig.companyIds }.map { "0x${it.toString(16).uppercase().padStart(4,'0')}" }} ✓")
        if (sig.auxCompanyIds.isNotEmpty() && ids.any { it in sig.auxCompanyIds })
            r.add("Aux ID: ${ids.filter { it in sig.auxCompanyIds }.map { "0x${it.toString(16).uppercase().padStart(4,'0')}" }}")
        if (name.isNotEmpty() && sig.nameKeywords.any { name.contains(it, ignoreCase = true) })
            r.add("Name: \"$name\"")
        if (sig.macPrefixes.any { mac.startsWith(it, ignoreCase = true) })
            r.add("MAC: ${mac.take(8)}")
        return r.joinToString(", ")
    }

    private fun onThreatFound(d: VRDevice) {
        history.add(0, d)
        saveHistory()
        refreshVRList()
        resizeList(lvVRDevices, vrListAdapter.count)
        val cat = if (d.category == DeviceCategory.SMART_GLASSES) "Smart glasses" else "VR headset"
        appendLog("🚨 $cat DETECTED: ${d.vendor} [${d.risk.label}] ~${d.distance}m")
        appendLog("   ${d.matchType}")
        setStatus(ScanStatus.THREAT,
            "⚠ $cat nearby!",
            "${d.vendor} · ~${d.distance}m · ${d.risk.label} risk")
        vibrate(d.risk)
        playAlarm(d.risk)
        val now = System.currentTimeMillis()
        if (now - lastNotificationTime >= notificationCooldownMs) {
            sendNotif(d)
            lastNotificationTime = now
        }
    }

    // ── Distance ──────────────────────────────────────────────────────────────
    private fun estimateDist(rssi: Int): Float =
        Math.pow(10.0, (-59 - rssi) / 20.0).toFloat().coerceIn(0.5f, 50f)
            .let { Math.round(it * 10) / 10f }

    private fun distLabel(rssi: Int) = when {
        rssi > -60 -> "Very close <2m"
        rssi > -67 -> "Close ~3-5m"
        rssi > -75 -> "Nearby ~5-10m"
        else       -> "At limit ~15m"
    }

    // ── Alarm ─────────────────────────────────────────────────────────────────
    private fun playAlarm(risk: RiskLevel) {
        try {
            val uri = RingtoneManager.getDefaultUri(
                if (risk == RiskLevel.HIGH) RingtoneManager.TYPE_ALARM
                else RingtoneManager.TYPE_NOTIFICATION
            )
            val r = RingtoneManager.getRingtone(applicationContext, uri)
            r?.play()
            handler.postDelayed({ try { r?.stop() } catch (e: Exception) {} }, 3000)
        } catch (e: Exception) {}
    }

    private fun vibrate(risk: RiskLevel) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val p = when (risk) {
            RiskLevel.HIGH   -> longArrayOf(0, 300, 100, 300, 100, 500)
            RiskLevel.MEDIUM -> longArrayOf(0, 400, 150, 400)
            RiskLevel.LOW    -> longArrayOf(0, 250)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            v.vibrate(VibrationEffect.createWaveform(p, -1))
        else @Suppress("DEPRECATION") v.vibrate(p, -1)
    }

    // ── Notifications ─────────────────────────────────────────────────────────
    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL, "VR Shield Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                        enableLights(true)
                        lightColor = 0xFFE24B4A.toInt()
                        enableVibration(true)
                    }
                )
        }
    }

    private fun sendNotif(d: VRDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cat = if (d.category == DeviceCategory.SMART_GLASSES) "Smart glasses" else "VR headset"
        NotificationManagerCompat.from(this).notify(
            System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("⚠ $cat Detected!")
                .setContentText("${d.vendor} · ~${d.distance}m · ${d.risk.label}")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("${d.vendor} · ~${d.distance}m\n${d.risk.label} risk\n${d.note}"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi).setAutoCancel(true)
                .setColor(0xFFE24B4A.toInt()).build()
        )
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_REQ)
    }

    // ── Settings ──────────────────────────────────────────────────────────────
    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        rssiThreshold = prefs.getInt("rssi_threshold", -75)
        notificationCooldownMs = prefs.getLong("cooldown_ms", 10_000L)
    }

    private fun showSettings() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }
        val rssiLabel = TextView(this).apply {
            text = "RSSI Threshold (default: -75)\nCloser to 0 = longer range, more false positives"
            textSize = 12f; setTextColor(0xFF6B7280.toInt()); setPadding(0, 0, 0, 8)
        }
        val rssiInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(rssiThreshold.toString()); hint = "-75"
        }
        val cooldownLabel = TextView(this).apply {
            text = "Notification cooldown ms (default: 10000)\nMinimum time between alerts"
            textSize = 12f; setTextColor(0xFF6B7280.toInt()); setPadding(0, 16, 0, 8)
        }
        val cooldownInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(notificationCooldownMs.toString()); hint = "10000"
        }
        dialogView.addView(rssiLabel)
        dialogView.addView(rssiInput)
        dialogView.addView(cooldownLabel)
        dialogView.addView(cooldownInput)

        AlertDialog.Builder(this)
            .setTitle("⚙ Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newRssi = rssiInput.text.toString().toIntOrNull()?.coerceIn(-120, 0) ?: -75
                val newCooldown = cooldownInput.text.toString().toLongOrNull()?.coerceIn(0, 600_000) ?: 10_000L
                prefs.edit().putInt("rssi_threshold", newRssi).putLong("cooldown_ms", newCooldown).apply()
                rssiThreshold = newRssi
                notificationCooldownMs = newCooldown
                appendLog("Settings saved: RSSI=${newRssi}dBm · Cooldown=${newCooldown}ms")
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── History ───────────────────────────────────────────────────────────────
    private fun saveHistory() {
        val arr = JSONArray()
        history.take(100).forEach { d ->
            arr.put(JSONObject().apply {
                put("name", d.name); put("vendor", d.vendor); put("mac", d.mac)
                put("rssi", d.rssi); put("risk", d.risk.name); put("distance", d.distance)
                put("category", d.category.name); put("matchType", d.matchType)
                put("note", d.note); put("timestamp", d.timestamp)
            })
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(HIST_KEY, arr.toString()).apply()
    }

    private fun loadHistory() {
        try {
            val json = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(HIST_KEY, "[]") ?: "[]"
            val arr  = JSONArray(json)
            history.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                history.add(VRDevice(
                    o.getString("name"), o.getString("vendor"),
                    try { DeviceCategory.valueOf(o.getString("category")) } catch (e: Exception) { DeviceCategory.VR_HEADSET },
                    o.getString("mac"), o.getInt("rssi"),
                    RiskLevel.valueOf(o.getString("risk")),
                    o.getDouble("distance").toFloat(),
                    listOf(), o.getString("matchType"),
                    o.optString("note", ""), o.getLong("timestamp")
                ))
            }
        } catch (e: Exception) {
            Log.e("VRShield", "History load error: ${e.message}")
        }
    }

    private fun showHistory() {
        if (history.isEmpty()) { alert("History", "No detections yet."); return }
        val sdf   = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        val lines = history.take(50).mapIndexed { i, d ->
            val cat = if (d.category == DeviceCategory.SMART_GLASSES) "Glasses" else "VR"
            "${i+1}. [$cat] ${d.vendor}  [${d.risk.label}]\n    ~${d.distance}m · ${sdf.format(Date(d.timestamp))}"
        }.joinToString("\n\n")
        val sv = ScrollView(this)
        val tv = TextView(this).apply {
            text = lines; textSize = 13f; setPadding(48, 32, 48, 32)
            setTextColor(0xFFE8EAF0.toInt()); setBackgroundColor(0xFF0A0D14.toInt())
        }
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("Detection History (${history.size})")
            .setView(sv)
            .setPositiveButton("OK", null)
            .setNegativeButton("Clear") { _, _ ->
                history.clear(); saveHistory()
                appendLog("History cleared.")
            }.show()
    }

    // ── List helpers ──────────────────────────────────────────────────────────
    private fun refreshVRList() {
        vrListAdapter.clear()
        vrDevices.values
            .sortedWith(compareByDescending<VRDevice> { it.category == DeviceCategory.SMART_GLASSES }
                .thenByDescending { it.risk.ordinal }
                .thenBy { it.distance })  // closer devices shown first within same risk
            .forEach { d ->
                val cat      = if (d.category == DeviceCategory.SMART_GLASSES) "GLASSES" else "VR"
                vrListAdapter.add("[$cat · ${d.risk.label.uppercase()}]  ${d.vendor}\n~${d.distance}m · ${d.rssi}dBm")
            }
        vrListAdapter.notifyDataSetChanged()
    }

    private fun refreshAllList() {
        allListAdapter.clear()
        deviceGroups.values.sortedByDescending { it.bestDevice().rssi }.forEach { g ->
            val best = g.bestDevice()
            val tag  = if (g.devices.size > 1) " (${g.devices.size})" else ""
            allListAdapter.add("${g.name}$tag · ${best.distLabel} · ${best.rssi}dBm")
        }
        allListAdapter.notifyDataSetChanged()
    }

    private fun resizeList(lv: ListView, count: Int) {
        val rowH = (68 * resources.displayMetrics.density).toInt()
        val minH = (100 * resources.displayMetrics.density).toInt()
        val maxH = (300 * resources.displayMetrics.density).toInt()
        lv.layoutParams.height = if (count == 0) minH else (count * rowH).coerceIn(minH, maxH)
        lv.requestLayout()
    }

    // ── Detail dialogs ────────────────────────────────────────────────────────
    private fun showVRDetail(d: VRDevice) {
        val t        = SimpleDateFormat("HH:mm:ss  dd MMM yyyy", Locale.getDefault()).format(Date(d.timestamp))
        val cat      = if (d.category == DeviceCategory.SMART_GLASSES) "Smart Glasses" else "VR Headset"
        val ids      = if (d.companyIds.isEmpty()) "None detected"
                       else d.companyIds.map { "0x${it.toString(16).uppercase().padStart(4,'0')}" }.joinToString(", ")
        alert("⚠ $cat Detected", """
            Vendor:      ${d.vendor}
            Category:    $cat
            Risk:        ${d.risk.label}
            MAC:         ${d.mac}
            Signal:      ${d.rssi} dBm
            Distance:    ~${d.distance}m (${distLabel(d.rssi)})
            Company IDs: $ids
            Detected:    $t
            Match via:   ${d.matchType}

            Note: ${d.note}
        """.trimIndent())
    }

    private fun showGroupDetail(g: DeviceGroup) {
        val macList = g.devices.values.mapIndexed { i, d ->
            val ids = if (d.companyIds.isEmpty()) ""
                      else " IDs:${d.companyIds.map { "0x${it.toString(16).uppercase().padStart(4,'0')}" }}"
            "  ${i+1}. ${d.mac} · ${d.rssi}dBm · ${d.distLabel}$ids"
        }.joinToString("\n")
        alert(g.name, """
            Name:    ${g.name}
            Devices: ${g.devices.size}${if (g.devices.size > 1) " (e.g. left/right earbud + case)" else ""}
            Status:  Not a known recording device

$macList
        """.trimIndent())
    }

    // ── UI helpers ────────────────────────────────────────────────────────────
    private fun clearSession() {
        vrListAdapter.clear(); allListAdapter.clear()
        scanCycles = 0
        updateMetrics(); updateHeaders()
        setStatus(ScanStatus.IDLE, "Ready to scan", "Bluetooth enabled automatically on scan")
        appendLog("Session cleared.")
    }

    private fun updateHeaders() {
        tvVRHeader.text  = "SMART GLASSES & VR DETECTED (${vrDevices.size})"
        tvAllHeader.text = "ALL BLUETOOTH (${deviceGroups.size} groups · ${allDevices.size} total)"
    }

    private fun updateMetrics() {
        tvMetricDetected.text = vrDevices.size.toString()
        tvMetricHigh.text     = vrDevices.values.count { it.risk == RiskLevel.HIGH }.toString()
        tvMetricCycles.text   = scanCycles.toString()
        tvMetricBT.text       = deviceGroups.size.toString()
    }

    private fun setStatus(s: ScanStatus, text: String, sub: String) {
        tvStatusText.text = text
        tvStatusSub.text  = sub
        viewStatusDot.setBackgroundColor(when (s) {
            ScanStatus.IDLE     -> 0xFF5F5E5A.toInt()
            ScanStatus.SCANNING -> 0xFF1DCA8A.toInt()
            ScanStatus.THREAT   -> 0xFFE24B4A.toInt()
        })
    }

    private fun alert(title: String, msg: String) =
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()

    // ── Permissions ───────────────────────────────────────────────────────────
    private fun hasPermissions() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    else ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() = ActivityCompat.requestPermissions(this,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERM_CODE)

    override fun onRequestPermissionsResult(rc: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        when (rc) {
            PERM_CODE  -> if (results.all { it == PackageManager.PERMISSION_GRANTED }) checkBTAndScan()
                          else alert("Permission Required", "Bluetooth permission needed to scan.")
            NOTIF_REQ  -> appendLog(if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                              "Notifications granted." else "Notifications denied.")
        }
    }
}
