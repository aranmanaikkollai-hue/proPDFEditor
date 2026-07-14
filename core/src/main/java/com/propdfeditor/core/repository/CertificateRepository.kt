package com.propdfeditor.core.repository

import android.content.Context
import android.security.KeyChain
import com.propdfeditor.core.database.dao.CertificateDao
import com.propdfeditor.core.database.entity.CertificateEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CertificateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val certificateDao: CertificateDao
) {
    private val keystoreDir: File by lazy {
        File(context.filesDir, "keystores").apply { mkdirs() }
    }

    fun getAllCertificates(): Flow<List<CertificateEntity>> = certificateDao.getAllCertificates()

    suspend fun getCertificateById(id: Long): CertificateEntity? = certificateDao.getCertificateById(id)

    suspend fun importP12Certificate(
        alias: String,
        displayName: String,
        p12Data: ByteArray,
        password: String,
        passwordHint: String? = null
    ): CertificateEntity = withContext(Dispatchers.IO) {
        // Validate P12 first
        val tempKeyStore = KeyStore.getInstance("PKCS12")
        ByteArrayInputStream(p12Data).use { stream ->
            tempKeyStore.load(stream, password.toCharArray())
        }
        
        val keystoreFile = File(keystoreDir, "${alias}_${System.currentTimeMillis()}.p12")
        FileOutputStream(keystoreFile).use { output ->
            output.write(p12Data)
        }
        
        // Extract certificate info
        val certAlias = tempKeyStore.aliases().nextElement()
        val cert = tempKeyStore.getCertificate(certAlias) as? X509Certificate
        
        val entity = CertificateEntity(
            alias = alias,
            displayName = displayName,
            keystorePath = keystoreFile.absolutePath,
            keystoreType = "PKCS12",
            certificateData = cert?.encoded,
            subjectDn = cert?.subjectX500Principal?.name,
            issuerDn = cert?.issuerX500Principal?.name,
            serialNumber = cert?.serialNumber?.toString(16),
            validFrom = cert?.notBefore,
            validUntil = cert?.notAfter,
            algorithm = cert?.sigAlgName,
            keySize = null, // Would need private key extraction
            isSelfSigned = cert?.subjectX500Principal == cert?.issuerX500Principal,
            isEncrypted = true,
            passwordHint = passwordHint
        )
        
        val id = certificateDao.insertCertificate(entity)
        entity.copy(id = id)
    }

    suspend fun importCertificateFromSystem(alias: String): CertificateEntity? = withContext(Dispatchers.IO) {
        // For Android KeyChain imported certificates
        val certChain = KeyChain.getCertificateChain(context, alias) ?: return@withContext null
        val cert = certChain.firstOrNull() as? X509Certificate ?: return@withContext null
        
        val entity = CertificateEntity(
            alias = alias,
            displayName = alias,
            keystorePath = "", // System managed
            keystoreType = "AndroidKeyStore",
            certificateData = cert.encoded,
            subjectDn = cert.subjectX500Principal.name,
            issuerDn = cert.issuerX500Principal.name,
            serialNumber = cert.serialNumber.toString(16),
            validFrom = cert.notBefore,
            validUntil = cert.notAfter,
            algorithm = cert.sigAlgName,
            keySize = null, // Would need private key extraction
            isSelfSigned = cert.subjectX500Principal == cert.issuerX500Principal,
            isEncrypted = false
        )
        
        val id = certificateDao.insertCertificate(entity)
        entity.copy(id = id)
    }

    suspend fun deleteCertificate(certificate: CertificateEntity) = withContext(Dispatchers.IO) {
        if (certificate.keystorePath.isNotEmpty()) {
            File(certificate.keystorePath).delete()
        }
        certificateDao.deleteCertificate(certificate)
    }

    suspend fun setAsDefault(id: Long) {
        certificateDao.setAsDefault(id)
    }

    suspend fun incrementUseCount(id: Long) {
        certificateDao.incrementUseCount(id)
    }

    suspend fun getDefaultCertificate(): CertificateEntity? = certificateDao.getDefaultCertificate()

    fun isCertificateValid(certificate: CertificateEntity): Boolean {
        val validUntil = certificate.validUntil ?: return false
        return validUntil.after(Date())
    }

    fun getDaysUntilExpiry(certificate: CertificateEntity): Int? {
        val validUntil = certificate.validUntil ?: return null
        val diff = validUntil.time - System.currentTimeMillis()
        return (diff / (1000 * 60 * 60 * 24)).toInt()
    }
}
