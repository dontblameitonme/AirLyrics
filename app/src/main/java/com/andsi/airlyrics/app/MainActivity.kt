package com.andsi.airlyrics.app

import android.content.ComponentName
import android.os.Bundle
import android.service.notification.NotificationListenerService
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.andsi.airlyrics.app.platform.PermissionHelper
import com.andsi.airlyrics.app.viewmodel.MainViewModel
import com.andsi.airlyrics.media.MediaNotificationListenerService

class MainActivity : AppCompatActivity() {
    internal val mainViewModel: MainViewModel by viewModels {
        MainViewModel.factory(applicationContext)
    }
    internal lateinit var graph: MainGraph
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = MainGraph(this, mainViewModel)
        graph.onCreate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::graph.isInitialized) graph.uiHost.interactionUi.capture()
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        rebindNotificationListenerIfNeeded()
        graph.onStart()
    }

    override fun onResume() {
        super.onResume()
        graph.onResume()
    }

    override fun onStop() {
        if (::graph.isInitialized) {
            graph.onStop()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::graph.isInitialized) {
            graph.onDestroy()
        }
        super.onDestroy()
    }

    private fun rebindNotificationListenerIfNeeded() {
        if (PermissionHelper.hasNotificationListenerAccess(this)) {
            val component = ComponentName(this, MediaNotificationListenerService::class.java)
            runCatching {
                NotificationListenerService.requestRebind(component)
            }
        }
    }
}
