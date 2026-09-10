package com.cyberbot.mobile.core.security

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Como terminou a tentativa de entrar. */
sealed interface ResultadoAuth {
    data object Sucesso : ResultadoAuth
    data object Falha : ResultadoAuth
    data object Cancelado : ResultadoAuth
    data object Indisponivel : ResultadoAuth
}

/**
 * Pede biometria ou o codigo de desbloqueio do aparelho.
 *
 * A combinacao de autenticadores muda com a versao do Android: o pareamento
 * biometria + credencial do aparelho so existe a partir da API 30. Abaixo disso o
 * sistema usa o que estiver disponivel, e o resultado pratico e o mesmo: sem
 * biometria ou sem PIN do aparelho, nao entra.
 */
class AutenticacaoBiometrica(private val atividade: FragmentActivity) {

    private fun autenticadores(): Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        else -> BIOMETRIC_WEAK
    }

    /** O aparelho tem biometria cadastrada ou algum bloqueio de tela configurado? */
    fun temProtecao(): Boolean {
        val gerenciador = BiometricManager.from(atividade)
        val porBiometria = gerenciador.canAuthenticate(autenticadores()) == BiometricManager.BIOMETRIC_SUCCESS
        if (porBiometria) return true
        val keyguard = atividade.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguard?.isDeviceSecure == true
    }

    fun pedir(aoTerminar: (ResultadoAuth) -> Unit) {
        val executor = ContextCompat.getMainExecutor(atividade)
        val prompt = BiometricPrompt(
            atividade,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    aoTerminar(ResultadoAuth.Sucesso)
                }

                override fun onAuthenticationError(codigo: Int, mensagem: CharSequence) {
                    aoTerminar(
                        when (codigo) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED,
                            -> ResultadoAuth.Cancelado

                            BiometricPrompt.ERROR_NO_BIOMETRICS,
                            BiometricPrompt.ERROR_HW_NOT_PRESENT,
                            BiometricPrompt.ERROR_HW_UNAVAILABLE,
                            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                            -> ResultadoAuth.Indisponivel

                            else -> ResultadoAuth.Falha
                        }
                    )
                }

                override fun onAuthenticationFailed() {
                    // uma leitura ruim ainda deixa o usuario tentar de novo dentro do
                    // proprio prompt; so conta como erro quando o prompt fecha.
                }
            }
        )

        val construtor = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Acesso restrito")
            .setSubtitle("Confirme sua identidade para entrar")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            construtor.setAllowedAuthenticators(autenticadores())
        } else {
            @Suppress("DEPRECATION")
            construtor.setDeviceCredentialAllowed(true)
        }

        try {
            prompt.authenticate(construtor.build())
        } catch (erro: Exception) {
            aoTerminar(ResultadoAuth.Indisponivel)
        }
    }
}
