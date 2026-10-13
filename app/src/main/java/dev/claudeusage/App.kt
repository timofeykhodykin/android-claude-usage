package dev.claudeusage

import android.app.Application
import dev.claudeusage.widget.Widgets
import dev.claudeusage.work.Notifier
import dev.claudeusage.work.Scheduler
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifier.createChannel(this)
        Graph.appScope.launch {
            Scheduler.ensure(this@App)
            Scheduler.scheduleRedraw(this@App, Graph.repository.state.value)
            Widgets.publishPreviews(this@App)
        }
    }
}
