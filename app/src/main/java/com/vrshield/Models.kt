package com.vrshield

// ─── Enums ───────────────────────────────────────────────────────────────────

enum class RiskLevel(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }
enum class ScanStatus { IDLE, SCANNING, THREAT }
enum class DeviceCategory { SMART_GLASSES, VR_HEADSET }

// ─── Data models ─────────────────────────────────────────────────────────────

data class DeviceSignature(
    val vendor: String,
    val category: DeviceCategory,
    val risk: RiskLevel,
    val companyIds: List<Int>,       // BT SIG company IDs — immutable in BLE ADV
    val auxCompanyIds: List<Int>,    // Supporting company IDs
    val nameKeywords: List<String>,  // Device name (only visible during pairing)
    val macPrefixes: List<String>,   // MAC OUI — randomized on modern devices, low confidence
    val note: String,
    // CONFIDENCE RULES — prevents false positives from shared company IDs
    val requireNameForCompanyIdMatch: Boolean = false, // If true, company ID alone is NOT enough
    val requireBothIdAndName: Boolean = false          // If true, MUST have BOTH company ID + name
)

data class VRDevice(
    val name: String,
    val vendor: String,
    val category: DeviceCategory,
    val mac: String,
    var rssi: Int,
    val risk: RiskLevel,
    var distance: Float,
    val companyIds: List<Int>,
    val matchType: String,
    val note: String,
    val timestamp: Long
)

data class BTDevice(
    val name: String,
    val mac: String,
    val rssi: Int,
    val distance: Float,
    val distLabel: String,
    val companyIds: List<Int>
)

class DeviceGroup(val name: String) {
    val devices = mutableMapOf<String, BTDevice>()
    fun addDevice(d: BTDevice) { devices[d.mac] = d }
    fun bestDevice(): BTDevice = devices.values.maxByOrNull { it.rssi } ?: devices.values.first()
}

// ─── Signature database ──────────────────────────────────────────────────────
//
// FALSE POSITIVE RULES per company ID:
//
// 0x004C = Apple Inc — used by ALL Apple devices (iPhone, iPad, AirPods, Watch, MacBook)
//          NEVER match on company ID alone. MUST have name keyword.
//
// 0x01AB = Meta Platforms Inc — used by Quest AND Ray-Ban AND Portal
// 0x058E = Meta Platforms Technologies — similar
//          These are more specific to VR/glasses but still need caution.
//          Match on ID alone only if no other Apple/common device could explain it.
//
// 0x0006 = Microsoft — used by Xbox controllers, Surface, HoloLens
//          Do NOT match on ID alone. MUST have name keyword.
//
// 0x0059 = Nordic Semiconductor — used in many IoT devices, not just Valve Index
//          Do NOT match on ID alone. MUST have name keyword.
//
// 0x0D53 = Luxottica — ONLY used in Ray-Ban products. Safe to match alone.
// 0x03C2 = Snapchat — ONLY used in Spectacles. Safe to match alone.
// 0x0BA7 = Pico — relatively unique. Safe to match.
// 0x07D7 = TCL — used in multiple products, needs name confirmation.

