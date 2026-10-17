package app.dpadmouse.hid

/**
 * Virtual HID mouse, fed into Android's `hid -` command (JSON, one object per line).
 * 5-byte input report: [buttons, dx, dy, wheel, pan] (pan = horizontal scroll).
 */
object HidMouse {
    const val DEVICE_ID = 1

    const val BTN_LEFT = 1
    const val BTN_RIGHT = 2
    const val BTN_MIDDLE = 4

    /** HID descriptor: 3 buttons + 5 padding bits, signed 8-bit relative X/Y/Wheel, plus AC Pan (horizontal scroll). */
    private val DESCRIPTOR = intArrayOf(
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x02,       // Usage (Mouse)
        0xA1, 0x01,       // Collection (Application)
        0x09, 0x01,       //   Usage (Pointer)
        0xA1, 0x00,       //   Collection (Physical)
        0x05, 0x09,       //     Usage Page (Button)
        0x19, 0x01,       //     Usage Minimum (1)
        0x29, 0x03,       //     Usage Maximum (3)
        0x15, 0x00,       //     Logical Minimum (0)
        0x25, 0x01,       //     Logical Maximum (1)
        0x95, 0x03,       //     Report Count (3)
        0x75, 0x01,       //     Report Size (1)
        0x81, 0x02,       //     Input (Data, Var, Abs)
        0x95, 0x01,       //     Report Count (1)
        0x75, 0x05,       //     Report Size (5)
        0x81, 0x01,       //     Input (Const)  -- padding
        0x05, 0x01,       //     Usage Page (Generic Desktop)
        0x09, 0x30,       //     Usage (X)
        0x09, 0x31,       //     Usage (Y)
        0x09, 0x38,       //     Usage (Wheel)
        0x15, 0x81,       //     Logical Minimum (-127)
        0x25, 0x7F,       //     Logical Maximum (127)
        0x75, 0x08,       //     Report Size (8)
        0x95, 0x03,       //     Report Count (3)
        0x81, 0x06,       //     Input (Data, Var, Rel)
        0x05, 0x0C,       //     Usage Page (Consumer)
        0x0A, 0x38, 0x02, //     Usage (AC Pan) -- horizontal scroll wheel
        0x95, 0x01,       //     Report Count (1)
        0x81, 0x06,       //     Input (Data, Var, Rel)  (still -127..127, 8 bits)
        0xC0,             //   End Collection
        0xC0              // End Collection
    )

    fun registerJson(): String =
        "{\"id\":$DEVICE_ID,\"command\":\"register\",\"name\":\"DpadMouse Virtual Mouse\"," +
            "\"bus\":\"usb\",\"descriptor\":[${DESCRIPTOR.joinToString(",")}]}"

    /**
     * dx, dy, wheel, pan in -127..127; negative numbers are encoded as unsigned bytes (0..255).
     * wheel: +1 = scroll up. pan: +1 = scroll right.
     */
    fun reportJson(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int = 0): String =
        "{\"id\":$DEVICE_ID,\"command\":\"report\",\"report\":" +
            "[${buttons and 0xFF},${dx and 0xFF},${dy and 0xFF},${wheel and 0xFF},${pan and 0xFF}]}"
}
