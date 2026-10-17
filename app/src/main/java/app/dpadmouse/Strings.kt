package app.dpadmouse

/**
 * All user-facing text for the main UI, in English and Vietnamese.
 * Set [lang] once (from [Prefs.language]) before building the UI; every
 * property below reflects the current value of [lang].
 */
object Strings {
    class Language(val code: String, val name: String)

    /** Selectable UI languages (each name is shown in its own language). */
    val languages = listOf(
        Language("en", "English"),
        Language("vi", "Tiếng Việt")
    )

    /** Language code, one of [languages]. */
    var lang: String = "en"

    private fun t(en: String, vi: String): String = if (lang == "vi") vi else en

    val appTitle get() = "Dpad Mouse"

    // --- Language selection
    val languageRowName get() = t("Language", "Ngôn ngữ")
    val languageButtonLabel get() = t("Language: ", "Ngôn ngữ: ") + (languages.firstOrNull { it.code == lang }?.name ?: "")

    // --- Common buttons
    val okBtn get() = t("OK", "OK")
    val cancelBtn get() = t("Cancel", "Huỷ")

    // --- Virtual mouse section
    val sectionVirtualMouse get() = t("Virtual mouse", "Chuột ảo")
    val mouseModeRow get() = t("Mouse mode", "Chế độ chuột")
    val subtitleOn get() = t("Mouse mode is ON", "Chế độ chuột đang BẬT")
    val subtitleOff get() = t("Cursor moves with controller keys", "Con trỏ chuột bằng phím điều khiển")

    // --- Local ADB section
    val sectionLocalAdb get() = t("Local ADB", "ADB cục bộ")
    val statusRowName get() = t("Status", "Trạng thái")
    val addressRowName get() = t("Address", "Địa chỉ")
    val addressRowDesc get() = t("Select to edit. Default 127.0.0.1", "Chọn để sửa. Mặc định 127.0.0.1")
    val portRowName get() = t("Port", "Cổng")
    val portRowDesc get() = t("Select to edit. Enable with: adb tcpip 5555", "Chọn để sửa. Bật bằng: adb tcpip 5555")
    val invalidPortToast get() = t("Port must be 1-65535", "Cổng phải từ 1-65535")
    val connectBtn get() = t("Connect", "Kết nối")
    val disconnectBtn get() = t("Disconnect", "Ngắt kết nối")

    // --- Control section
    val sectionControl get() = t("Control", "Điều khiển")
    val sensitivityRowName get() = t("Sensitivity", "Độ nhạy")
    fun sensitivityDesc(v: Int) = t("$v / 100  -  press Left / Right to adjust", "$v / 100  -  bấm Trái / Phải để chỉnh")
    val mapKeysRowName get() = t("Map keys", "Gán phím")

    // --- Accessibility section
    val sectionAccessibility get() = t("Accessibility", "Trợ năng")
    val a11yServiceRowName get() = t("Accessibility service", "Dịch vụ trợ năng")

    // --- Debug section
    val sectionDebug get() = t("Debug", "Gỡ lỗi")
    fun storageRowName(dir: String) = t("Write access $dir", "Quyền ghi $dir")
    val nudgeRightRowName get() = t("Nudge cursor right", "Nhích chuột sang phải")
    val nudgeRightRowDesc get() = t("Try sending an HID report, no accessibility needed", "Thử gửi report HID, không cần trợ năng")
    val nudgeDownRowName get() = t("Nudge cursor down", "Nhích chuột xuống")
    val nudgeDownRowDesc get() = t("Test the vertical axis", "Thử trục dọc")
    val testClickRowName get() = t("Test click", "Click thử")
    val testClickRowDesc get() = t("Left button press and release", "Nút trái nhấn rồi nhả")
    val diagnoseRowName get() = t("Diagnose environment", "Chẩn đoán môi trường")
    val diagnoseRowDesc get() = t("uid, Android version, hid command", "uid, phiên bản Android, lệnh hid")
    val openLogRowName get() = t("Open log", "Mở log")
    val openLogRowDesc get() = t("View, copy or save the debug log", "Xem, sao chép hoặc lưu log gỡ lỗi")

