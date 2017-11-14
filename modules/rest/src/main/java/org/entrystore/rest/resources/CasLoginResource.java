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
import org.entrystore.rest.auth.CookieVerifier;
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
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
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

	// TODO support auto-provisioning of users (an authenticated CAS user does not exist in EntryStore yet)

	private static boolean sslVerificationInitialized;

	@Override
	public void init(Context c, Request request, Response response) {
		super.init(c, request, response);

		Config config = getRM().getConfiguration();

		if (!sslVerificationInitialized) {
			if ("localhost".equals(URI.create(getBaseUrl()).getHost()) ||
					config.getBoolean(Settings.HTTPS_DISABLE_VERIFICATION, false)) {
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

	@Get
	public Representation represent() {
		String ticket = parameters.get(protocol.getArtifactParameterName());
		String redirSuccess = parameters.get("redirectOnSuccess");
		String redirFailure = parameters.get("redirectOnFailure");

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

				CookieVerifier verifier = new CookieVerifier(getRM());

				if (verifier.userExists(userName)) {
					verifier.createAuthToken(userName, null, getResponse());

					// TODO cache CAS ticket together with auth_token

					if (redirSuccess != null) {
						try {
							getResponse().redirectTemporary(URLDecoder.decode(redirSuccess, "UTF-8"));
						} catch (UnsupportedEncodingException e) {
							log.warn("Unable to decode URL parameter redirectOnSuccess: " + e.getMessage());
						}
					}

					getResponse().setStatus(Status.SUCCESS_OK);
				} else {
					if (redirFailure != null) {
						try {
							getResponse().redirectTemporary(URLDecoder.decode(redirFailure, "UTF-8"));
						} catch (UnsupportedEncodingException e) {
							log.warn("Unable to decode URL parameter redirectOnFailure: " + e.getMessage());
						}
					}

					getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
				}
			} catch (TicketValidationException e) {
				getResponse().setStatus(Status.CLIENT_ERROR_UNAUTHORIZED);
				log.error(e.getMessage());
			}
		} else {
			String serviceUrl = constructServiceUrl();
			if (redirSuccess != null) {
				serviceUrl += "?redirectOnSuccess=";
				serviceUrl += redirSuccess;
			}
			if (redirFailure != null) {
				if (redirSuccess == null) {
					serviceUrl += "?";
				}
				serviceUrl += "redirectOnFailure=";
				serviceUrl += redirFailure;
			}

			String redirUrl = CommonUtils.constructRedirectUrl(casLoginUrl, protocol.getServiceParameterName(), serviceUrl, false, false);
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

}