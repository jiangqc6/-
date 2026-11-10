package com.jqc.webplayer

import android.app.Application

class WebPlayerApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
