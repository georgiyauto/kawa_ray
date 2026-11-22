package pro.vinnikov.kawavpn.vpn

import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process

/** Private socket protection bridge to the process that owns the VPN. */
object ProbeProtection {
    const val ACTION = "pro.vinnikov.kawavpn.PROTECT_PROBE_SOCKET"
    private const val DESCRIPTOR = "pro.vinnikov.kawavpn.ProbeProtection"
    private const val PROTECT = IBinder.FIRST_CALL_TRANSACTION

    fun binder(service: VpnService): IBinder = object : Binder() {
        @Suppress("DEPRECATION")
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != PROTECT) return super.onTransact(code, data, reply, flags)
            check(Binder.getCallingUid() == Process.myUid())
            data.enforceInterface(DESCRIPTOR)
            val duplicate = data.readParcelable<ParcelFileDescriptor>(ParcelFileDescriptor::class.java.classLoader)
            val protected = duplicate?.use { runCatching { service.protect(it.fd) }.getOrDefault(false) } ?: false
            reply?.writeNoException()
            reply?.writeInt(if (protected) 1 else 0)
            return true
        }
    }

    internal fun protect(remote: IBinder, fd: Long): Boolean {
        if (fd !in 0..Int.MAX_VALUE.toLong()) return false
        return runCatching {
            // fromFd duplicates; it never adopts or closes the Go-owned original.
            ParcelFileDescriptor.fromFd(fd.toInt()).use { duplicate ->
                val request = Parcel.obtain()
                val response = Parcel.obtain()
                try {
                    request.writeInterfaceToken(DESCRIPTOR)
                    request.writeParcelable(duplicate, 0)
                    if (!remote.transact(PROTECT, request, response, 0)) return@use false
                    response.readException()
                    response.readInt() == 1
                } finally { response.recycle(); request.recycle() }
            }
        }.getOrDefault(false)
    }
}
