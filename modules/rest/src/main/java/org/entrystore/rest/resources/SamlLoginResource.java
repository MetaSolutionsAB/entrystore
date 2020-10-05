/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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
import org.apache.commons.lang.StringEscapeUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.auth.BasicVerifier;
import org.entrystore.rest.auth.CookieVerifier;
import org.entrystore.rest.util.SimpleHTML;
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
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TODO Support logout via IdP
 *
 * @author Hannes Ebner
 */
public class SamlLoginResource extends BaseResource {

	private static Logger log = LoggerFactory.getLogger(SamlLoginResource.class);

	private static SamlClient samlClient;

	static {
		Security.addProvider(new BouncyCastleProvider());
	}

	@Override
	public void init(Context c, Request request, Response response) {
		super.init(c, request, response);

		if (samlClient == null) {
			Config config = getRM().getConfiguration();

			String relyingPartyId = config.getString(Settings.AUTH_SAML_RELYING_PARTY_ID);
			if (relyingPartyId == null) {
				log.warn("No SAML Relying Party Identifier configured");
			} else {
				log.info("SAML Relying Party Identifier: " + relyingPartyId);
			}

			String assertionConsumerService = config.getString(Settings.AUTH_SAML_ASSERTION_CONSUMER_SERVICE_URL);
			if (assertionConsumerService == null) {
				log.warn("No SAML Assertion Consumer Service URL configured");
			} else {
				log.info("SAML Assertion Consumer Service URL: " + assertionConsumerService);
			}

			String idpMetadata = config.getString(Settings.AUTH_SAML_IDP_METADATA_URL);
			if (idpMetadata == null) {
				log.warn("No SAML IDP Metadata URL configured");
			} else {
				log.info("SAML IDP Metadata URL: " + idpMetadata);
			}

			if (relyingPartyId != null && assertionConsumerService != null && idpMetadata != null) {
				try {
					Reader idpMetadataReader = new BufferedReader(new InputStreamReader(new URL(idpMetadata).openStream()));
					samlClient = SamlClient.fromMetadata(relyingPartyId, assertionConsumerService, idpMetadataReader);
				} catch (IOException | SamlException e) {
					log.error(e.getMessage());
				}
			} else {
				log.error("SAML authentication could not be initialized properly");
			}
		}
	}

	@Get
	public Representation represent() {
		try {
			redirectToIdentityProvider(getResponse());
		} catch (SamlException e) {
			log.error(e.getMessage());
			getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
			return new StringRepresentation(e.getMessage());
		}
		return getResponseEntity();
	}

