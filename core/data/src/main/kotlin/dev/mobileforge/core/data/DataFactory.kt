package dev.mobileforge.core.data

import android.content.Context
import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.Logger
import dev.mobileforge.core.data.database.MobileForgeDatabase
import dev.mobileforge.core.data.secure.KeystoreSecretStore
import dev.mobileforge.core.data.secure.SecretStore
import dev.mobileforge.core.data.settings.DataStoreSettingsRepository
import dev.mobileforge.core.data.settings.SettingsRepository
import dev.mobileforge.core.data.workspace.RoomWorkspaceRepository
import dev.mobileforge.core.data.workspace.WorkspaceRepository
import java.io.File

/**
 * Constructs the persistence layer.
 *
 * This exists so that Room never becomes a compile dependency of `:app`. Without it, the
 * composition root has to name `MobileForgeDatabase`, which drags `RoomDatabase` onto its
 * classpath and quietly couples the application shell to the storage engine — the exact
 * coupling ARCHITECTURE.md forbids.
 *
 * Everything returned here is an interface. Swapping Room for something else is a change
 * inside this module.
 */
object DataFactory {

    fun createWorkspaceRepository(
        context: Context,
        workspacesRoot: File,
        dispatchers: AppDispatchers,
        logger: Logger,
    ): WorkspaceRepository = RoomWorkspaceRepository(
        dao = MobileForgeDatabase.create(context).workspaceDao(),
        workspacesRoot = workspacesRoot,
        dispatchers = dispatchers,
        logger = logger,
    )

    fun createSettingsRepository(context: Context): SettingsRepository =
        DataStoreSettingsRepository(context)

    fun createSecretStore(context: Context): SecretStore = KeystoreSecretStore(context)
}
