package com.arceusvault.keel.ui.components

import com.arceusvault.keel.core.network.ConnectionClosedException
import com.arceusvault.keel.core.network.PublicAddressException
import com.arceusvault.keel.core.network.RpcException
import com.arceusvault.keel.core.network.UntrustedCertificateException
import com.arceusvault.keel.data.JobFailedException
import com.arceusvault.keel.data.LoginException
import com.arceusvault.keel.data.SubscriptionException
import kotlinx.coroutines.TimeoutCancellationException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

fun describeError(e: Throwable): String = when (e) {
    is PublicAddressException -> e.message!!
    is UnknownHostException -> "Couldn't resolve the host. If you use Tailscale or another VPN, check that it's connected."
    is ConnectException, is SocketTimeoutException, is TimeoutCancellationException ->
        "Couldn't reach the server. Check the address and port, and that your VPN is connected."
    is UntrustedCertificateException -> when (e.reason) {
        UntrustedCertificateException.Reason.CHANGED -> "The server's certificate changed since you last connected."
        UntrustedCertificateException.Reason.FIRST_USE -> "The server's certificate isn't trusted yet."
    }
    is CertificateException -> "The server's certificate was rejected: ${e.message}"
    is SSLException -> "Secure connection failed: ${e.message}"
    is LoginException -> e.message!!
    is RpcException -> when (e.errname) {
        "ENOTAUTHORIZED", "EACCES", "EPERM" -> "This API key isn't allowed to do that. Its privilege needs the matching role in TrueNAS."
        else -> e.reason.lineSequence().firstOrNull()?.removePrefix("[EFAULT] ")?.trim() ?: e.reason
    }
    is JobFailedException -> e.message!!.removePrefix("[EFAULT] ")
    is SubscriptionException -> e.message!!
    is ConnectionClosedException -> e.message!!
    else -> e.message ?: e.javaClass.simpleName
}
