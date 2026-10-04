package com.material.xray.core.android.xray

import android.net.LocalSocketAddress
import com.material.xray.core.xray.LocalSockets
import javax.net.SocketFactory
import org.koin.core.annotation.Singleton

/** [LocalSockets] on `android.net.LocalSocket`. */
@Singleton(binds = [LocalSockets::class])
class AndroidLocalSockets : LocalSockets {
    override fun abstractSocketFactory(name: String): SocketFactory = AndroidLocalSocketFactory(name, LocalSocketAddress.Namespace.ABSTRACT)

    override fun fileSystemSocketFactory(path: String): SocketFactory = AndroidLocalSocketFactory(path, LocalSocketAddress.Namespace.FILESYSTEM)
}
