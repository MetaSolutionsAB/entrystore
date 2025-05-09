/*
 * Copyright (c) 2007-2025 MetaSolutions AB
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

package org.entrystore.rest.resources;

import com.coveo.saml.SamlClient;
import com.coveo.saml.SamlException;
import com.coveo.saml.SamlResponse;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.EntryStoreApplication;
import org.entrystore.rest.auth.BasicVerifier;
import org.entrystore.rest.auth.CookieVerifier;
import org.entrystore.rest.util.HttpUtil;
import org.entrystore.rest.util.SimpleHTML;
import org.entrystore.rest.util.Util;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.CacheDirective;
import org.restlet.data.Form;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.time.temporal.ChronoUnit.MILLIS;

/**
 * TODO Support logout via IdP
 *
 * @author Hannes Ebner
 */
public class SamlLoginResource extends BaseResource {

	private static final Logger log = LoggerFactory.getLogger(SamlLoginResource.class);

	private static Map<String, SamlIdpInfo> samlIDPs;

	private static String redirSuccess;

	private static String redirFailure;

	private static final Object mutex = new Object();

	private static Config config;

	private static List<String> redirectDomainWhitelist;

	private static final Cache<String, Map<String, String>> relayStateCache;

	@Getter
	private static String defaultIdp;

	@Getter
	@Setter
	@NoArgsConstructor
	protected static class SamlIdpInfo {

		private String id;

		private SamlClient samlClient;

		private String relyingPartyId;

		private String metadataUrl;

		private String assertionConsumerServiceUrl;

		private Instant metadataLoaded;

		private long metadataMaxAge;

		private List<String> domains;

		private boolean autoProvisioning;

		private String redirectMethod;

	}

