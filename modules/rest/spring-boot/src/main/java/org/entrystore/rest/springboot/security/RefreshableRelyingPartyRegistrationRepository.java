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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.saml.metadata.resolver.impl.AbstractReloadingMetadataResolver;
import org.opensaml.saml.metadata.resolver.impl.ResourceBackedMetadataResolver;
import org.opensaml.saml.metadata.resolver.index.impl.RoleMetadataIndex;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyProperties;
import org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyProperties.AssertingParty;
import org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyProperties.Registration;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.saml2.core.OpenSamlInitializationService;
import org.springframework.security.saml2.provider.service.registration.AssertingPartyMetadata;
import org.springframework.security.saml2.provider.service.registration.AssertingPartyMetadataRepository;
import org.springframework.security.saml2.provider.service.registration.IterableRelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.OpenSaml4AssertingPartyMetadataRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A {@link org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository}
 * that re-fetches each IdP's asserting-party metadata at runtime, so signing-certificate rollovers are
 * picked up without restarting EntryStore (ENTRYSTORE-1061).
 *
 * <p>Defining this bean makes Spring Boot's auto-configured repository back off
 * ({@code @ConditionalOnMissingBean}); it is only created when SAML is enabled. Each configured
 * registration is backed by an OpenSAML {@link ResourceBackedMetadataResolver} that reloads the
 * metadata in the background on an expiry-aware schedule, bounded by
 * {@code entrystore.auth.saml.idp.<id>.metadata.max-age} (the staleness ceiling, default 7 days). On a
 * failed refresh the resolver keeps the previously loaded metadata, so a transient fetch error never
 * causes an auth outage. Every {@code use="signing"} certificate in the refreshed metadata becomes a
 * verification credential automatically.
 *
 * <p>{@link #findByRegistrationId(String)} builds the {@link RelyingPartyRegistration} fresh from the
 * live (background-refreshed) metadata on each call; Spring Security resolves the registration per
 * request, so refreshes are picked up by subsequent logins with no re-wiring of the security filter
 * chain. The relying-party (SP) side of each registration is mapped as Spring Boot's
 * {@code Saml2RelyingPartyRegistrationConfiguration} does, except that statically configured SP
 * signing/decryption and verification credentials are not applied (EntryStore configures none — see
 * {@link #asRegistration} and {@link #warnOnUnsupportedStaticCredentials}).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "entrystore.auth.saml", name = "enabled", havingValue = "true")
public class RefreshableRelyingPartyRegistrationRepository implements IterableRelyingPartyRegistrationRepository {

	// Minimum delay between background metadata refreshes — matches OpenSAML's own default (5 min) so a
	// short cacheDuration/validUntil in the IdP metadata cannot drive polling faster than this. Clamped to
	// max-age (which can be as low as 60s) so the resolver never gets minRefreshDelay > maxRefreshDelay.
	private static final long MIN_REFRESH_DELAY_SECONDS = 300L;

	private final Saml2RelyingPartyProperties relyingPartyProperties;
	private final SamlCustomConfiguration samlConfiguration;

	// One self-refreshing metadata repository per registration id; the wrapped resolvers are kept so
	// their background reload threads can be stopped on shutdown.
	private final Map<String, AssertingPartyMetadataRepository> metadataByRegistrationId = new ConcurrentHashMap<>();
	private final List<AbstractReloadingMetadataResolver> resolvers = new ArrayList<>();

	public RefreshableRelyingPartyRegistrationRepository(Saml2RelyingPartyProperties relyingPartyProperties,
														 SamlCustomConfiguration samlConfiguration) {
		this.relyingPartyProperties = relyingPartyProperties;
		this.samlConfiguration = samlConfiguration;
	}

	@PostConstruct
	void initialize() {
		OpenSamlInitializationService.initialize();
		Map<String, Registration> registrations = relyingPartyProperties.getRegistration();
		if (registrations.isEmpty()) {
			throw new IllegalStateException("SAML is enabled but no relying-party registrations are configured "
					+ "(spring.security.saml2.relyingparty.registration.*)");
		}
		registrations.forEach((id, registration) -> {
			String metadataUri = registration.getAssertingparty().getMetadataUri();
			if (!StringUtils.hasText(metadataUri)) {
				throw new IllegalStateException("Missing assertingparty.metadata-uri for SAML registration '" + id + "'");
			}
			warnOnUnsupportedStaticCredentials(id, registration);
			long maxAge = maxAgeSeconds(id);
			AbstractReloadingMetadataResolver resolver = buildRefreshingResolver(id, metadataUri, maxAge);
			resolvers.add(resolver);
			metadataByRegistrationId.put(id, new OpenSaml4AssertingPartyMetadataRepository(resolver));
			log.info("SAML IdP metadata for registration '{}' auto-refreshes (max-age {}s) from {}", id, maxAge, metadataUri);
		});
	}

	@PreDestroy
	void shutdown() {
		resolvers.forEach(resolver -> {
			try {
				resolver.destroy();
			} catch (RuntimeException e) {
				log.warn("Failed to stop SAML metadata resolver '{}' during shutdown", resolver.getId(), e);
			}
		});
	}

	@Override
	public RelyingPartyRegistration findByRegistrationId(String registrationId) {
		AssertingPartyMetadataRepository metadataRepository = metadataByRegistrationId.get(registrationId);
		if (metadataRepository == null) {
			return null;
		}
		Registration registration = relyingPartyProperties.getRegistration().get(registrationId);
		AssertingPartyMetadata metadata = resolveAssertingPartyMetadata(registrationId, metadataRepository,
				registration.getAssertingparty().getEntityId());
		if (metadata == null) {
			return null;
		}
		return asRegistration(registrationId, registration, metadata);
	}

	@Override
	public Iterator<RelyingPartyRegistration> iterator() {
		return metadataByRegistrationId.keySet().stream()
				.map(this::findByRegistrationId)
				.filter(Objects::nonNull)
				.iterator();
	}

	private long maxAgeSeconds(String registrationId) {
		SamlCustomConfiguration.Idp idp = samlConfiguration.idp().get(registrationId);
		return idp != null ? idp.metadata().maxAge() : SamlCustomConfiguration.Idp.Metadata.DEFAULT_MAX_AGE_SECONDS;
	}

	/**
	 * Builds an OpenSAML resolver that reloads the metadata in the background, capped at {@code maxAge}.
	 * Mirrors how {@code OpenSaml4AssertingPartyMetadataRepository.withTrustedMetadataLocation} constructs
	 * its resolver (a {@link ResourceBackedMetadataResolver} over a Spring resource), adding the
	 * refresh-interval and require-valid-metadata configuration the Spring builder does not expose.
	 *
	 * <p>Spring Boot 4 / Spring Security 7 touch-point: swap {@link OpenSaml4AssertingPartyMetadataRepository}
	 * for {@code OpenSaml5AssertingPartyMetadataRepository} and the OpenSAML 4 resolver for its OpenSAML 5
	 * equivalent. The rest of this class uses version-neutral Spring Security APIs.
	 */
	private AbstractReloadingMetadataResolver buildRefreshingResolver(String id, String metadataUri, long maxAgeSeconds) {
		try {
			ResourceBackedMetadataResolver resolver = new ResourceBackedMetadataResolver(
					new SpringMetadataResource(new DefaultResourceLoader().getResource(metadataUri)));
			resolver.setId("entrystore-saml-idp-" + id);
			resolver.setParserPool(XMLObjectProviderRegistrySupport.getParserPool());
			// Required so the asserting-party repository can resolve/iterate IDPSSODescriptor entities.
			resolver.setIndexes(Set.of(new RoleMetadataIndex()));
			resolver.setMaxRefreshDelay(Duration.ofSeconds(maxAgeSeconds));
			resolver.setMinRefreshDelay(Duration.ofSeconds(Math.min(maxAgeSeconds, MIN_REFRESH_DELAY_SECONDS)));
			resolver.setRequireValidMetadata(true);
			// initialize() performs the initial fetch and fails fast (context startup) if it is unreachable,
			// matching the previous fetch-once-at-startup behaviour; later refresh failures keep the last
			// good metadata.
			resolver.initialize();
			return resolver;
		} catch (Exception e) {
			throw new IllegalStateException(
					"Failed to initialize auto-refreshing SAML metadata resolver for registration '" + id + "'", e);
		}
	}

	private AssertingPartyMetadata resolveAssertingPartyMetadata(String registrationId,
																 AssertingPartyMetadataRepository metadataRepository,
																 String configuredEntityId) {
		if (StringUtils.hasText(configuredEntityId)) {
			AssertingPartyMetadata metadata = metadataRepository.findByEntityId(configuredEntityId);
			if (metadata == null) {
				log.warn("SAML registration '{}' configures assertingparty.entity-id '{}', which is not present in the "
						+ "current IdP metadata; SAML login for this registration will fail until the metadata or the "
						+ "configured entity-id is corrected.", registrationId, configuredEntityId);
			}
			return metadata;
		}
		Iterator<AssertingPartyMetadata> iterator = metadataRepository.iterator();
		if (!iterator.hasNext()) {
			log.warn("Current IdP metadata for SAML registration '{}' contains no asserting party; SAML login for this "
					+ "registration will fail.", registrationId);
			return null;
		}
		AssertingPartyMetadata first = iterator.next();
		if (iterator.hasNext()) {
			log.warn("Metadata for SAML registration '{}' contains multiple asserting parties; using the first. "
					+ "Configure spring.security.saml2.relyingparty.registration.{}.assertingparty.entity-id "
					+ "to select one explicitly.", registrationId, registrationId);
		}
		return first;
	}

	/**
	 * Maps the relying-party (SP) side onto the freshly resolved asserting-party metadata. Kept aligned
	 * with Spring Boot's {@code Saml2RelyingPartyRegistrationConfiguration#asRegistration} so behaviour is
	 * identical to the auto-configuration this bean replaces. Static SP signing/decryption credentials and
	 * statically configured verification credentials are intentionally not applied here — EntryStore's SAML
	 * SP carries none (verification credentials come from the IdP metadata); see
	 * {@link #warnOnUnsupportedStaticCredentials}.
	 */
	private RelyingPartyRegistration asRegistration(String id, Registration properties, AssertingPartyMetadata metadata) {
		return RelyingPartyRegistration.withAssertingPartyMetadata(metadata)
				.registrationId(id)
				.entityId(properties.getEntityId())
				.assertionConsumerServiceLocation(properties.getAcs().getLocation())
				.assertionConsumerServiceBinding(properties.getAcs().getBinding())
				.assertingPartyMetadata(mapAssertingParty(properties.getAssertingparty()))
				.singleLogoutServiceLocation(properties.getSinglelogout().getUrl())
				.singleLogoutServiceResponseLocation(properties.getSinglelogout().getResponseUrl())
				.singleLogoutServiceBinding(properties.getSinglelogout().getBinding())
				.nameIdFormat(properties.getNameIdFormat())
				.build();
	}

	// Copied from Spring Boot's Saml2RelyingPartyRegistrationConfiguration#mapAssertingParty: overrides the
	// metadata-derived asserting-party fields with any explicitly configured property (non-null only).
	private Consumer<AssertingPartyMetadata.Builder<?>> mapAssertingParty(AssertingParty assertingParty) {
		return (details) -> {
			PropertyMapper map = PropertyMapper.get().alwaysApplyingWhenNonNull();
			map.from(assertingParty::getEntityId).to(details::entityId);
			map.from(assertingParty.getSinglesignon()::getBinding).to(details::singleSignOnServiceBinding);
			map.from(assertingParty.getSinglesignon()::getUrl).to(details::singleSignOnServiceLocation);
			map.from(assertingParty.getSinglesignon()::getSignRequest).to(details::wantAuthnRequestsSigned);
			map.from(assertingParty.getSinglelogout()::getUrl).to(details::singleLogoutServiceLocation);
			map.from(assertingParty.getSinglelogout()::getResponseUrl).to(details::singleLogoutServiceResponseLocation);
			map.from(assertingParty.getSinglelogout()::getBinding).to(details::singleLogoutServiceBinding);
		};
	}

	private void warnOnUnsupportedStaticCredentials(String id, Registration registration) {
		boolean hasStaticCredentials = !registration.getSigning().getCredentials().isEmpty()
				|| !registration.getDecryption().getCredentials().isEmpty()
				|| !registration.getAssertingparty().getVerification().getCredentials().isEmpty();
		if (hasStaticCredentials) {
			log.warn("SAML registration '{}' configures static signing/decryption/verification credentials, which the "
					+ "auto-refreshing metadata repository does not apply; IdP verification credentials are taken from "
					+ "the refreshed metadata. Remove them or extend RefreshableRelyingPartyRegistrationRepository.", id);
		}
	}
}
