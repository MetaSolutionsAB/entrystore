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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.shibboleth.shared.resolver.ResolverException;
import org.entrystore.rest.springboot.configuration.ConditionalOnBooleanConfig;
import org.entrystore.rest.springboot.configuration.SamlCustomConfiguration;
import org.entrystore.rest.springboot.util.LogThrottle;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.metadata.resolver.impl.AbstractReloadingMetadataResolver;
import org.opensaml.saml.metadata.resolver.impl.ResourceBackedMetadataResolver;
import org.opensaml.saml.metadata.resolver.index.impl.RoleMetadataIndex;
import org.opensaml.saml.saml2.metadata.SingleSignOnService;
import org.slf4j.event.Level;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyProperties;
import org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyProperties.AssertingParty;
import org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyProperties.AssertingParty.Singlesignon;
import org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyProperties.Registration;
import org.springframework.security.saml2.core.OpenSamlInitializationService;
import org.springframework.security.saml2.provider.service.registration.AssertingPartyMetadata;
import org.springframework.security.saml2.provider.service.registration.AssertingPartyMetadataRepository;
import org.springframework.security.saml2.provider.service.registration.IterableRelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.OpenSaml5AssertingPartyMetadataRepository;
import org.springframework.security.saml2.provider.service.registration.OpenSamlAssertingPartyDetails;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>An IdP whose {@code http(s)} metadata cannot be fetched at startup does not stop EntryStore from starting: a
 * WARN names it, and {@link #findByRegistrationId} returns {@code null} for it until a background retry or a login
 * (see {@link #ensureLoaded}) loads the metadata. Unreadable {@code file:} or {@code classpath:} metadata is a
 * configuration error and still fails startup.
 *
 * <p>{@link #findByRegistrationId(String)} builds the {@link RelyingPartyRegistration} fresh from the
 * live (background-refreshed) metadata on each call; Spring Security resolves the registration per
 * request, so refreshes are picked up by subsequent logins with no re-wiring of the security filter
 * chain. The relying-party (SP) side of each registration is mapped as Spring Boot's
 * {@code Saml2RelyingPartyRegistrationConfiguration} does, except that a configured single sign-on binding
 * is sent to the metadata's endpoint for that binding ({@link #mapSingleSignOn}), and that statically
 * configured SP signing/decryption and verification credentials are not applied (EntryStore configures
 * none — see {@link #asRegistration} and {@link #warnOnUnsupportedStaticCredentials}).
 */
@Slf4j
@Component
@ConditionalOnBooleanConfig("entrystore.auth.saml.enabled")
public class RefreshableRelyingPartyRegistrationRepository implements IterableRelyingPartyRegistrationRepository {

	// Minimum delay between background metadata refreshes — matches OpenSAML's own default (5 min) so a
	// short cacheDuration/validUntil in the IdP metadata cannot drive polling faster than this. Clamped to
	// max-age (which can be as low as 60s) so the resolver never gets minRefreshDelay > maxRefreshDelay.
	private static final long MIN_REFRESH_DELAY_SECONDS = 300L;

	// How often a login may trigger a fetch of metadata that has never loaded: often enough that a recovered IdP is
	// usable on the next login, rarely enough that anonymous logins cannot turn an outage into a fetch storm.
	static final Duration ON_DEMAND_REFRESH_INTERVAL = Duration.ofSeconds(30);

	private final Saml2RelyingPartyProperties relyingPartyProperties;
	private final SamlCustomConfiguration samlConfiguration;
	private final Ticker ticker;

	// One self-refreshing metadata source per registration id; the resolvers are kept so their background reload
	// threads can be stopped on shutdown.
	private final Map<String, IdpMetadata> idps = new ConcurrentHashMap<>();
	// Every login rebuilds its registration, so a missing single sign-on endpoint is warned about once per id.
	private final Set<String> missingSsoEndpointWarned = ConcurrentHashMap.newKeySet();

	public RefreshableRelyingPartyRegistrationRepository(Saml2RelyingPartyProperties relyingPartyProperties,
														 SamlCustomConfiguration samlConfiguration, Ticker ticker) {
		this.relyingPartyProperties = relyingPartyProperties;
		this.samlConfiguration = samlConfiguration;
		this.ticker = ticker;
	}

	@PostConstruct
	void initialize() {
		OpenSamlInitializationService.initialize();
		Map<String, Registration> registrations = relyingPartyProperties.getRegistration();
		if (registrations.isEmpty()) {
			throw new IllegalStateException(noRegistrationsMessage());
		}
		registrations.forEach((id, registration) -> {
			String metadataUri = registration.getAssertingparty().getMetadataUri();
			if (!StringUtils.hasText(metadataUri)) {
				throw new IllegalStateException("Missing assertingparty.metadata-uri for SAML registration '" + id + "'");
			}
			warnOnUnsupportedStaticCredentials(id, registration);
			long maxAge = maxAgeSeconds(id);
			AbstractReloadingMetadataResolver resolver = buildRefreshingResolver(id, metadataUri, maxAge);
			var idp = new IdpMetadata(resolver, new OpenSaml5AssertingPartyMetadataRepository(resolver),
					new LogThrottle(ON_DEMAND_REFRESH_INTERVAL, ticker::read), new AtomicBoolean());
			idps.put(id, idp);
			if (idp.isLoaded()) {
				log.info("SAML IdP metadata for registration '{}' auto-refreshes (max-age {}s) from {}", id, maxAge, metadataUri);
			} else {
				log.warn("SAML IdP metadata for registration '{}' could not be loaded from {}; SAML login with it fails "
								+ "until the metadata is reachable. Retrying every {}s, and on login at most every {}s.",
						id, metadataUri, minRefreshDelaySeconds(maxAge), ON_DEMAND_REFRESH_INTERVAL.toSeconds());
			}
		});
	}

	@PreDestroy
	void shutdown() {
		idps.values().forEach(idp -> {
			try {
				idp.resolver().destroy();
			} catch (RuntimeException e) {
				log.warn("Failed to stop SAML metadata resolver '{}' during shutdown", idp.resolver().getId(), e);
			}
		});
	}

	@Override
	public RelyingPartyRegistration findByRegistrationId(String registrationId) {
		IdpMetadata idp = idps.get(registrationId);
		if (idp == null) {
			return null;
		}
		if (!ensureLoaded(registrationId, idp)) {
			log.debug("No IdP metadata loaded for SAML registration '{}'", registrationId);
			return null;
		}
		Registration registration = relyingPartyProperties.getRegistration().get(registrationId);
		AssertingPartyMetadata metadata = resolveAssertingPartyMetadata(registrationId, idp.assertingParties(),
				registration.getAssertingparty().getEntityId());
		if (metadata == null) {
			return null;
		}
		return asRegistration(registrationId, registration, metadata);
	}

	/**
	 * Whether {@code registrationId} is configured but no registration can be built for it, because its IdP metadata
	 * has not loaded or lacks the configured asserting party: a login with it cannot start.
	 */
	public boolean isConfiguredButUnavailable(String registrationId) {
		return registrationId != null && idps.containsKey(registrationId) && findByRegistrationId(registrationId) == null;
	}

	@Override
	public Iterator<RelyingPartyRegistration> iterator() {
		return idps.keySet().stream()
				.map(this::findByRegistrationId)
				.filter(Objects::nonNull)
				.iterator();
	}

	/**
	 * The registration whose asserting party has {@code entityId}, or {@code null} when none or more than one
	 * has. The SAML response converter falls back to this for an IdP-initiated response posted to a URL that
	 * names no registration, such as the 5.x single-IdP assertion consumer service URL; the inherited default
	 * would look the entity id up as a registration id.
	 */
	@Override
	public RelyingPartyRegistration findUniqueByAssertingPartyEntityId(String entityId) {
		// The indexed metadata lookup first, so only registrations whose IdP metadata has that entity are built. No
		// fetch: any anonymous ACS POST gets here, and would fetch every unloaded IdP in turn.
		List<RelyingPartyRegistration> matches = idps.entrySet().stream()
				.filter(entry -> entry.getValue().isLoaded())
				.filter(entry -> entry.getValue().assertingParties().findByEntityId(entityId) != null)
				.map(entry -> findByRegistrationId(entry.getKey()))
				.filter(registration -> registration != null
						&& entityId.equals(registration.getAssertingPartyMetadata().getEntityId()))
				.limit(2)
				.toList();
		return matches.size() == 1 ? matches.getFirst() : null;
	}

	/**
	 * Whether the IdP's metadata has loaded, fetching it first if it never has and the registration's
	 * {@link #ON_DEMAND_REFRESH_INTERVAL} has passed. Only the caller that wins the throttle fetches; the others,
	 * and any caller while that fetch is still running, return at once instead of waiting for it.
	 */
	private boolean ensureLoaded(String registrationId, IdpMetadata idp) {
		// Unlocked read: refresh() holds the resolver's lock for the whole fetch, which logins must not wait on.
		if (idp.isLoaded() || !idp.onDemandRefresh().tryAcquire() || !idp.fetching().compareAndSet(false, true)) {
			return idp.isLoaded();
		}
		AbstractReloadingMetadataResolver resolver = idp.resolver();
		try {
			// refresh() and destroy() lock the resolver; refresh() after destroy() throws, so re-check under the lock.
			synchronized (resolver) {
				if (!resolver.isDestroyed() && !idp.isLoaded()) {
					resolver.refresh();
				}
			}
		} catch (ResolverException e) {
			log.warn("SAML IdP metadata for registration '{}' is still unavailable; retrying on login at most every "
					+ "{}s: {}", registrationId, ON_DEMAND_REFRESH_INTERVAL.toSeconds(), e.getMessage());
		} finally {
			idp.fetching().set(false);
		}
		return idp.isLoaded();
	}

	private static long minRefreshDelaySeconds(long maxAgeSeconds) {
		return Math.min(maxAgeSeconds, MIN_REFRESH_DELAY_SECONDS);
	}

	private long maxAgeSeconds(String registrationId) {
		SamlCustomConfiguration.Idp idp = samlConfiguration.idp().get(registrationId);
		return idp != null ? idp.metadata().maxAge() : SamlCustomConfiguration.Idp.Metadata.DEFAULT_MAX_AGE_SECONDS;
	}

	/**
	 * Names the IdP ids that EntryStore settings refer to, so an operator whose {@code entrystore.auth.saml.*}
	 * settings survived an upgrade sees which registrations are missing rather than only the Spring key prefix.
	 */
	private String noRegistrationsMessage() {
		Set<String> idpIds = new TreeSet<>(samlConfiguration.idp().keySet());
		if (StringUtils.hasText(samlConfiguration.defaultIdp())) {
			idpIds.add(samlConfiguration.defaultIdp());
		}
		String message = "SAML is enabled but no relying-party registrations are configured "
				+ "(spring.security.saml2.relyingparty.registration.*).";
		if (idpIds.isEmpty()) {
			return message;
		}
		return message + " entrystore.auth.saml.* refers to the IdP ids " + idpIds + "; set "
				+ "spring.security.saml2.relyingparty.registration.<id>.assertingparty.metadata-uri for each of them.";
	}

	/**
	 * Builds an OpenSAML resolver that reloads the metadata in the background, capped at {@code maxAge}.
	 * Mirrors how {@code OpenSaml5AssertingPartyMetadataRepository.withTrustedMetadataLocation} constructs
	 * its resolver (a {@link ResourceBackedMetadataResolver} over a Spring resource), adding the
	 * refresh-interval and require-valid-metadata configuration the Spring builder does not expose.
	 *
	 * <p>Uses the OpenSAML 5 backend (Spring Security 7); the rest of this class uses version-neutral
	 * Spring Security APIs.
	 */
	private AbstractReloadingMetadataResolver buildRefreshingResolver(String id, String metadataUri, long maxAgeSeconds) {
		try {
			SpringMetadataResource resource = SpringMetadataResource.forLocation(metadataUri);
			ResourceBackedMetadataResolver resolver = new ResourceBackedMetadataResolver(resource);
			resolver.setId("entrystore-saml-idp-" + id);
			resolver.setParserPool(XMLObjectProviderRegistrySupport.getParserPool());
			// Required so the asserting-party repository can resolve/iterate IDPSSODescriptor entities.
			resolver.setIndexes(Set.of(new RoleMetadataIndex()));
			resolver.setMaxRefreshDelay(Duration.ofSeconds(maxAgeSeconds));
			resolver.setMinRefreshDelay(Duration.ofSeconds(minRefreshDelaySeconds(maxAgeSeconds)));
			resolver.setRequireValidMetadata(true);
			// initialize() performs the initial fetch. An unreachable IdP must not stop EntryStore from starting, so a
			// remote resolver then starts empty and retries; unreadable local metadata is a configuration error.
			resolver.setFailFastInitialization(!resource.isRemote());
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
	 * with Spring Boot's {@code Saml2RelyingPartyRegistrationConfiguration#asRegistration} so behaviour
	 * matches the auto-configuration this bean replaces, except for the single sign-on endpoint of a
	 * configured binding ({@link #mapSingleSignOn}). Static SP signing/decryption credentials and
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
				.assertingPartyMetadata(mapAssertingParty(id, properties.getAssertingparty(), metadata))
				.singleLogoutServiceLocation(properties.getSinglelogout().getUrl())
				.singleLogoutServiceResponseLocation(properties.getSinglelogout().getResponseUrl())
				.singleLogoutServiceBinding(properties.getSinglelogout().getBinding())
				.nameIdFormat(properties.getNameIdFormat())
				.build();
	}

	// Copied from Spring Boot's Saml2RelyingPartyRegistrationConfiguration#mapAssertingParty, except for the
	// single sign-on endpoint: overrides the metadata-derived asserting-party fields with any explicitly
	// configured property (non-null only).
	private Consumer<AssertingPartyMetadata.Builder<?>> mapAssertingParty(String id, AssertingParty assertingParty,
																		  AssertingPartyMetadata metadata) {
		return (details) -> {
			// Boot 4's PropertyMapper filters null source values by default, so the former
			// alwaysApplyingWhenNonNull() is no longer needed (and was removed).
			PropertyMapper map = PropertyMapper.get();
			map.from(assertingParty::getEntityId).to(details::entityId);
			mapSingleSignOn(id, assertingParty.getSinglesignon(), metadata, details);
			map.from(assertingParty.getSinglesignon()::getSignRequest).to(details::wantAuthnRequestsSigned);
			map.from(assertingParty.getSinglelogout()::getUrl).to(details::singleLogoutServiceLocation);
			map.from(assertingParty.getSinglelogout()::getResponseUrl).to(details::singleLogoutServiceResponseLocation);
			map.from(assertingParty.getSinglelogout()::getBinding).to(details::singleLogoutServiceBinding);
		};
	}

	/**
	 * Applies the configured single sign-on binding together with the metadata's endpoint for it, unless a URL
	 * is configured too. Spring Boot overrides only the binding and keeps the URL of the metadata's first POST
	 * or Redirect endpoint, so with an IdP that publishes one URL per binding the request would reach the wrong
	 * endpoint. Without an endpoint for the configured binding, it is sent to that first endpoint's URL, and a WARN,
	 * logged once per registration, says so.
	 */
	private void mapSingleSignOn(String id, Singlesignon singleSignOn, AssertingPartyMetadata metadata,
								 AssertingPartyMetadata.Builder<?> details) {
		Saml2MessageBinding binding = singleSignOn.getBinding();
		String url = singleSignOn.getUrl();
		if (binding != null && url == null) {
			url = singleSignOnLocation(metadata, binding);
			if (url == null) {
				log.atLevel(missingSsoEndpointWarned.add(id) ? Level.WARN : Level.DEBUG).log("IdP metadata for SAML "
								+ "registration '{}' has no single sign-on endpoint with the configured {} binding; "
								+ "sending it to {}. Set ...singlesignon.url to choose the endpoint.",
						id, binding, metadata.getSingleSignOnServiceLocation());
			}
		}
		PropertyMapper map = PropertyMapper.get();
		map.from(binding).to(details::singleSignOnServiceBinding);
		map.from(url).to(details::singleSignOnServiceLocation);
	}

	private static String singleSignOnLocation(AssertingPartyMetadata metadata, Saml2MessageBinding binding) {
		if (!(metadata instanceof OpenSamlAssertingPartyDetails openSamlMetadata)) {
			return null;
		}
		return openSamlMetadata.getEntityDescriptor().getIDPSSODescriptor(SAMLConstants.SAML20P_NS)
				.getSingleSignOnServices().stream()
				.filter(service -> binding.getUrn().equals(service.getBinding()))
				.map(SingleSignOnService::getLocation)
				.filter(StringUtils::hasText)
				.findFirst()
				.orElse(null);
	}

	/**
	 * The metadata source of one registration and the gates on the fetches its logins trigger: a fetch can outlast
	 * the throttle interval, and a second one would only wait on the resolver's lock.
	 */
	private record IdpMetadata(AbstractReloadingMetadataResolver resolver,
							   AssertingPartyMetadataRepository assertingParties,
							   LogThrottle onDemandRefresh,
							   AtomicBoolean fetching) {

		// lastUpdate is set only when metadata is actually loaded, unlike the refresh-success flags.
		boolean isLoaded() {
			return resolver.getLastUpdate() != null;
		}
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
