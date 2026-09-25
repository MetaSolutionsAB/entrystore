/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.it

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.entrystore.rest.it.util.EntryStoreClient

import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import static java.net.HttpURLConnection.HTTP_BAD_GATEWAY
import static java.net.HttpURLConnection.HTTP_OK

/**
 * Proxying to HTTPS upstreams over connections pinned to the resolved IP. Each upstream presents a
 * self-signed certificate: one valid for {@code localhost}, one for another host name, and one valid
 * only for the loopback IPs, so a proxy that verified against the pinned IP would accept it.
 * <p>
 * Replaces {@link HttpsURLConnection}'s JVM-wide default socket factory for the duration of this
 * spec so the app trusts these certificates on top of the JDK defaults.
 */
class ProxyHttpsIT extends BaseSpec {

	static SSLSocketFactory previousDefaultFactory
	static Map<String, HttpsServer> servers = [:]
	static Map<String, AtomicInteger> hits = [:]

	def setupSpec() {
		previousDefaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory()

		def certs = [
			good    : [new GeneralName(GeneralName.dNSName, 'localhost')],
			wrong   : [new GeneralName(GeneralName.dNSName, 'wrong.example')],
			ipOnly  : [new GeneralName(GeneralName.iPAddress, '127.0.0.1'),
			           new GeneralName(GeneralName.iPAddress, '::1')]
		]
		KeyStore trusted = defaultTrustStore()
		certs.each { name, sans ->
			KeyPair keyPair = KeyPairGenerator.getInstance('EC').with { initialize(256); generateKeyPair() }
			X509Certificate cert = selfSigned(keyPair, sans as GeneralName[])
			trusted.setCertificateEntry(name, cert)
			servers[name] = startServer(name, keyPair, cert)
		}

		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
		tmf.init(trusted)
		SSLContext clientContext = SSLContext.getInstance('TLS')
		clientContext.init(null, tmf.trustManagers, null)
		HttpsURLConnection.setDefaultSSLSocketFactory(clientContext.socketFactory)
	}

	def cleanupSpec() {
		if (previousDefaultFactory != null) {
			HttpsURLConnection.setDefaultSSLSocketFactory(previousDefaultFactory)
		}
		servers.values()*.stop(0)
	}

	def setup() {
		hits.values()*.set(0)
	}

	def 'GET /proxy to an HTTPS upstream whose certificate matches the host name should return proxied content'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + upstreamUrl('good', '/api/data'))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.inputStream.text == '{"server":"good"}'
		hits.good.get() == 1
	}

	def 'GET /proxy to an HTTPS upstream whose certificate is for another host should return 502'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + upstreamUrl('wrong', '/api/data'))

		then:
		conn.getResponseCode() == HTTP_BAD_GATEWAY
		hits.wrong.get() == 0
	}

	def 'GET /proxy to an HTTPS upstream whose certificate only matches the pinned IP should return 502'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + upstreamUrl('ipOnly', '/api/data'))

		then:
		conn.getResponseCode() == HTTP_BAD_GATEWAY
		hits.ipOnly.get() == 0
	}

	def 'GET /proxy should follow an HTTPS redirect to a host with a valid certificate'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + upstreamUrl('good', '/redirect-good'))

		then:
		conn.getResponseCode() == HTTP_OK
		conn.inputStream.text == '{"server":"good"}'
	}

	def 'GET /proxy should return 502 for an HTTPS redirect to a host with a certificate for another host'() {
		when:
		def conn = EntryStoreClient.getRequest('/proxy?url=' + upstreamUrl('good', '/redirect-wrong'))

		then:
		conn.getResponseCode() == HTTP_BAD_GATEWAY
		hits.wrong.get() == 0
	}

	private static String upstreamUrl(String server, String path) {
		return URLEncoder.encode("https://localhost:${servers[server].address.port}${path}", 'UTF-8')
	}

	private static KeyStore defaultTrustStore() {
		KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType())
		new File(System.getProperty('java.home'), 'lib/security/cacerts').withInputStream {
			trusted.load(it, null)
		}
		return trusted
	}

	private static X509Certificate selfSigned(KeyPair keyPair, GeneralName[] sans) {
		def subject = new X500Name('CN=ProxyHttpsIT')
		def now = Instant.now()
		def builder = new JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(now.toEpochMilli()),
			Date.from(now - Duration.ofMinutes(5)), Date.from(now + Duration.ofDays(1)), subject, keyPair.public)
		builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sans))
		def signer = new JcaContentSignerBuilder('SHA256withECDSA').build(keyPair.private)
		return new JcaX509CertificateConverter().getCertificate(builder.build(signer))
	}

	private static HttpsServer startServer(String name, KeyPair keyPair, X509Certificate cert) {
		char[] password = 'changeit'.toCharArray()
		KeyStore keyStore = KeyStore.getInstance('PKCS12')
		keyStore.load(null, null)
		keyStore.setKeyEntry(name, keyPair.private, password, [cert] as X509Certificate[])
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
		kmf.init(keyStore, password)
		SSLContext serverContext = SSLContext.getInstance('TLS')
		serverContext.init(kmf.keyManagers, null, null)

		HttpsServer server = HttpsServer.create(new InetSocketAddress('localhost', 0), 0)
		server.httpsConfigurator = new HttpsConfigurator(serverContext)
		hits[name] = new AtomicInteger()
		server.createContext('/api/data') { HttpExchange exchange ->
			hits[name].incrementAndGet()
			respond(exchange, 200, "{\"server\":\"${name}\"}")
		}
		server.createContext('/redirect-good') { HttpExchange exchange ->
			exchange.responseHeaders.set('Location', "https://localhost:${servers.good.address.port}/api/data")
			respond(exchange, 302, '')
		}
		server.createContext('/redirect-wrong') { HttpExchange exchange ->
			exchange.responseHeaders.set('Location', "https://localhost:${servers.wrong.address.port}/api/data")
			respond(exchange, 302, '')
		}
		server.start()
		return server
	}

	private static void respond(HttpExchange exchange, int status, String body) {
		byte[] bytes = body.bytes
		exchange.responseHeaders.set('Content-Type', 'application/json')
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length)
		if (bytes.length > 0) {
			exchange.responseBody.write(bytes)
		}
		exchange.close()
	}
}
