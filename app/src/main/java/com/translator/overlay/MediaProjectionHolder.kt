package com.translator.overlay

import android.content.Intent

object MediaProjectionHolder {
    @Volatile var resultCode: Int = -1
    @Volatile var data: Intent? = null

    fun clear() {
        resultCode = -1
        data = null
    }
}
