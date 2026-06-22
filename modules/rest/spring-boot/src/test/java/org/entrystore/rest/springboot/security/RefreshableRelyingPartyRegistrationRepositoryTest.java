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

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.security.saml2.Saml2RelyingPartyProperties;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;

import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshableRelyingPartyRegistrationRepositoryTest {

	@TempDir
	File tempDir;

	private RefreshableRelyingPartyRegistrationRepository repository;

	@AfterEach
	void tearDown() {
		if (repository != null) {
			repository.shutdown();
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
				new Saml2RelyingPartyProperties(), enabledSamlConfig());

		var ex = assertThrows(IllegalStateException.class, repo::initialize);
		assertTrue(ex.getMessage().contains("no relying-party registrations"));
	}

	@Test
	void initialize_failsFast_whenMetadataUriMissing() {
		var properties = new Saml2RelyingPartyProperties();
		properties.getRegistration().put("keycloak", new Saml2RelyingPartyProperties.Registration());
		var repo = new RefreshableRelyingPartyRegistrationRepository(properties, enabledSamlConfig());

		var ex = assertThrows(IllegalStateException.class, repo::initialize);
		assertTrue(ex.getMessage().contains("metadata-uri"));
	}

	@Test
	void initialize_failsFast_whenMetadataUnreadable() {
		var properties = new Saml2RelyingPartyProperties();
		var registration = new Saml2RelyingPartyProperties.Registration();
		registration.getAssertingparty().setMetadataUri(new File(tempDir, "absent.xml").toURI().toString());
		properties.getRegistration().put("keycloak", registration);
		var repo = new RefreshableRelyingPartyRegistrationRepository(properties, enabledSamlConfig());

		var ex = assertThrows(IllegalStateException.class, repo::initialize);
		assertTrue(ex.getMessage().contains("keycloak"));
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
		var properties = new Saml2RelyingPartyProperties();
		var registration = new Saml2RelyingPartyProperties.Registration();
		registration.setEntityId("https://sp.entrystore.example/" + registrationId);
		registration.getAssertingparty().setMetadataUri(metadataFile.toURI().toString());
		if (assertingPartyEntityId != null) {
			registration.getAssertingparty().setEntityId(assertingPartyEntityId);
		}
		properties.getRegistration().put(registrationId, registration);

		var repo = new RefreshableRelyingPartyRegistrationRepository(properties, enabledSamlConfig());
		repo.initialize();
		return repo;
	}

	private static SamlCustomConfiguration enabledSamlConfig() {
		return new SamlCustomConfiguration(true, null, List.of(), Map.of(), null, null);
	}

	private File writeIdpMetadata(String fileName, List<String> signingCertsBase64) throws Exception {
		String keyDescriptors = signingCertsBase64.stream()
				.map("""
						<md:KeyDescriptor use="signing">
						  <ds:KeyInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
						    <ds:X509Data><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data>
						  </ds:KeyInfo>
						</md:KeyDescriptor>"""::formatted)
				.collect(Collectors.joining("\n"));
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata"
				                     entityID="https://idp.entrystore.example/test" validUntil="2099-01-01T00:00:00Z">
				  <md:IDPSSODescriptor WantAuthnRequestsSigned="false"
				                       protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
				%s
				    <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
				                            Location="https://idp.entrystore.example/test/sso"/>
				  </md:IDPSSODescriptor>
				</md:EntityDescriptor>""".formatted(keyDescriptors);
		File file = new File(tempDir, fileName);
		Files.writeString(file.toPath(), xml);
		return file;
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