	@Post
	public void store(Representation r) {
		String redirSuccess = parameters.get("redirectOnSuccess");
		String redirFailure = parameters.get("redirectOnFailure");

		boolean html = MediaType.TEXT_HTML.equals(getRequest().getClientInfo().getPreferredMediaType(Arrays.asList(MediaType.TEXT_HTML, MediaType.APPLICATION_ALL)));

		boolean authSuccess = false;

		String encodedResponse = new Form(r).getFirstValue("SAMLResponse");
		if (encodedResponse != null) {
			String userName = null;
			try {
				SamlResponse samlResponse = samlClient.decodeAndValidateSamlResponse(encodedResponse, "POST");
				userName = samlResponse.getNameID();
				log.info("Successfully authenticated via SAML: " + userName);
			} catch (SamlException e) {
				log.error(e.getMessage());
			}

			if ("admin".equalsIgnoreCase(userName)) {
				userName = null;
			}
			boolean autoProvisioning = "on".equalsIgnoreCase(getRM().getConfiguration().getString(Settings.AUTH_SAML_USER_AUTO_PROVISIONING, "off"));

			if (userName != null && !BasicVerifier.userExists(getPM(), userName)) {
				if (!autoProvisioning) {
					log.warn("User auto-provisioning is deactivated");
				} else {
					PrincipalManager pm = getPM();
					URI authUser = pm.getAuthenticatedUserURI();
					try {
						pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

						// Create user
						Entry entry = pm.createResource(null, GraphType.User, null, null);
						if (entry != null) {
							User u = (User) entry.getResource();
							log.info("Created user " + u.getURI());
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
				new CookieVerifier(getRM()).createAuthToken(userName, null, getResponse());

				// TODO cache SAML ticket together with auth_token (probably necessary for logouts originating from SAML)

				if (redirSuccess != null) {
					try {
						getResponse().redirectTemporary(URLDecoder.decode(redirSuccess, "UTF-8"));
					} catch (UnsupportedEncodingException e) {
						log.warn("Unable to decode URL parameter redirectOnSuccess: " + e.getMessage());
					}
				} else {
					getResponse().setStatus(Status.SUCCESS_OK);
					if (html) {
						getResponse().setEntity(new SimpleHTML("Login").representation("Login successful."));
					}
				}

				authSuccess = true;
			}

			if (!authSuccess) {
				if (redirFailure != null) {
					try {
						getResponse().redirectTemporary(URLDecoder.decode(redirFailure, "UTF-8"));
					} catch (UnsupportedEncodingException e) {
						log.warn("Unable to decode URL parameter redirectOnFailure: " + e.getMessage());
					}
				} else {
					getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
					if (html) {
						getResponse().setEntity(new SimpleHTML("Login").representation("Login failed."));
					}
				}
			}
		} else {
			try {
				redirectToIdentityProvider(getResponse());
			} catch (SamlException e) {
				log.error(e.getMessage());
			}
		}
	}

	private String constructServiceUrl(String redirSuccess, String redirFailure) {
		String result = getBaseUrl() + "auth/saml";
		if (redirSuccess != null) {
			result += "?redirectOnSuccess=";
			result += redirSuccess;
		}
		if (redirFailure != null) {
			result += (redirSuccess == null ? "?" : "&");
			result += "redirectOnFailure=";
			result += redirFailure;
		}
		return result;
	}

	private String getBaseUrl() {
		String baseUrl = getRM().getConfiguration().getString(Settings.BASE_URL);
		if (!baseUrl.endsWith("/")) {
			baseUrl += "/";
		}
		return baseUrl;
	}

	private void redirectToIdentityProvider(Response response) throws SamlException {
		Map<String, String> values = new HashMap<>();
		values.put("SAMLRequest", samlClient.getSamlRequest());
		if ("post".equalsIgnoreCase(getRM().getConfiguration().getString(Settings.AUTH_SAML_REDIRECT_METHOD, "get"))) {
			redirectWithPost(samlClient.getIdentityProviderUrl(), response, values);
		} else {
			redirectWithGet(samlClient.getIdentityProviderUrl(), response, values);
		}
	}

	private void redirectWithPost(String url, Response response, Map<String, String> values) {
		StringBuilder sb = new StringBuilder();
		sb.append("<html><head></head><body><form id='TheForm' action='");
		sb.append(StringEscapeUtils.escapeHtml(url));
		sb.append("' method='POST'>");

		for (String key : values.keySet()) {
			String encodedKey = StringEscapeUtils.escapeHtml(key);
			String encodedValue = StringEscapeUtils.escapeHtml((String) values.get(key));
			sb.append("<input type='hidden' id='").append(encodedKey).
					append("' name='").append(encodedKey).
					append("' value='").append(encodedValue).
					append("'/>");
		}

		sb.append("</form><script type='text/javascript'>document.getElementById('TheForm').submit();</script></body></html>");
		Representation rep = new StringRepresentation(sb.toString(), MediaType.TEXT_HTML);
		List<CacheDirective> cacheDirs = new ArrayList<>();
		cacheDirs.add(CacheDirective.noCache());
		cacheDirs.add(CacheDirective.noStore());
		response.setCacheDirectives(cacheDirs);
		response.setEntity(rep);
	}

	private void redirectWithGet(String url, Response response, Map<String, String> values) {
		String targetUrl = url;
		if (values.containsKey("SAMLRequest")) {
			try {
				if (targetUrl.contains("?")) {
					targetUrl += "&";
				} else {
					targetUrl += "?";
				}
				targetUrl += "SAMLRequest=" + URLEncoder.encode(values.get("SAMLRequest"), "UTF-8");
			} catch (UnsupportedEncodingException e) {
				log.error(e.getMessage());
			}
		}
		List<CacheDirective> cacheDirs = new ArrayList<>();
		cacheDirs.add(CacheDirective.noCache());
		cacheDirs.add(CacheDirective.noStore());
		response.setCacheDirectives(cacheDirs);
		response.redirectTemporary(targetUrl);
	}

}