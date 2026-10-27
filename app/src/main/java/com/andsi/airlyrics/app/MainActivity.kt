package com.andsi.airlyrics.app

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.andsi.airlyrics.app.viewmodel.MainViewModel
import com.andsi.airlyrics.media.NotificationListenerDemand

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
        runCatching {
            startService(android.content.Intent(this, TaskRemovalWatcherService::class.java))
        }
        graph.onStart()
    }

    override fun onResume() {
        super.onResume()
        // Covers returning from the system notification-access screen after granting access.
        NotificationListenerDemand.requestRebindIfNeeded(this)
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

}
