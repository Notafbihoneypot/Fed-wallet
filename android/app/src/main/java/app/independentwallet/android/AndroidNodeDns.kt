package app.independentwallet.android

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object AndroidNodeDns {
    suspend fun lookup(host: String): List<String> {
        // Preserve Android hosts/search-domain behavior for local names. API 28
        // also uses the system's InetAddress path; no alternate DNS provider.
        if (Build.VERSION.SDK_INT < 29 || '.' !in host || host.endsWith(".local")) {
            return withContext(Dispatchers.IO) {
                try { InetAddress.getAllByName(host).mapNotNull { it.hostAddress } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { throw NodeResolutionException(problem(error)) }
            }
        }
        return Platform29.lookup(host)
    }

    private fun problem(error: Throwable): NodeResolutionProblem {
        // Inspect only typed OS errors and fixed codes, never exception messages.
        var cause: Throwable? = error
        repeat(8) {
            when (val current = cause) {
                is SecurityException -> return NodeResolutionProblem.Permission
                is ErrnoException -> when (current.errno) {
                    OsConstants.EACCES, OsConstants.EPERM -> return NodeResolutionProblem.Permission
                    OsConstants.ENETDOWN, OsConstants.ENETUNREACH, OsConstants.EHOSTUNREACH -> return NodeResolutionProblem.NoNetwork
                    OsConstants.ETIMEDOUT -> return NodeResolutionProblem.Timeout
                    OsConstants.EAGAIN -> return NodeResolutionProblem.Server
                }
            }
            cause = cause?.cause
        }
        return if (error is UnknownHostException) NodeResolutionProblem.NoAddress else NodeResolutionProblem.System
    }

    @androidx.annotation.RequiresApi(29)
    private object Platform29 {
        // Callback work is small and nonblocking. Cancellation is tied to the
        // owning connection coroutine, including lock, route change and timeout.
        private val callbackExecutor = Executor { it.run() }

        suspend fun lookup(host: String): List<String> = suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            val callback = object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    if (!continuation.isActive) return
                    val failure = dnsAnswerProblem(rcode, answer.isNotEmpty())
                    if (failure != null) continuation.resumeWithException(NodeResolutionException(failure))
                    else continuation.resume(answer.mapNotNull { it.hostAddress })
                }

                override fun onError(error: DnsResolver.DnsException) {
                    if (continuation.isActive) continuation.resumeWithException(NodeResolutionException(problem(error)))
                }
            }
            try {
                if (continuation.isActive) DnsResolver.getInstance().query(
                    null, host, DnsResolver.FLAG_EMPTY, callbackExecutor, cancellation, callback)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(NodeResolutionException(problem(error)))
            }
        }
    }
}
