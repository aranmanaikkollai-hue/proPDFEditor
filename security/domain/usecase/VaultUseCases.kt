package com.propdf.security.domain.usecase

import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.model.VaultEntry
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.usecase.UseCase
import com.propdf.security.vault.VaultEncryptionEngine
import java.io.File
import javax.inject.Inject

// ── EncryptToVaultUseCase ─────────────────────────────────────────────────

class EncryptToVaultUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val vaultEngine: VaultEncryptionEngine
) : UseCase<EncryptToVaultUseCase.Params, VaultEntry>(dispatchers) {

    data class Params(
        val sourceFile: File,
        val useBiometricKey: Boolean = false
    )

    override suspend fun execute(params: Params): VaultEntry {
        return when (val result = vaultEngine.encryptToVault(params.sourceFile, params.useBiometricKey)) {
            is AppResult.Success -> result.data
            is AppResult.Error -> throw result.exception
            else -> throw IllegalStateException("Unexpected result")
        }
    }
}

// ── DecryptFromVaultUseCase ───────────────────────────────────────────────

class DecryptFromVaultUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val vaultEngine: VaultEncryptionEngine
) : UseCase<DecryptFromVaultUseCase.Params, File>(dispatchers) {

    data class Params(
        val entry: VaultEntry,
        val useBiometricKey: Boolean = false
    )

    override suspend fun execute(params: Params): File {
        return when (val result = vaultEngine.decryptFromVault(params.entry, params.useBiometricKey)) {
            is AppResult.Success -> result.data
            is AppResult.Error -> throw result.exception
            else -> throw IllegalStateException("Unexpected result")
        }
    }
}

// ── DeleteVaultEntryUseCase ───────────────────────────────────────────────

class DeleteVaultEntryUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val vaultEngine: VaultEncryptionEngine
) : UseCase<VaultEntry, Unit>(dispatchers) {

    override suspend fun execute(params: VaultEntry) {
        when (val result = vaultEngine.deleteVaultEntry(params)) {
            is AppResult.Success -> Unit
            is AppResult.Error -> throw result.exception
            else -> throw IllegalStateException("Unexpected result")
        }
    }
}

// ── ExportFromVaultUseCase ────────────────────────────────────────────────

class ExportFromVaultUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val vaultEngine: VaultEncryptionEngine
) : UseCase<ExportFromVaultUseCase.Params, Unit>(dispatchers) {

    data class Params(
        val entry: VaultEntry,
        val destinationFile: File,
        val useBiometricKey: Boolean = false
    )

    override suspend fun execute(params: Params) {
        when (val result = vaultEngine.exportFromVault(params.entry, params.destinationFile, params.useBiometricKey)) {
            is AppResult.Success -> Unit
            is AppResult.Error -> throw result.exception
            else -> throw IllegalStateException("Unexpected result")
        }
    }
}
