package com.musichub.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/** Тёмная системная рамка окна Windows 10/11 — чтобы светлый заголовок не спорил с тёмной темой. */
object WindowsTitleBar {

    @Suppress("FunctionName")
    private interface Dwmapi : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): WinNT.HRESULT
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20

    fun useDarkMode(window: Window) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        runCatching {
            val dwm = Native.load("dwmapi", Dwmapi::class.java)
            val hwnd = WinDef.HWND(Pointer(Native.getWindowID(window)))
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, IntByReference(1), 4)
        }
    }
}