    // --- Toasts / status
    val notConnectedToast get() = t("Not connected", "Chưa kết nối")
    val masterDescOn get() = t("On: arrows move, OK clicks", "Đang bật: mũi tên di chuyển, OK để click")
    val masterDescConnectedOff get() = t("Virtual mouse created, turning off", "Đã tạo chuột ảo, đang tắt")
    val masterDescConnecting get() = t("Connecting…", "Đang kết nối…")
    val masterDescDefault get() = t("Turning on connects ADB automatically", "Bật sẽ tự kết nối ADB")
    val statusDisconnected get() = t("Not connected", "Chưa kết nối")
    val statusConnecting get() = t("Connecting…", "Đang kết nối…")
    val statusConnected get() = t("Connected, virtual mouse active", "Đã kết nối, chuột ảo đang hoạt động")
    fun errorSuffix(msg: String) = t("\nError: $msg", "\nLỗi: $msg")
    val a11yRunning get() = t("Running", "Đang chạy")
    val a11yNotEnabled get() = t("Not enabled (turned on automatically with mouse mode)", "Chưa bật (tự bật cùng chế độ chuột)")
    fun toggleDesc(keyName: String) = t("On/off key: $keyName", "Bật/tắt: $keyName")
    val storageGranted get() = t("Granted", "Đã cấp")
    val storageNotGranted get() = t("Not granted, select to request", "Chưa cấp, chọn để xin quyền")
    val diagnosePrefix get() = t("Diagnosis:\n", "Chẩn đoán:\n")
    val diagnoseErrorPrefix get() = t("ERROR: ", "LỖI: ")

    // --- Map keys page
    val mapKeysTitle get() = t("Map keys", "Gán phím")
    val slotToggleTitle get() = t("Mouse on/off", "Bật / tắt chuột")
    val slotToggleDesc get() = t("Always captured, even while the mouse is off", "Luôn được bắt, kể cả khi chuột đang tắt")
    val slotLeftClickTitle get() = t("Left click", "Click trái")
    val slotLeftClickDesc get() = t("Hold to drag", "Giữ để kéo thả")
    val slotRightClickTitle get() = t("Right click", "Click phải")
    val slotRightClickDesc get() = t("Leave unset: the Back key still works normally", "Để trống: phím Back vẫn hoạt động bình thường")
    val slotScrollUpTitle get() = t("Scroll up", "Cuộn lên")
    val slotScrollUpDesc get() = t("Hold to scroll continuously", "Giữ để cuộn liên tục")
    val slotScrollDownTitle get() = t("Scroll down", "Cuộn xuống")
    val slotScrollDownDesc get() = t("Hold to scroll continuously", "Giữ để cuộn liên tục")
    val slotScrollLeftTitle get() = t("Scroll left", "Cuộn trái")
    val slotScrollLeftDesc get() = t("Horizontal scroll. Unset by default", "Cuộn ngang. Mặc định để trống")
    val slotScrollRightTitle get() = t("Scroll right", "Cuộn phải")
    val slotScrollRightDesc get() = t("Horizontal scroll. Unset by default", "Cuộn ngang. Mặc định để trống")
    val keysPageNote get() = t(
        "Select an item, then press a key on the controller (Back cancels). Hold OK on an item to clear it. " +
            "Arrows always move the cursor while the mouse is on.",
        "Chọn một mục rồi bấm phím trên điều khiển (Back để huỷ). Giữ OK trên mục để xoá phím. " +
            "Mũi tên luôn dùng để di chuyển khi chuột đang bật."
    )
    val resetDefaultsBtn get() = t("Restore defaults", "Khôi phục mặc định")
    fun keyClearedToast(title: String) = t("$title: key cleared", "$title: đã xoá phím")

    // --- Learn key dialog
    val learnMessage get() = t(
        "Press the key you want to use on the controller.\nPress Back to cancel.",
        "Bấm phím muốn dùng trên điều khiển.\nBấm Back để huỷ."
    )
    val dontUseKeyBtn get() = t("Don't use a key", "Không dùng phím này")
    val notUsedKeyName get() = t("Not used", "Không dùng")

    // --- Log page (debug)
    val logTitle get() = t("Log", "Log")
    val logSavedPrefix get() = t("Log saved: ", "Đã ghi log: ")
    val logCopiedToast get() = t("Log copied", "Đã sao chép log")
}
