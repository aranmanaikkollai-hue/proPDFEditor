package com.propdf.security.domain.usecase

import android.net.Uri
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.model.EncryptedBackupConfig
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.usecase.UseCase
import com.propdf.security.backup.EncryptedBackupEngine
import java.io.File
import javax.inject.Inject

class CreateEncryptedBackupUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val backupEngine: EncryptedBackupEngine
) : UseCase<CreateEncryptedBackupUseCase.Params, Long>(dispatchers) {

    data class Params(
        val sources: List<File>,
        val outputUri: Uri,
        val config: EncryptedBackupConfig
    )

    override suspend fun execute(params: Params): Long {
        return when (val result = backupEngine.createEncryptedBackup(params.sources, params.outputUri, params.config)) {
            is AppResult.Success -> result.data
            is AppResult.Error -> throw result.exception
            is AppResult.Loading -> throw IllegalStateException("Unexpected loading state")
        }
    }
}

class RestoreEncryptedBackupUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val backupEngine: EncryptedBackupEngine
) : UseCase<RestoreEncryptedBackupUseCase.Params, List<String>>(dispatchers) {

    data class Params(
        val backupUri: Uri,
        val destinationDir: File,
        val password: CharArray
    )

    override suspend fun execute(params: Params): List<String> {
        return when (val result = backupEngine.restoreEncryptedBackup(params.backupUri, params.destinationDir, params.password)) {
            is AppResult.Success -> result.data
            is AppResult.Error -> throw result.exception
            is AppResult.Loading -> throw IllegalStateException("Unexpected loading state")
        }
    }
}
