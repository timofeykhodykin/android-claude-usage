package dev.claudeusage

import android.app.Application
import android.util.Log
import dev.claudeusage.auth.SignInFlow
import dev.claudeusage.auth.TokenManager
import dev.claudeusage.data.Settings
import dev.claudeusage.data.UsageRepository
import dev.claudeusage.widget.Widgets
import dev.claudeusage.work.Notifier
import dev.claudeusage.work.Scheduler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The app's singletons. Widgets, workers and the UI all run in the same process and share these. */
object Graph {
    lateinit var app: Application
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        Log.e("Graph", "Uncaught in app scope", e)
    })

    val settings: Settings by lazy { Settings(app) }
    val tokens: TokenManager by lazy { TokenManager(app) }

    val repository: UsageRepository by lazy {
        UsageRepository(app, tokens).also { repo ->
            repo.onChanged = { previous, current ->
                Widgets.updateAll(app)
                Notifier.check(app, previous, current)
                Scheduler.scheduleRedraw(app, current)
            }
        }
    }

    val signIn: SignInFlow by lazy {
        SignInFlow(app, tokens, appScope) { sameAccount ->
            // Another account: drop anything left from the previous one. Then fetch.
            if (!sameAccount) repository.clear()
            Scheduler.ensure(app)
            repository.refresh(maxAge = 0, manual = true)
        }
    }

    fun init(application: Application) {
        app = application
    }

    fun signOut() {
        appScope.launch {
            tokens.signOut()
            repository.clear()
            Scheduler.ensure(app)
        }
    }
}