val ALL_SIGNATURES = listOf(

    // ── SMART GLASSES ────────────────────────────────────────────────────────

    // Meta Ray-Ban: 0x0D53 (Luxottica) is unique to Ray-Ban products — safe standalone match
    // 0x01AB/0x058E alone could be any Meta product — require name OR Luxottica ID
    DeviceSignature(
        vendor        = "Meta Ray-Ban Glasses",
        category      = DeviceCategory.SMART_GLASSES,
        risk          = RiskLevel.HIGH,
        companyIds    = listOf(0x0D53),              // Luxottica — ONLY Ray-Ban uses this
        auxCompanyIds = listOf(0x01AB, 0x058E),      // Meta IDs as supporting evidence
        nameKeywords  = listOf("Ray-Ban", "RayBan", "Meta Glasses"),
        macPrefixes   = listOf(),
        note          = "7M+ sold in 2025. Used for covert recording. Facial recognition launching.",
        requireNameForCompanyIdMatch = false          // 0x0D53 alone is enough — it's Luxottica only
    ),

    // Oakley Meta: same IDs as Ray-Ban
    DeviceSignature(
        vendor        = "Oakley Meta Glasses",
        category      = DeviceCategory.SMART_GLASSES,
        risk          = RiskLevel.HIGH,
        companyIds    = listOf(0x0D53),
        auxCompanyIds = listOf(0x01AB, 0x058E),
        nameKeywords  = listOf("Oakley Meta", "HSTN", "Vanguard"),
        macPrefixes   = listOf(),
        note          = "Meta x Oakley smart glasses with camera. Released Aug 2025.",
        requireNameForCompanyIdMatch = false
    ),

    // Snap Spectacles: 0x03C2 is ONLY Snapchat — safe standalone
    DeviceSignature(
        vendor        = "Snap Spectacles",
        category      = DeviceCategory.SMART_GLASSES,
        risk          = RiskLevel.HIGH,
        companyIds    = listOf(0x03C2),
        auxCompanyIds = listOf(),
        nameKeywords  = listOf("Spectacles", "Snap Glasses"),
        macPrefixes   = listOf(),
        note          = "Camera-equipped smart glasses by Snapchat.",
        requireNameForCompanyIdMatch = false
    ),

    // TCL RayNeo: 0x07D7 used in multiple TCL products — require name confirmation
    DeviceSignature(
        vendor        = "TCL RayNeo Glasses",
        category      = DeviceCategory.SMART_GLASSES,
        risk          = RiskLevel.HIGH,
        companyIds    = listOf(0x07D7),
        auxCompanyIds = listOf(),
        nameKeywords  = listOf("RayNeo", "NXTWEAR", "TCL Glasses"),
        macPrefixes   = listOf("AC:67:B2"),
        note          = "TCL smart glasses with camera.",
        requireNameForCompanyIdMatch = true           // 0x07D7 not unique enough alone
    ),

    // XREAL: no reliable company ID — name/MAC only
    DeviceSignature(
        vendor        = "XREAL Glasses",
        category      = DeviceCategory.SMART_GLASSES,
        risk          = RiskLevel.MEDIUM,
        companyIds    = listOf(),
        auxCompanyIds = listOf(),
        nameKeywords  = listOf("XREAL", "Nreal", "Air 2"),
        macPrefixes   = listOf("CC:1B:E0"),
        note          = "AR smart glasses.",
        requireNameForCompanyIdMatch = false
    ),

    // ── VR HEADSETS ──────────────────────────────────────────────────────────

    // Meta Quest: 0x01AB/0x058E shared with Ray-Ban — need name OR MAC prefix confirmation
    DeviceSignature(
        vendor        = "Meta Quest",
        category      = DeviceCategory.VR_HEADSET,
        risk          = RiskLevel.HIGH,
        companyIds    = listOf(0x01AB, 0x058E),
        auxCompanyIds = listOf(),
        nameKeywords  = listOf("Quest", "Oculus", "Meta Quest", "OculusHMD"),
        macPrefixes   = listOf("A4:C1:38", "94:E9:79", "04:D6:AA"),
        note          = "VR headset with passthrough cameras.",
        requireNameForCompanyIdMatch = true           // 0x01AB alone could be Ray-Ban too
    ),

    // Apple Vision Pro: 0x004C is ALL Apple devices — MUST have name match
    DeviceSignature(
        vendor        = "Apple Vision Pro",
        category      = DeviceCategory.VR_HEADSET,
        risk          = RiskLevel.HIGH,
        companyIds    = listOf(0x004C),
        auxCompanyIds = listOf(),
        nameKeywords  = listOf("Vision Pro", "AVP"),
        macPrefixes   = listOf(),
        note          = "Mixed reality headset with cameras.",
        requireNameForCompanyIdMatch = true,          // 0x004C is every Apple device!
        requireBothIdAndName = true                   // Extra strict — must have BOTH
    ),

    // Pico: 0x0BA7 is relatively unique
    DeviceSignature(
        vendor        = "Pico VR",
        category      = DeviceCategory.VR_HEADSET,
        risk          = RiskLevel.HIGH,
        companyIds    = listOf(0x0BA7),
        auxCompanyIds = listOf(),
        nameKeywords  = listOf("Pico 4", "Pico Neo", "PICO"),
        macPrefixes   = listOf("40:4E:36", "84:EF:18"),
        note          = "VR headset with cameras.",
        requireNameForCompanyIdMatch = false
    ),

    // HTC Vive: 0x00E0 is HTC specific
    DeviceSignature(
        vendor        = "HTC Vive",
        category      = DeviceCategory.VR_HEADSET,
        risk          = RiskLevel.MEDIUM,
        companyIds    = listOf(0x00E0),
        auxCompanyIds = listOf(),
        nameKeywords  = listOf("Vive", "HTC Vive", "Vive Pro"),
        macPrefixes   = listOf("AC:BC:32", "00:1B:F0"),
        note          = "PC VR headset.",
        requireNameForCompanyIdMatch = false
    ),

    // Sony PSVR: no reliable company ID
    DeviceSignature(
        vendor        = "Sony PSVR",
        category      = DeviceCategory.VR_HEADSET,
        risk          = RiskLevel.MEDIUM,
        companyIds    = listOf(),
        auxCompanyIds = listOf(),
        nameKeywords  = listOf("PSVR", "PlayStation VR", "PSVR2"),
        macPrefixes   = listOf("00:04:1F", "28:42:B0"),
        note          = "PlayStation VR headset.",
        requireNameForCompanyIdMatch = false
    ),

    // Valve Index: 0x0059 = Nordic Semiconductor, used in many IoT devices — require name
    DeviceSignature(
        vendor        = "Valve Index",
        category      = DeviceCategory.VR_HEADSET,
        risk          = RiskLevel.MEDIUM,
        companyIds    = listOf(0x0059),
        auxCompanyIds = listOf(),
        nameKeywords  = listOf("Index", "Valve Index", "SteamVR"),
        macPrefixes   = listOf("D0:73:D5", "EC:0E:C4"),
        note          = "PC VR headset.",
        requireNameForCompanyIdMatch = true           // Nordic ID too common
    ),

    // HoloLens: 0x0006 = Microsoft, used by Xbox controllers, Surface etc — require name
    DeviceSignature(
        vendor        = "Microsoft HoloLens",
        category      = DeviceCategory.VR_HEADSET,
        risk          = RiskLevel.MEDIUM,
        companyIds    = listOf(0x0006),
        auxCompanyIds = listOf(),
        nameKeywords  = listOf("HoloLens", "Mixed Reality", "WMR"),
        macPrefixes   = listOf("28:18:78", "60:45:BD"),
        note          = "Mixed reality headset.",
        requireNameForCompanyIdMatch = true           // Microsoft ID too common
    ),
)
