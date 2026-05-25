package com.vrshield

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class ScanService : Service() {

    private var bleScanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    private val seenMacs = mutableSetOf<String>()

    private val ONGOING = "vr_ongoing"
    private val ALERTS  = "vr_alerts"
    private val NOTIF_ID = 1
    private val RSSI_THRESHOLD = -75

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(t: Int, r: ScanResult) { process(r) }
        override fun onBatchScanResults(rs: MutableList<ScanResult>) { rs.forEach { process(it) } }
        override fun onScanFailed(e: Int) { Log.d("ScanService", "Failed: $e") }
    }

    override fun onCreate() { super.onCreate(); createChannels() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBgScan()
            ACTION_STOP  -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { super.onDestroy(); stopBLE() }

    private fun startBgScan() {
        startForeground(NOTIF_ID, buildOngoing("Scanning for smart glasses & VR headsets..."))
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) { updateOngoing("BT off — enable Bluetooth"); return }
        bleScanner = bt.bluetoothLeScanner
        val s = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).setReportDelay(0).build()
        try { bleScanner?.startScan(null, s, scanCallback) } catch (e: Exception) { Log.e("ScanService", e.message ?: "") }
    }

    private fun stopBLE() {
        try { if (hasPerms()) bleScanner?.stopScan(scanCallback) } catch (e: Exception) {}
    }

    private fun process(result: ScanResult) {
        val mac  = result.device.address ?: return
        val rssi = result.rssi
        if (rssi < RSSI_THRESHOLD || seenMacs.contains(mac)) return
        val name = try { if (hasPerms()) result.device.name ?: "" else "" } catch (e: SecurityException) { "" }
        val ids  = mutableListOf<Int>()
        result.scanRecord?.manufacturerSpecificData?.let { d -> for (i in 0 until d.size()) ids.add(d.keyAt(i)) }

        val sig = matchSig(name, mac, ids) ?: return

        seenMacs.add(mac)
        val dist = Math.pow(10.0, (-59 - rssi) / 20.0).toFloat().coerceIn(0.5f, 50f).let { Math.round(it * 10) / 10f }
        val cat  = if (sig.category == DeviceCategory.SMART_GLASSES) "Smart glasses" else "VR headset"

        saveHistory(name.ifEmpty { sig.vendor }, sig.vendor, mac, rssi, sig.risk, dist, sig.category.name)
        sendAlert(sig.vendor, cat, dist, sig.risk)
        updateOngoing("⚠ $cat: ${sig.vendor} ~${dist}m!")
        vibrate(sig.risk); playAlarm(sig.risk)
        Log.d("ScanService", "DETECTED: ${sig.vendor} [$cat] ${rssi}dBm ~${dist}m")
    }

    private fun matchSig(name: String, mac: String, ids: List<Int>): DeviceSignature? {
        for (sig in ALL_SIGNATURES) {
            val idMatch   = sig.companyIds.isNotEmpty()    && ids.any { it in sig.companyIds }
            val auxMatch  = sig.auxCompanyIds.isNotEmpty() && ids.any { it in sig.auxCompanyIds }
            val nameMatch = name.isNotEmpty() && sig.nameKeywords.any { name.contains(it, ignoreCase = true) }
            val macMatch  = sig.macPrefixes.isNotEmpty() && sig.macPrefixes.any { mac.startsWith(it, ignoreCase = true) }
            if (sig.requireBothIdAndName) { if (idMatch && nameMatch) return sig; continue }
            if (sig.requireNameForCompanyIdMatch) {
                if (nameMatch || macMatch || (idMatch && auxMatch)) return sig
                continue
            }
            if (idMatch || auxMatch || nameMatch || macMatch) return sig
        }
        return null
    }

        private fun saveHistory(name: String, vendor: String, mac: String, rssi: Int, risk: RiskLevel, dist: Float, category: String) {
        val prefs = getSharedPreferences("VRShieldPrefs", Context.MODE_PRIVATE)
        val arr   = try { JSONArray(prefs.getString("detection_history", "[]")) } catch (e: Exception) { JSONArray() }
        val new   = JSONArray().put(JSONObject().apply {
            put("name", name); put("vendor", vendor); put("mac", mac); put("rssi", rssi)
            put("risk", risk.name); put("distance", dist); put("category", category)
            put("matchType", "BG scan"); put("note", "Detected while app closed"); put("timestamp", System.currentTimeMillis())
        })
        for (i in 0 until minOf(arr.length(), 99)) new.put(arr.getJSONObject(i))
        prefs.edit().putString("detection_history", new.toString()).apply()
    }

    private fun sendAlert(vendor: String, cat: String, dist: Float, risk: RiskLevel) {
        if (!hasNotifPerm()) return
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(System.currentTimeMillis().toInt(),
                NotificationCompat.Builder(this, ALERTS)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("⚠ $cat Detected!")
                    .setContentText("$vendor · ~${dist}m · ${risk.label} risk")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pi).setAutoCancel(true).setColor(0xFFE24B4A.toInt()).build())
    }

    private fun buildOngoing(text: String): Notification {
        val pi    = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopI = PendingIntent.getService(this, 0, Intent(this, ScanService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, ONGOING)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("VR Shield — Background Scan")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true).setContentIntent(pi)
            .addAction(android.R.drawable.ic_delete, "Stop", stopI).build()
    }

    private fun updateOngoing(text: String) =
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildOngoing(text))

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(ONGOING, "VR Shield Scanning", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(ALERTS, "VR Shield Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableLights(true); lightColor = 0xFFE24B4A.toInt(); enableVibration(true)
            })
        }
    }

    private fun vibrate(risk: RiskLevel) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val p = when (risk) { RiskLevel.HIGH -> longArrayOf(0,300,100,300,100,500); RiskLevel.MEDIUM -> longArrayOf(0,400,150,400); RiskLevel.LOW -> longArrayOf(0,250) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createWaveform(p,-1))
        else @Suppress("DEPRECATION") v.vibrate(p,-1)
    }

    private fun playAlarm(risk: RiskLevel) {
        try {
            val uri = RingtoneManager.getDefaultUri(if (risk == RiskLevel.HIGH) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION)
            val r   = RingtoneManager.getRingtone(applicationContext, uri)
            r?.play(); handler.postDelayed({ try { r?.stop() } catch (e: Exception) {} }, 3000)
        } catch (e: Exception) {}
    }

    private fun hasPerms() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    else ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifPerm() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    else true

    companion object {
        const val ACTION_START = "com.vrshield.START"
        const val ACTION_STOP  = "com.vrshield.STOP"
    }
}