	static {
		Security.addProvider(new BouncyCastleProvider());
		relayStateCache = CacheBuilder.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).build();
	}

	@Override
	public void init(Context c, Request request, Response response) {
		super.init(c, request, response);

		synchronized (mutex) {
			if (config == null) {
				config = getRM().getConfiguration();
			}

			if (samlIDPs == null) {
				String assertionConsumerServiceBaseUrl = config.getString(Settings.AUTH_SAML_ASSERTION_CONSUMER_SERVICE_URL);
				if (assertionConsumerServiceBaseUrl == null) {
					log.error("No SAML Assertion Consumer Service Base URL configured");
					return;
				} else {
					log.info("SAML Assertion Consumer Service Base URL: {}", assertionConsumerServiceBaseUrl);
				}

				redirectDomainWhitelist = config.getStringList(Settings.AUTH_SAML_REDIRECT_DOMAIN_WHITELIST, new ArrayList<>());
				for (String domain : redirectDomainWhitelist) {
					log.info("Allowed domain for redirects: {}", domain);
				}

				List<String> idps = config.getStringList(Settings.AUTH_SAML_IDPS);
				if (idps == null) {
					return;
				}

				samlIDPs = new HashMap<>();

				for (String idp : idps) {
					SamlIdpInfo idpInfo = new SamlIdpInfo();
					idpInfo.setId(idp);
					idpInfo.setDomains(config.getStringList(idpSetting(Settings.AUTH_SAML_IDP_DOMAINS, idp), List.of("*")));
					idpInfo.setRelyingPartyId(config.getString(idpSetting(Settings.AUTH_SAML_IDP_RELYING_PARTY_ID, idp)));
					idpInfo.setMetadataUrl(config.getString(idpSetting(Settings.AUTH_SAML_IDP_METADATA_URL, idp)));
					idpInfo.setMetadataMaxAge(config.getLong(idpSetting(Settings.AUTH_SAML_IDP_METADATA_MAXAGE, idp), 604800) * 1000);
					idpInfo.setAutoProvisioning(config.getBoolean(idpSetting(Settings.AUTH_SAML_IDP_USER_AUTO_PROVISIONING, idp), false));
					idpInfo.setRedirectMethod(config.getString(idpSetting(Settings.AUTH_SAML_IDP_REDIRECT_METHOD, idp), "get"));

					String acsWithIdp = assertionConsumerServiceBaseUrl;
					if (acsWithIdp.contains("?")) {
						acsWithIdp += "&";
					} else {
						acsWithIdp += "?";
					}
					acsWithIdp += "idp=" + idpInfo.getId();
					idpInfo.setAssertionConsumerServiceUrl(acsWithIdp);

					if (idpInfo.getRelyingPartyId() != null && idpInfo.getMetadataUrl() != null && idpInfo.getAssertionConsumerServiceUrl() != null) {
						samlIDPs.put(idp, idpInfo);
						//loadMetadataAndInitSamlClient(idpInfo);
					} else {
						log.error("Configuration of SAML IdP \"{}\" is incomplete and its SAML client was therefore not loaded", idp);
					}

					logIdpInfo(idpInfo);
				}

				redirSuccess = config.getString(Settings.AUTH_SAML_REDIRECT_SUCCESS_URL);
				redirFailure = config.getString(Settings.AUTH_SAML_REDIRECT_FAILURE_URL);
			}

			if (defaultIdp == null) {
				defaultIdp = config.getString(Settings.AUTH_SAML_DEFAULT_IDP);
				log.info("SAML Default IdP: {}", defaultIdp);
			}
		}
	}

	private void logIdpInfo(SamlIdpInfo info) {
		String prefix = "SAML IdP \"" + info.getId() + "\" -";
		log.info("{} Domains: {}", prefix, info.getDomains());
		log.info("{} Relying Party ID: {}", prefix, info.getRelyingPartyId());
		log.info("{} Metadata URL: {}", prefix, info.getMetadataUrl());
		log.info("{} Metadata Max Age: {} seconds", prefix, info.getMetadataMaxAge() / 1000);
		log.info("{} Assertion Consumer Service URL: {}", prefix, info.getAssertionConsumerServiceUrl());
		log.info("{} Auto Provisioning: {}", prefix, info.isAutoProvisioning());
		log.info("{} Redirect Method: {}", prefix, info.getRedirectMethod());
		log.info("{} Client: {}", prefix, info.getSamlClient() != null ? "initialized" : "not initialized");
	}

	private void loadMetadataAndInitSamlClient(SamlIdpInfo samlIdpInfo) throws SamlException {
		try {
			Reader idpMetadataReader = new BufferedReader(new InputStreamReader(URI.create(samlIdpInfo.getMetadataUrl()).toURL().openStream(), StandardCharsets.UTF_8));
			samlIdpInfo.setSamlClient(SamlClient.fromMetadata(samlIdpInfo.getRelyingPartyId(), samlIdpInfo.getAssertionConsumerServiceUrl(), idpMetadataReader));
			samlIdpInfo.setMetadataLoaded(Instant.now());
			log.info("Loaded SAML metadata for IdP \"{}\" from {}", samlIdpInfo.getId(), samlIdpInfo.getMetadataUrl());
		} catch (IOException e) {
			log.error(e.getMessage());
		}
	}

	private void checkAndInitSamlClient(SamlIdpInfo info) throws SamlException {
		synchronized (mutex) {
			if (info.getMetadataLoaded() == null || (MILLIS.between(Instant.now(), info.getMetadataLoaded()) > info.getMetadataMaxAge())) {
				log.info("Loading SAML metadata for \"{}\"", info.getId());
				loadMetadataAndInitSamlClient(info);
			}
		}
	}

	@Get
	public Representation represent() {
		SamlIdpInfo idpInfo = findIdpForRequest(getRequest());
		if (idpInfo == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return new StringRepresentation("No matching IdP configuration found for request");
		}

		try {
			checkAndInitSamlClient(idpInfo);
			redirectToIdentityProvider(idpInfo, getResponse());
		} catch (SamlException e) {
			log.error(e.getMessage());
			getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
			return new StringRepresentation(e.getMessage());
		}
		return getResponseEntity();
	}

	@Post
	public void store(Representation r) {
		if (HttpUtil.isLargerThan(r, 524288)) {
			log.warn("The size of the representation is larger than 512KB or unknown, similar requests may be blocked in future versions");
		}

		SamlIdpInfo idpInfo = findIdpForRequest(getRequest());
		if (idpInfo == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			getResponse().setEntity(new StringRepresentation("No matching IdP configuration found for request"));
			return;
		}

		try {
			checkAndInitSamlClient(idpInfo);
		} catch (SamlException e) {
			getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
			getResponse().setEntity(new StringRepresentation(e.getMessage()));
			return;
		}

		boolean html = MediaType.TEXT_HTML.equals(getRequest().getClientInfo().getPreferredMediaType(Arrays.asList(MediaType.TEXT_HTML, MediaType.APPLICATION_ALL)));
		boolean authSuccess = false;

		Form samlResponseForm = new Form(r);
		String encodedResponse = samlResponseForm.getFirstValue("SAMLResponse");

		if (encodedResponse != null) {
			String redirectSuccessUrlFromRelayState = null;
			String redirectFailureUrlFromRelayState = null;

			String relayStateToken = samlResponseForm.getFirstValue("RelayState");
			if (relayStateToken != null) {
				log.debug("Received token via SAML RelayState: {}", relayStateToken);
				Map<String, String> relayStateInfo = relayStateCache.getIfPresent(relayStateToken);
				if (relayStateInfo != null) {
					if (relayStateInfo.containsKey("successurl")) {
						redirectSuccessUrlFromRelayState = relayStateInfo.get("successurl");
					}
					if (relayStateInfo.containsKey("failureurl")) {
						redirectFailureUrlFromRelayState = relayStateInfo.get("failureurl");
					}
				} else {
					log.debug("Token {} not found in relay state cache", relayStateToken);
				}
			}

			String userName = null;
			try {
				SamlResponse samlResponse = idpInfo.getSamlClient().decodeAndValidateSamlResponse(encodedResponse, "POST");
				userName = samlResponse.getNameID();
				log.info("Successfully authenticated via SAML IdP \"{}\": {}", idpInfo.getId(), userName);
			} catch (SamlException e) {
				log.error(e.getMessage());
			}

			if ("admin".equalsIgnoreCase(userName)) {
				log.warn("Ignoring received username \"admin\" from SAML IdP \"{}\"", idpInfo.getId());
				userName = null;
			}

			if (userName != null && !BasicVerifier.userExists(getPM(), userName)) {
				if (!idpInfo.isAutoProvisioning()) {
					log.warn("User auto-provisioning is deactivated for IdP \"{}\"", idpInfo.getId());
				} else {
					PrincipalManager pm = getPM();
					URI authUser = pm.getAuthenticatedUserURI();
					try {
						pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

						// Create user
						Entry entry = pm.createResource(null, GraphType.User, null, null);
						if (entry != null) {
							User u = (User) entry.getResource();
							log.info("Created user {}", u.getURI());
							pm.setPrincipalName(entry.getResourceURI(), userName);
							// TODO set some basic metadata, if we can get it from the SAML server
							// Signup.setFoafMetadata(entry, new org.restlet.security.User(...));
						} else {
							log.error("An error occured when creating the new user");
						}
					} finally {
						pm.setAuthenticatedUserURI(authUser);
					}
				}
			}

			if (userName != null && BasicVerifier.userExists(getPM(), userName) && !BasicVerifier.isUserDisabled(getPM(), userName)) {
				EntryStoreApplication app = (EntryStoreApplication) getApplication();
				new CookieVerifier(app, getRM()).createAuthToken(userName, null, getRequest(), getResponse());

				if (redirectSuccessUrlFromRelayState != null) {
					log.debug("Redirecting to custom success URL: {}", redirectSuccessUrlFromRelayState);
					getResponse().redirectTemporary(redirectSuccessUrlFromRelayState);
				} else if (redirSuccess != null) {
					log.debug("Redirecting to default success URL: {}", redirSuccess);
					getResponse().redirectTemporary(URLDecoder.decode(redirSuccess, StandardCharsets.UTF_8));
				} else {
					getResponse().setStatus(Status.SUCCESS_OK);
					if (html) {
						getResponse().setEntity(new SimpleHTML("Login").representation("Login successful."));
					}
				}

				authSuccess = true;
			}

			if (!authSuccess) {
				log.info("Login failed with username {} via IdP {}", userName, idpInfo.getId());
				if (redirectFailureUrlFromRelayState != null) {
					log.debug("Redirecting to custom failure URL: {}", redirectFailureUrlFromRelayState);
					getResponse().redirectTemporary(redirectFailureUrlFromRelayState);
				} else if (redirFailure != null) {
					log.debug("Redirecting to default failure URL: {}", redirFailure);
					getResponse().redirectTemporary(URLDecoder.decode(redirFailure, StandardCharsets.UTF_8));
				} else {
					getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
					if (html) {
						getResponse().setEntity(new SimpleHTML("Login").representation("Login failed."));
					}
				}
			}
		} else {
			try {
				redirectToIdentityProvider(idpInfo, getResponse());
			} catch (SamlException e) {
				log.error(e.getMessage());
			}
		}
	}

	private void redirectToIdentityProvider(SamlIdpInfo idpInfo, Response response) throws SamlException {
		Map<String, String> values = new HashMap<>();
		values.put("SAMLRequest", idpInfo.getSamlClient().getSamlRequest());

		Map<String, String> relayStateInfo = new HashMap<>();
		String successUrl = parameters.get("successurl");
		if (isValidRedirectTarget(successUrl)) {
			relayStateInfo.put("successurl", successUrl);
		}
		String failureUrl = parameters.get("failureurl");
		if (isValidRedirectTarget(failureUrl)) {
			relayStateInfo.put("failureurl", failureUrl);
		}

		if (!relayStateInfo.isEmpty()) {
			String relayStateToken = RandomStringUtils.secure().nextAlphanumeric(16);
			relayStateCache.put(relayStateToken, relayStateInfo);
			values.put("RelayState", relayStateToken);
			log.debug("Setting RelayState token {} in SAMLRequest, mapped to redirect URL: {}", relayStateToken, successUrl);
		}

		log.debug("Redirecting to SAML IdP \"{}\" using {}" , idpInfo.getId(), idpInfo.getRedirectMethod().toUpperCase());
		if ("post".equalsIgnoreCase(idpInfo.getRedirectMethod())) {
			redirectWithPost(idpInfo.getSamlClient().getIdentityProviderUrl(), response, values);
		} else {
			redirectWithGet(idpInfo.getSamlClient().getIdentityProviderUrl(), response, values);
		}
	}

	private void redirectWithPost(String url, Response response, Map<String, String> values) {
		StringBuilder sb = new StringBuilder();
		sb.append("<html><head></head><body><form id='TheForm' action='");
		sb.append(StringEscapeUtils.escapeHtml(url));
		sb.append("' method='POST'>");

		for (String key : values.keySet()) {
			String encodedKey = StringEscapeUtils.escapeHtml(key);
			String encodedValue = StringEscapeUtils.escapeHtml(values.get(key));
			sb.append("<input type='hidden' id='").append(encodedKey).
					append("' name='").append(encodedKey).
					append("' value='").append(encodedValue).
					append("'/>");
		}

		sb.append("</form><script type='text/javascript'>document.getElementById('TheForm').submit();</script></body></html>");
		setCacheDirectives(response);
		response.setEntity(new StringRepresentation(sb.toString(), MediaType.TEXT_HTML));
	}

	private void redirectWithGet(String url, Response response, Map<String, String> values) {
		StringBuilder targetUrl = new StringBuilder(url);
		for (String key : values.keySet()) {
			String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
			String encodedValue = URLEncoder.encode(values.get(key), StandardCharsets.UTF_8);
			if (targetUrl.toString().contains("?")) {
				targetUrl.append("&");
			} else {
				targetUrl.append("?");
			}
			targetUrl.append(encodedKey).append("=").append(encodedValue);
		}
		setCacheDirectives(response);
		response.redirectTemporary(targetUrl.toString());
	}

	protected String findIdpForDomain(String domain) {
		String wildcardIdp = null;
		for (SamlIdpInfo idpInfo : getSamlIDPs().values()) {
			if (idpInfo.getDomains().contains("*")) {
				wildcardIdp = idpInfo.getId();
			}
			if (idpInfo.getDomains().contains(domain.toLowerCase())) {
				return idpInfo.getId();
			}
		}
		// we return the IDP matching the wildcard only if we cannot find anything more
		// specific for that particular domain, this way we treat wildcards as fallback
		return wildcardIdp;
	}

	protected SamlIdpInfo findIdpForRequest(Request request) {
		Map<String, String> parameters = Util.parseRequest(request.getResourceRef().getRemainingPart());
		if (parameters.containsKey("username")) {
			String domain = StringUtils.substringAfter(parameters.get("username"), "@");
			if (domain != null && !domain.isEmpty()) {
				return getSamlIDPs().get(findIdpForDomain(domain));
			}
		} else if (parameters.containsKey("idp") && !parameters.get("idp").isEmpty()) {
			return getSamlIDPs().get(parameters.get("idp"));
		} else {
			if (getDefaultIdp() == null || getDefaultIdp().isEmpty()) {
				log.warn("IdP parameter missing and no default IdP configured, unable to properly initialize SAML request");
			}
			return getSamlIDPs().get(getDefaultIdp());
		}
		return null;
	}

	private void setCacheDirectives(Response response) {
		List<CacheDirective> cacheDirs = new ArrayList<>();
		cacheDirs.add(CacheDirective.noCache());
		cacheDirs.add(CacheDirective.noStore());
		response.setCacheDirectives(cacheDirs);
	}

	protected boolean isValidRedirectTarget(String url) {
		if (url != null) {
			return redirectDomainWhitelist.contains(URI.create(url).getHost());
		}
		return false;
	}

	private String idpSetting(String configKey, String idp) {
		return String.format(configKey, idp);
	}

	protected Map<String, SamlIdpInfo> getSamlIDPs() {
		return samlIDPs;
	}

}
