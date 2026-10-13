package com.arceusvault.keel

import android.app.Application
import com.arceusvault.keel.core.security.KeystoreKeyProvider
import com.arceusvault.keel.core.security.Vault
import com.arceusvault.keel.data.AlertWatch
import com.arceusvault.keel.data.Connector
import com.arceusvault.keel.data.SessionManager
import com.arceusvault.keel.data.Settings
import com.arceusvault.keel.ui.Operations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.io.File

/** Manual DI: few enough objects that a framework would only add attack surface. */
class AppContainer(app: Application) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val keyProvider = KeystoreKeyProvider(app)
    val vault = Vault.servers(File(app.noBackupFilesDir, "vault.bin"), keyProvider, json)
    val connector = Connector(json)
    val settings = Settings(app)
    val session = SessionManager(vault, connector, json, scope, settings)
    val operations = Operations(scope)
    val alertWatch = AlertWatch(app, json, connector).also { watch ->
        session.onProfileChanged = watch::updateProfile
        session.onProfileRemoved = watch::serverRemoved
    }
}

class KeelApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
