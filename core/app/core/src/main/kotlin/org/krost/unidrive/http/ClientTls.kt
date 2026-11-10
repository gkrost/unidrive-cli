package org.krost.unidrive.http

import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * A trust override for a client that talks to a test server with a certificate of its own. Production code never passes one:
 * the clients then use the JVM's default trust (the `javax.net.ssl.trustStore*` properties, `Windows-ROOT` on the Windows
 * launchers).
 */
public class ClientTls(
    public val socketFactory: SSLSocketFactory,
    public val trustManager: X509TrustManager,
)
