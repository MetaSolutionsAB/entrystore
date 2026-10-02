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

package org.entrystore.rest.springboot.security;

import com.github.benmanes.caffeine.cache.Ticker;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.Level;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.web.OpenSaml5AuthenticationTokenConverter;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshableRelyingPartyRegistrationRepositoryTest {

	private static final String IDP_ENTITY_ID = "https://idp.entrystore.example/test";

	@TempDir
	File tempDir;

	private RefreshableRelyingPartyRegistrationRepository repository;

	private final AtomicLong nanos = new AtomicLong();

	// An IdP metadata endpoint that answers 503 while servedMetadata is null and, like Keycloak's descriptor
	// endpoint, never sends a Last-Modified header.
	private HttpServer metadataServer;
	private final AtomicReference<String> servedMetadata = new AtomicReference<>();
	private final AtomicInteger metadataRequests = new AtomicInteger();
	// When set, the endpoint holds each answer until the latch opens.
	private volatile CountDownLatch metadataGate;

	@Test
	void relaxedEnabledSettingCreatesTheMetadataRepository() throws Exception {
		File metadata = writeIdpMetadata("idp.xml", List.of(selfSignedCertBase64("idp-signing")));
		var properties = new Saml2RelyingPartyProperties();
		var registration = new Saml2RelyingPartyProperties.Registration();
		registration.setEntityId("https://sp.entrystore.example/keycloak");
		registration.getAssertingparty().setMetadataUri(metadata.toURI().toString());
		properties.getRegistration().put("keycloak", registration);

		new ApplicationContextRunner()
				.withUserConfiguration(SamlBinding.class, RefreshableRelyingPartyRegistrationRepository.class)
				.withBean(Saml2RelyingPartyProperties.class, () -> properties)
				.withBean(Ticker.class, Ticker::systemTicker)
				.withPropertyValues("entrystore.auth.saml.enabled=on")
				.run(context -> {
					assertNull(context.getStartupFailure());
					assertTrue(context.getBean(SamlCustomConfiguration.class).enabled());
					var boundRepository = context.getBean(RefreshableRelyingPartyRegistrationRepository.class);
					assertNotNull(boundRepository.findByRegistrationId("keycloak"));
				});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(SamlCustomConfiguration.class)
	static class SamlBinding {}

	@AfterEach
	void tearDown() {
		if (repository != null) {
			repository.shutdown();
		}
		if (metadataServer != null) {
			metadataServer.stop(0);
		}
	}

	@Test
	void findByRegistrationId_extractsMetadataSigningCertAsVerificationCredential() throws Exception {
		String signingCert = selfSignedCertBase64("idp-signing");
		repository = repositoryFor("keycloak", writeIdpMetadata("idp-v1.xml", List.of(signingCert)));

		RelyingPartyRegistration registration = repository.findByRegistrationId("keycloak");

		assertNotNull(registration);
		assertEquals("keycloak", registration.getRegistrationId());
		assertEquals("https://sp.entrystore.example/keycloak", registration.getEntityId());
		assertEquals(Set.of(signingCert), verificationCerts(registration));
	}

	@Test
	void findByRegistrationId_metadataGainingSigningCert_yieldsBothAsVerificationCredentials() throws Exception {
		// Simulates an IdP signing-certificate rollover: the published metadata now carries the existing
		// certificate plus a newly published one. Spring Security accepts a signature matching any of them.
		String existingCert = selfSignedCertBase64("idp-existing");
		String rolledCert = selfSignedCertBase64("idp-rolled");
		repository = repositoryFor("keycloak", writeIdpMetadata("idp-v2.xml", List.of(existingCert, rolledCert)));

		RelyingPartyRegistration registration = repository.findByRegistrationId("keycloak");

		assertEquals(Set.of(existingCert, rolledCert), verificationCerts(registration),
				"both the existing and the newly published signing certificate must be verification credentials");
	}

	@Test
	void iterator_returnsOneRegistrationPerConfiguredId() throws Exception {
		repository = repositoryFor("keycloak", writeIdpMetadata("idp-v1.xml", List.of(selfSignedCertBase64("idp"))));

		List<String> ids = StreamSupport.stream(repository.spliterator(), false)
				.map(RelyingPartyRegistration::getRegistrationId)
				.toList();

		assertEquals(List.of("keycloak"), ids);
	}

	@Test
	void findByRegistrationId_unknownRegistration_returnsNull() throws Exception {
		repository = repositoryFor("keycloak", writeIdpMetadata("idp-v1.xml", List.of(selfSignedCertBase64("idp"))));

		assertNull(repository.findByRegistrationId("not-configured"));
	}

	@Test
	void initialize_failsFast_whenSamlEnabledButNoRegistrations() {
		var repo = new RefreshableRelyingPartyRegistrationRepository(
				new Saml2RelyingPartyProperties(), enabledSamlConfig(), nanos::get);

		var ex = assertThrows(IllegalStateException.class, repo::initialize);
		assertTrue(ex.getMessage().contains("no relying-party registrations"));
	}

	@Test
	void initialize_noRegistrations_namesTheIdpIdsEntryStoreSettingsReferTo() {
		var samlConfig = new SamlCustomConfiguration(true, "keycloak", List.of(),
				Map.of("google", new SamlCustomConfiguration.Idp(null, false, null)), null, null, null);
		var repo = new RefreshableRelyingPartyRegistrationRepository(new Saml2RelyingPartyProperties(), samlConfig, nanos::get);

		var ex = assertThrows(IllegalStateException.class, repo::initialize);
		assertTrue(ex.getMessage().contains("[google, keycloak]"), ex.getMessage());
		assertTrue(ex.getMessage().contains(
				"spring.security.saml2.relyingparty.registration.<id>.assertingparty.metadata-uri"), ex.getMessage());
	}

	@Test
	void findUniqueByAssertingPartyEntityId_returnsTheRegistrationOfThatIdp() throws Exception {
		repository = repositoryFor("keycloak", writeIdpMetadata("idp-v1.xml", List.of(selfSignedCertBase64("idp"))));

		RelyingPartyRegistration registration = repository.findUniqueByAssertingPartyEntityId(IDP_ENTITY_ID);

		assertNotNull(registration);
		assertEquals("keycloak", registration.getRegistrationId());
	}

	@Test
	void findUniqueByAssertingPartyEntityId_unknownEntityId_returnsNull() throws Exception {
		repository = repositoryFor("keycloak", writeIdpMetadata("idp-v1.xml", List.of(selfSignedCertBase64("idp"))));

		assertNull(repository.findUniqueByAssertingPartyEntityId("https://other.idp.example/test"));
	}

	@Test
	void findUniqueByAssertingPartyEntityId_twoRegistrationsForTheSameIdp_returnsNull() throws Exception {
		String metadataUri = writeIdpMetadata("idp-v1.xml", List.of(selfSignedCertBase64("idp"))).toURI().toString();
		var properties = new Saml2RelyingPartyProperties();
		for (String id : List.of("keycloak", "keycloak-2")) {
			var registration = new Saml2RelyingPartyProperties.Registration();
			registration.getAssertingparty().setMetadataUri(metadataUri);
			properties.getRegistration().put(id, registration);
		}
		repository = new RefreshableRelyingPartyRegistrationRepository(properties, enabledSamlConfig(), nanos::get);
		repository.initialize();

		assertNull(repository.findUniqueByAssertingPartyEntityId(IDP_ENTITY_ID), "an ambiguous Issuer must not pick one");
	}

	@Test
	void tokenConverter_unsolicitedResponseToTheFiveXAcsUrl_resolvesTheRegistrationByIssuer() throws Exception {
		// An IdP-initiated response to the 5.x single-IdP ACS URL: no ?idp and no RelayState of a saved request.
		repository = repositoryFor("default", writeIdpMetadata("idp-v1.xml", List.of(selfSignedCertBase64("idp"))));
		var converter = new OpenSaml5AuthenticationTokenConverter(repository);
		converter.setRequestMatcher(new SamlAcsRequestMatcher());
		var request = new MockHttpServletRequest("POST", "/auth/saml");
		request.setParameter("SAMLResponse", Base64.getEncoder().encodeToString("""
				<samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"
				                xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion"
				                ID="_unsolicited" Version="2.0" IssueInstant="2026-01-01T00:00:00Z">
				  <saml:Issuer>%s</saml:Issuer>
				</samlp:Response>""".formatted(IDP_ENTITY_ID).getBytes(StandardCharsets.UTF_8)));

		var token = converter.convert(request);

		assertNotNull(token);
		assertEquals("default", token.getRelyingPartyRegistration().getRegistrationId());
	}

	@Test
	void initialize_failsFast_whenMetadataUriMissing() {
		var properties = new Saml2RelyingPartyProperties();
		properties.getRegistration().put("keycloak", new Saml2RelyingPartyProperties.Registration());
		var repo = new RefreshableRelyingPartyRegistrationRepository(properties, enabledSamlConfig(), nanos::get);

		var ex = assertThrows(IllegalStateException.class, repo::initialize);
		assertTrue(ex.getMessage().contains("metadata-uri"));
	}

	// A local metadata file is configuration, not a remote IdP that may be down: a missing one fails startup.
	@Test
	void initialize_failsFast_whenLocalMetadataMissing() {
		File metadata = new File(tempDir, "absent.xml");

		var ex = assertThrows(IllegalStateException.class, () -> repositoryFor("keycloak", metadata));
		assertTrue(ex.getMessage().contains("keycloak"));
	}

	@Test
	void initialize_failsFast_whenLocalMetadataInvalid() throws Exception {
		File metadata = new File(tempDir, "invalid.xml");
		Files.writeString(metadata.toPath(), "not SAML metadata");

		var ex = assertThrows(IllegalStateException.class, () -> repositoryFor("keycloak", metadata));
		assertTrue(ex.getMessage().contains("keycloak"));
	}

	@Test
	void isConfiguredButUnavailable_whenTheMetadataLacksTheConfiguredEntityId() throws Exception {
		repository = repositoryFor("keycloak", writeIdpMetadata("idp-v1.xml", List.of(selfSignedCertBase64("idp"))),
				"https://other.idp.example/test");

		assertTrue(repository.isConfiguredButUnavailable("keycloak"));
		assertFalse(repository.isConfiguredButUnavailable("not-configured"));
	}

	// 5.x started without an IdP that was down and only failed SAML login with it.
	@Test
	void initialize_startsWithoutMetadataAndWarns_whenRemoteIdpUnreachable() throws Exception {
		String metadataUri = "http://127.0.0.1:" + closedPort() + "/metadata";

		try (var appender = CapturingAppender.attachTo(RefreshableRelyingPartyRegistrationRepository.class)) {
			repository = repositoryFor("keycloak", metadataUri, null);

			assertEquals(1, appender.messagesAt(Level.WARN)
					.filter(message -> message.contains("'keycloak' could not be loaded from " + metadataUri))
					.count(), appender::toString);
		}
		assertTrue(repository.isConfiguredButUnavailable("keycloak"));
		assertNull(repository.findByRegistrationId("keycloak"));
	}

	@Test
	void findByRegistrationId_loadsTheMetadataOnLogin_onceTheIdpRecovers() throws Exception {
		String metadataUri = startMetadataServer();
		repository = repositoryFor("keycloak", metadataUri, null);
		String signingCert = selfSignedCertBase64("idp-signing");
		// The IdP sends no Last-Modified header: the fetch must not be skipped as "unchanged since the failed attempt".
		servedMetadata.set(idpMetadataXml(List.of(signingCert)));

		RelyingPartyRegistration registration = repository.findByRegistrationId("keycloak");

		assertNotNull(registration);
		assertEquals(Set.of(signingCert), verificationCerts(registration));
		assertFalse(repository.isConfiguredButUnavailable("keycloak"));
	}

	@Test
	void findByRegistrationId_fetchesUnavailableMetadataAtMostOncePerInterval() throws Exception {
		String metadataUri = startMetadataServer();
		try (var appender = CapturingAppender.attachTo(RefreshableRelyingPartyRegistrationRepository.class)) {
			repository = repositoryFor("keycloak", metadataUri, null);
			assertNull(repository.findByRegistrationId("keycloak"));
			int requestsAfterFirstLogin = metadataRequests.get();
			servedMetadata.set(idpMetadataXml(List.of(selfSignedCertBase64("idp-signing"))));

			assertNull(repository.findByRegistrationId("keycloak"), "a login within the interval must not fetch");
			assertEquals(requestsAfterFirstLogin, metadataRequests.get());
			assertEquals(1, appender.messagesAt(Level.WARN).filter(message -> message.contains("still unavailable"))
					.count(), appender::toString);

			nanos.addAndGet(RefreshableRelyingPartyRegistrationRepository.ON_DEMAND_REFRESH_INTERVAL.toNanos());

			assertNotNull(repository.findByRegistrationId("keycloak"));
			assertEquals(requestsAfterFirstLogin + 1, metadataRequests.get());
		}
	}

	// A fetch can outlast the throttle interval; a login meanwhile must not park its request thread on the resolver.
	@Test
	@Timeout(30)
	void findByRegistrationId_doesNotWaitForAFetchStillRunning() throws Exception {
		String metadataUri = startMetadataServer();
		repository = repositoryFor("keycloak", metadataUri, null);
		var idpAnswers = new CountDownLatch(1);
		metadataGate = idpAnswers;
		servedMetadata.set(idpMetadataXml(List.of(selfSignedCertBase64("idp-signing"))));
		int requestsBeforeLogins = metadataRequests.get();
		Thread slowLogin = Thread.ofVirtual().start(() -> repository.findByRegistrationId("keycloak"));
		while (metadataRequests.get() == requestsBeforeLogins) {
			Thread.onSpinWait();
		}
		nanos.addAndGet(RefreshableRelyingPartyRegistrationRepository.ON_DEMAND_REFRESH_INTERVAL.toNanos());

		try {
			assertTimeoutPreemptively(Duration.ofSeconds(5),
					() -> assertNull(repository.findByRegistrationId("keycloak")));
		} finally {
			idpAnswers.countDown();
		}
		slowLogin.join();
		assertNotNull(repository.findByRegistrationId("keycloak"), "the slow login's fetch loads the metadata");
		assertEquals(requestsBeforeLogins + 1, metadataRequests.get());
	}

	@Test
	void findByRegistrationId_afterShutdown_returnsNullWithoutFetching() throws Exception {
		String metadataUri = startMetadataServer();
		repository = repositoryFor("keycloak", metadataUri, null);
		repository.shutdown();
		int requestsBeforeLookup = metadataRequests.get();
		servedMetadata.set(idpMetadataXml(List.of(selfSignedCertBase64("idp-signing"))));

		assertNull(repository.findByRegistrationId("keycloak"));
		assertEquals(requestsBeforeLookup, metadataRequests.get());
	}

	@Test
	void findByRegistrationId_selectsAssertingPartyByConfiguredEntityId() throws Exception {
		String firstCert = selfSignedCertBase64("idp-one");
		String secondCert = selfSignedCertBase64("idp-two");
		var entities = new LinkedHashMap<String, String>();
		entities.put("https://idp.one/test", firstCert);
		entities.put("https://idp.two/test", secondCert);
		repository = repositoryFor("keycloak", writeAggregateMetadata("idps.xml", entities), "https://idp.two/test");

		RelyingPartyRegistration registration = repository.findByRegistrationId("keycloak");

		assertEquals(Set.of(secondCert), verificationCerts(registration),
				"the asserting party selected by entity-id must supply the verification credential");
	}

	@Test
	void findByRegistrationId_multipleAssertingPartiesWithoutEntityId_usesOne() throws Exception {
		var entities = new LinkedHashMap<String, String>();
		entities.put("https://idp.one/test", selfSignedCertBase64("idp-one"));
		entities.put("https://idp.two/test", selfSignedCertBase64("idp-two"));
		repository = repositoryFor("keycloak", writeAggregateMetadata("idps.xml", entities));

		RelyingPartyRegistration registration = repository.findByRegistrationId("keycloak");

		assertNotNull(registration);
		assertEquals(1, registration.getAssertingPartyMetadata().getVerificationX509Credentials().size(),
				"with no configured entity-id a single asserting party is selected (and a warning is logged)");
	}

	private RefreshableRelyingPartyRegistrationRepository repositoryFor(String registrationId, File metadataFile) {
		return repositoryFor(registrationId, metadataFile, null);
	}

	private RefreshableRelyingPartyRegistrationRepository repositoryFor(String registrationId, File metadataFile,
																		String assertingPartyEntityId) {
		return repositoryFor(registrationId, metadataFile.toURI().toString(), assertingPartyEntityId);
	}

	private RefreshableRelyingPartyRegistrationRepository repositoryFor(String registrationId, String metadataUri,
																		String assertingPartyEntityId) {
		var properties = new Saml2RelyingPartyProperties();
		var registration = new Saml2RelyingPartyProperties.Registration();
		registration.setEntityId("https://sp.entrystore.example/" + registrationId);
		registration.getAssertingparty().setMetadataUri(metadataUri);
		if (assertingPartyEntityId != null) {
			registration.getAssertingparty().setEntityId(assertingPartyEntityId);
		}
		properties.getRegistration().put(registrationId, registration);

		var repo = new RefreshableRelyingPartyRegistrationRepository(properties, enabledSamlConfig(), nanos::get);
		repo.initialize();
		return repo;
	}

	private static SamlCustomConfiguration enabledSamlConfig() {
		return new SamlCustomConfiguration(true, null, List.of(), Map.of(), null, null, null);
	}

	private String startMetadataServer() throws IOException {
		metadataServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		metadataServer.createContext("/metadata", exchange -> {
			metadataRequests.incrementAndGet();
			if (metadataGate != null) {
				try {
					metadataGate.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			String xml = servedMetadata.get();
			if (xml == null) {
				exchange.sendResponseHeaders(503, -1);
				exchange.close();
				return;
			}
			byte[] body = xml.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (var out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		metadataServer.start();
		return "http://127.0.0.1:" + metadataServer.getAddress().getPort() + "/metadata";
	}

	private static int closedPort() throws IOException {
		try (var socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private File writeIdpMetadata(String fileName, List<String> signingCertsBase64) throws Exception {
		File file = new File(tempDir, fileName);
		Files.writeString(file.toPath(), idpMetadataXml(signingCertsBase64));
		return file;
	}

	private static String idpMetadataXml(List<String> signingCertsBase64) {
		String keyDescriptors = signingCertsBase64.stream()
				.map("""
						<md:KeyDescriptor use="signing">
						  <ds:KeyInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
						    <ds:X509Data><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data>
						  </ds:KeyInfo>
						</md:KeyDescriptor>"""::formatted)
				.collect(Collectors.joining("\n"));
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata"
				                     entityID="%s" validUntil="2099-01-01T00:00:00Z">
				  <md:IDPSSODescriptor WantAuthnRequestsSigned="false"
				                       protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
				%s
				    <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
				                            Location="https://idp.entrystore.example/test/sso"/>
				  </md:IDPSSODescriptor>
				</md:EntityDescriptor>""".formatted(IDP_ENTITY_ID, keyDescriptors);
	}

	// An aggregate EntitiesDescriptor with one IDPSSODescriptor (single signing cert) per entity id.
	private File writeAggregateMetadata(String fileName, Map<String, String> entityIdToSigningCert) throws Exception {
		String entities = entityIdToSigningCert.entrySet().stream()
				.map(entry -> """
						  <md:EntityDescriptor entityID="%s" validUntil="2099-01-01T00:00:00Z">
						    <md:IDPSSODescriptor WantAuthnRequestsSigned="false" protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
						      <md:KeyDescriptor use="signing">
						        <ds:KeyInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
						          <ds:X509Data><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data>
						        </ds:KeyInfo>
						      </md:KeyDescriptor>
						      <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST" Location="%s/sso"/>
						    </md:IDPSSODescriptor>
						  </md:EntityDescriptor>""".formatted(entry.getKey(), entry.getValue(), entry.getKey()))
				.collect(Collectors.joining("\n"));
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<md:EntitiesDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" validUntil="2099-01-01T00:00:00Z">
				%s
				</md:EntitiesDescriptor>""".formatted(entities);
		File file = new File(tempDir, fileName);
		Files.writeString(file.toPath(), xml);
		return file;
	}

	private static Set<String> verificationCerts(RelyingPartyRegistration registration) {
		return registration.getAssertingPartyMetadata().getVerificationX509Credentials().stream()
				.map(credential -> base64(credential.getCertificate()))
				.collect(Collectors.toSet());
	}

	private static String selfSignedCertBase64(String commonName) throws Exception {
		var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
		keyPairGenerator.initialize(2048);
		var keyPair = keyPairGenerator.generateKeyPair();
		var name = new X500Name("CN=" + commonName);
		var now = Instant.now();
		var certificateBuilder = new JcaX509v3CertificateBuilder(
				name, BigInteger.valueOf(System.nanoTime()),
				Date.from(now.minusSeconds(3600)), Date.from(now.plusSeconds(3650L * 24 * 3600)),
				name, keyPair.getPublic());
		ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
		X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(certificateBuilder.build(signer));
		return base64(certificate);
	}

	private static String base64(X509Certificate certificate) {
		try {
			return Base64.getEncoder().encodeToString(certificate.getEncoded());
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
