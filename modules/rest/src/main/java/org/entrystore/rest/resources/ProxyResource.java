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

import com.google.common.base.Joiner;
import org.apache.commons.lang.StringEscapeUtils;
import org.entrystore.AuthorizationException;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.repository.config.Settings;
import org.restlet.Client;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * This class provides support for proxying requests to web services on other
 * servers.
 *
 * @author Hannes Ebner
 */
public class ProxyResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(ProxyResource.class);

	private Client client;

	private Response clientResponse;

	private static List<String> whitelistAnon;

	private static List<String> whitelistLocal;

	private final static List<Pattern> blacklistRegEx;

	static {
		blacklistRegEx = Arrays.asList(
				Pattern.compile("localhost"),		// localhost
				Pattern.compile("(.+)\\.local"),	// any local domains
				Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"),	// IPv4
				Pattern.compile("^\\d$"),			// IPv4
				Pattern.compile(":")				// IPv6
		);
		log.info("Proxy blacklist consists of following regular expressions: " + Joiner.on(", ").join(blacklistRegEx));
	}

	@Override
	public void doInit() {
		if (whitelistAnon == null) {
			whitelistAnon = new ArrayList<>();
			List<String> tmpWhitelistAnon = getRM().getConfiguration().getStringList(Settings.PROXY_WHITELIST_ANONYMOUS);
			// we normalize the list to lower case and to not contain null
			for (String domain : tmpWhitelistAnon) {
				if (domain != null) {
					whitelistAnon.add(domain.toLowerCase());
				}
			}
			if (!whitelistAnon.isEmpty()) {
				log.info("Proxy whitelist for guest users initialized with following domains: " +
						Joiner.on(", ").join(whitelistAnon)+
						"; Requests to other domains require authentication");
			} else {
				log.info("No domains provided for proxy whitelist; only authenticated users are allowed to perform proxy requests");
			}
		}
		if (whitelistLocal == null) {
			whitelistLocal = new ArrayList<>();
			List<String> tmpWhitelistLocal = getRM().getConfiguration().getStringList(Settings.PROXY_WHITELIST_LOCAL);
			// we normalize the list to lower case and to not contain null
			for (String domain : tmpWhitelistLocal) {
				if (domain != null) {
					whitelistLocal.add(domain.toLowerCase());
				}
			}
			if (!whitelistLocal.isEmpty()) {
				log.info("Proxy whitelist for authenticated requests against local domains initialized with following domains (to bypass built-in proxy blacklist): " +
					Joiner.on(", ").join(whitelistLocal)+
					"; Requests to other local domains will be blocked");
			} else {
				log.info("No domains provided for local proxy whitelist; no requests against local hosts will be allowed");
			}
		}
	}

	@Get
	public Representation represent() {
		String extResourceURL = parameters.get("url");

		if (extResourceURL == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return null;
		}

		if (contextId != null && context == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			return null;
		}

		// for /{context-id}/proxy only principals with read access may access the context's proxy resource
		if (context != null && !canReadContextResource(context.getEntry())) {
			getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
			return null;
		} else {
			// For /proxy in general: any user, including _guest may access hosts that are whitelisted,
			// otherwise access is restricted to logged-in users.
			// If the host is blacklisted, nobody is allowed to fetch the URL via the proxy
			String host = null;
			try {
				host = new URI(extResourceURL).getHost().toLowerCase();
			} catch (URISyntaxException | NullPointerException e) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return null;
			}
			if (!whitelistAnon.contains(host) && getPM().getGuestUser().getURI().equals(getPM().getAuthenticatedUserURI())) {
				getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
				return null;
			}
		}

		log.info("Received proxy request for " + extResourceURL);

		clientResponse = getResourceFromURL(extResourceURL, 0);
		Representation representation = null;
		if (clientResponse != null && clientResponse.getStatus().isSuccess()) {
			representation = clientResponse.getEntity();
			getResponse().getHeaders().set("Content-Security-Policy", "script-src 'none'; form-action 'none';"); // XSS and SSRF protection
			getResponse().setOnSent((request, response) -> {
				try {
					clientResponse.release();
					client.stop();
					client = null;
				} catch (Exception e) {
					log.error(e.getMessage());
				}
			});
			if (Status.isConnectorError(clientResponse.getStatus().getCode())) {
				log.debug("Proxy request to " + extResourceURL + " timed out");
				getResponse().setStatus(Status.SERVER_ERROR_GATEWAY_TIMEOUT);
				return null;
			} else {
				getResponse().setStatus(clientResponse.getStatus());
			}

			if (representation != null) {
				return representation;
			}
		}

		if (clientResponse == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		} else {
			getResponse().setStatus(clientResponse.getStatus());
		}

		return null;
	}

	private boolean canReadContextResource(Entry contextEntry) {
		try {
			getPM().checkAuthenticatedUserAuthorized(contextEntry, PrincipalManager.AccessProperty.ReadResource);
		} catch (AuthorizationException ae) {
			return false;
		}
		return true;
	}
	
	private Response getResourceFromURL(String url, int loopCount) {
		String host;
		try {
			host = new URI(url).getHost();
		} catch (URISyntaxException e) {
			log.debug(e.getMessage());
			Response errorResponse = new Response(new Request());
			errorResponse.setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return errorResponse;
		}

		if (!isWhitelisted(host) && isBlacklisted(host)) {
			Response errorResponse = new Response(new Request());
			errorResponse.setStatus(Status.CLIENT_ERROR_FORBIDDEN);
			return errorResponse;
		}

		if (loopCount > 15) {
			log.warn("More than 15 redirect loops detected, aborting");
			return null;
		}

		if (client == null) {
			client = new Client(Arrays.asList(Protocol.HTTP, Protocol.HTTPS));
			client.setContext(new Context());
			client.getContext().getParameters().set("connectTimeout", "30000");
			client.getContext().getParameters().set("socketConnectTimeoutMs", "30000");
			client.getContext().getParameters().set("socketTimeout", "60000");
			client.getContext().getParameters().set("readTimeout", "60000");
	        log.info("Initialized HTTP client for proxy requests");
		}

		Request request = new Request(Method.GET, url);
		request.getClientInfo().setAcceptedMediaTypes(getRequest().getClientInfo().getAcceptedMediaTypes());
		Response response = client.handle(request);
		
		if (response.getStatus().isRedirection()) {
			Reference ref = response.getLocationRef();
			response.getEntity().release();
			if (ref != null) {
				String refURL = ref.getIdentifier();
				log.debug("Request redirected to " + refURL);
				return getResourceFromURL(refURL, ++loopCount);
			}
		}

		if (response.getEntity() != null) {
			if (response.getEntity().getLocationRef() != null && response.getEntity().getLocationRef().getBaseRef() == null) {
				response.getEntity().getLocationRef().setBaseRef(url.substring(0, url.lastIndexOf("/") + 1));
			}
		}

		return response;
	}
	
	private String getTitle(String htmlString) {
		htmlString = htmlString.replaceAll("\\s+", " ");
		Pattern p = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE);
		Matcher m = p.matcher(htmlString);
		if (m.find()) {
			return StringEscapeUtils.unescapeHtml(m.group(1)).trim();
		}
		return null;
	}
	
	private String getMetaValue(String metaName, String htmlString) {
		htmlString = htmlString.replaceAll("\\s+", " ");
		Pattern p = Pattern.compile("<meta name=\"" + metaName + "\" content=\"(.*?)\" />", Pattern.CASE_INSENSITIVE);
		Matcher m = p.matcher(htmlString);
		if (m.find()) {
			return StringEscapeUtils.unescapeHtml(m.group(1)).trim();
		}
		return null;
	}
	
	private String getDescription(String htmlString) {
		String[] lines = htmlString.split("\\r?\\n");
		for (String s : lines) {
			String description = getMetaValue("description", s); 
			if (description != null) {
				return description;
			}
		}
		return null;
	}
	
	private Set<String> getKeywords(String htmlString) {
		Set<String> result = new HashSet<>();
		String[] lines = htmlString.split("\\r?\\n");
		for (String s : lines) {
			String keywords = getMetaValue("keywords", s);
			if (keywords != null) {
				for (String st : keywords.split(",")) {
					result.add(st.trim());
				}
			}
		}
		return result;
	}

	private boolean isWhitelisted(String host) {
		return whitelistLocal.contains(host.toLowerCase());
	}

	private boolean isBlacklisted(String host) {
		host = host.toLowerCase();

		for (Pattern p : blacklistRegEx) {
			if (p.matcher(host).find()) {
				return true;
			}
		}

		// all hosts that do not resolve into a "regular" Unicast address are automatically
		// blacklisted, among other reasons to avoid access to local networks
		try {
			InetAddress ia = InetAddress.getByName(host);
			if (ia.isAnyLocalAddress() ||
					ia.isSiteLocalAddress() ||
					ia.isLoopbackAddress() ||
					ia.isLinkLocalAddress() ||
					ia.isMulticastAddress()) {
				return true;
			}
		} catch (UnknownHostException e) {
			log.warn(e.getMessage());
			return true;
		}

		return false;
	}

}
