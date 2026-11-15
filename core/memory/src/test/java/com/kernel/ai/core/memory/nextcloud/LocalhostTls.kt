package com.kernel.ai.core.memory.nextcloud

import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Test-only TLS for MockWebServer on localhost (#1678: the Nextcloud transport refuses plain HTTP).
 * Uses the self-signed `localhost`/`127.0.0.1` certificate in `nextcloud-test-localhost.p12`.
 */
internal class LocalhostTls(val socketFactory: SSLSocketFactory, val client: OkHttpClient)

internal fun localhostTls(): LocalhostTls {
    val password = "testpass".toCharArray()
    val keyStore = KeyStore.getInstance("PKCS12").apply {
        val stream = LocalhostTls::class.java.classLoader!!.getResourceAsStream("nextcloud-test-localhost.p12")
            ?: error("Missing nextcloud-test-localhost.p12 test resource")
        stream.use { load(it, password) }
    }
    val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        .apply { init(keyStore, password) }.keyManagers
    val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(keyStore) }.trustManagers.filterIsInstance<X509TrustManager>().single()
    val serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
    val clientContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .sslSocketFactory(clientContext.socketFactory, trustManager)
        .build()
    return LocalhostTls(serverContext.socketFactory, client)
}
