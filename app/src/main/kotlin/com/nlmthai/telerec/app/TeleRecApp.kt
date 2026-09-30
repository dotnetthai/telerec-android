package com.nlmthai.telerec.app

import android.app.Application

class TeleRecApp : Application() {
    lateinit var model: AppModel
        private set

    override fun onCreate() {
        super.onCreate()
        model = AppModel(this)
        model.launch()
    }
}
