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

import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.auth.CookieVerifier;
import org.entrystore.rest.auth.LoginTokenCache;
import org.entrystore.rest.auth.UserInfo;
import org.jasig.cas.client.Protocol;
import org.jasig.cas.client.util.CommonUtils;
import org.jasig.cas.client.validation.Assertion;
import org.jasig.cas.client.validation.Cas10TicketValidator;
import org.jasig.cas.client.validation.Cas20ServiceTicketValidator;
import org.jasig.cas.client.validation.Cas30ServiceTicketValidator;
import org.jasig.cas.client.validation.Saml11TicketValidator;
import org.jasig.cas.client.validation.TicketValidationException;
import org.jasig.cas.client.validation.TicketValidator;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.CookieSetting;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Map;

/**
 * @author Hannes Ebner
 */
public class CasLoginResource extends BaseResource {

	private static Logger log = LoggerFactory.getLogger(CasLoginResource.class);

	private static Protocol protocol;

	private static String casLoginUrl;

	private static String casServerUrlPrefix;

	private static TicketValidator ticketValidator;

	// TODO support logout

	private static boolean sslVerificationInitialized;

	@Override
	public void init(Context c, Request request, Response response) {
		super.init(c, request, response);

		Config config = getRM().getConfiguration();

		if (!sslVerificationInitialized) {
			// TODO instead of checking for localhost we could also check the configuration for a corresponding
			// setting (should be global and not specific to SSO/CAS since the trustmanager is valid for the whole
			// java application scope and not only the CAS related stuff.
			// See Settings.HTTPS_DISABLE_VERIFICATION
			if ("localhost".equals(URI.create(getBaseUrl()).getHost())) {
				disableSslVerification();
			}
			sslVerificationInitialized = true;
		}

		if (casServerUrlPrefix == null) {
			casServerUrlPrefix = config.getString(Settings.AUTH_CAS_SERVER_URL);
			if (casServerUrlPrefix == null) {
				log.warn("No CAS server URL configured");
			} else {
				log.info("CAS server URL: " + casServerUrlPrefix);
			}
		}

		if (casLoginUrl == null) {
			casLoginUrl = config.getString(Settings.AUTH_CAS_SERVER_LOGIN_URL);
			if (casLoginUrl == null && casServerUrlPrefix != null) {
				casLoginUrl = casServerUrlPrefix + ((casServerUrlPrefix.endsWith("/") ? "" : "/")) + "login";
			}
			log.info("CAS login URL: " + casLoginUrl);
		}

		if (protocol == null) {
			String casVersion = config.getString(Settings.AUTH_CAS_VERSION, "cas2");
			if ("cas1".equalsIgnoreCase(casVersion)) {
				protocol = Protocol.CAS1;
				ticketValidator = new Cas10TicketValidator(casServerUrlPrefix);
			} else if ("cas3".equalsIgnoreCase(casVersion)) {
				protocol = Protocol.CAS3;
				ticketValidator = new Cas30ServiceTicketValidator(casServerUrlPrefix);
			} else if ("saml11".equalsIgnoreCase(casVersion)) {
				protocol = Protocol.SAML11;
				ticketValidator = new Saml11TicketValidator(casServerUrlPrefix);
			} else {
				protocol = Protocol.CAS2;
				ticketValidator = new Cas20ServiceTicketValidator(casServerUrlPrefix);
			}
			log.info("CAS protocol: " + protocol.name());
		}
	}

	private static void disableSslVerification() {
		try
		{
			// create a trust manager that does not validate certificate chains
			TrustManager[] trustAllCerts = new TrustManager[]{
				new X509TrustManager() {
					public java.security.cert.X509Certificate[] getAcceptedIssuers() {
						return null;
					}

					public void checkClientTrusted(X509Certificate[] certs, String authType) {}

					public void checkServerTrusted(X509Certificate[] certs, String authType) {}
				}
			};

			// install the all-trusting trust manager
			SSLContext sc = SSLContext.getInstance("SSL");
			sc.init(null, trustAllCerts, new java.security.SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

			// create all-trusting host name verifier
			HostnameVerifier allHostsValid = new HostnameVerifier() {
				public boolean verify(String hostname, SSLSession session) {
					return true;
				}
			};

			// install the all-trusting host verifier
			HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
		} catch (NoSuchAlgorithmException e) {
			log.error(e.getMessage());
		} catch (KeyManagementException e) {
			log.error(e.getMessage());
		}
	}

	@Get
	public Representation represent() {
		String ticket = parameters.get(protocol.getArtifactParameterName());
		if (ticket != null) {
			try {
				final Assertion assertion = ticketValidator.validate(ticket, constructServiceUrl());

				log.info("Successfully authenticated via CAS: " + assertion.getPrincipal());
				Map<String, Object> attr = assertion.getPrincipal().getAttributes();
				for (String k : attr.keySet()) {
					log.info(k + ": " + attr.get(k));
				}
				// cacheAuthentication(request, authentication);
				String userName = assertion.getPrincipal().getName();
				log.info("Received principal from CAS: " + userName);

				if (new CookieVerifier(getPM()).userExists(userName)) {
					// TODO externalize the whole section for generating token and setting cookie into an own method
					// in a helper class so that it can be used here and in CookieLoginResource

					// 24h default, lifetime in seconds
					// TODO make maxAge configurable, use same config for CookieLoginResource
					int maxAge = 24 * 3600;
					String token = Password.getRandomBase64(128);
					Date loginExpiration = new Date(new Date().getTime() + (maxAge * 1000));
					LoginTokenCache.getInstance().putToken(token, new UserInfo(userName, loginExpiration));

					// TODO cache CAS ticket together with auth_token

					CookieSetting tokenCookieSetting = new CookieSetting(0, "auth_token", token);
					tokenCookieSetting.setMaxAge(maxAge);
					tokenCookieSetting.setPath(getRM().getRepositoryURL().getPath());
					getResponse().getCookieSettings().add(tokenCookieSetting);
					getResponse().setStatus(Status.SUCCESS_OK);

					log.debug("User " + userName + " received authentication token that will expire on " + loginExpiration);
				} else {
					getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
				}
			} catch (TicketValidationException e) {
				getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
				log.error(e.getMessage());
			}
		} else {
			String redirUrl = CommonUtils.constructRedirectUrl(casLoginUrl, protocol.getServiceParameterName(), constructServiceUrl(), false, false);
			getResponse().redirectTemporary(redirUrl);
		}

		return new StringRepresentation("Authenticated user: " + getPM().getAuthenticatedUserURI(), MediaType.TEXT_HTML);
	}

	private String constructServiceUrl() {
		return getBaseUrl() + "auth/cas";
	}

	private String getBaseUrl() {
		String baseUrl = getRM().getConfiguration().getString(Settings.BASE_URL);
		if (!baseUrl.endsWith("/")) {
			baseUrl += "/";
		}
		return baseUrl;
	}

}