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
import org.entrystore.repository.util.NS;
import org.entrystore.rest.util.GraphUtil;
import org.entrystore.rest.util.RDFJSON;
import org.openrdf.model.Graph;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.n3.N3ParserFactory;
import org.openrdf.rio.ntriples.NTriplesParser;
import org.openrdf.rio.rdfxml.RDFXMLParser;
import org.openrdf.rio.trig.TriGParser;
import org.openrdf.rio.trix.TriXParser;
import org.openrdf.rio.turtle.TurtleParser;
import org.restlet.Client;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
 * If a request with the URL-parameter fromFormat is received, the response is
 * converted into RDF/JSON, otherwise the content is only proxied without conversion.
 * 
 * @author Hannes Ebner
 */
public class ProxyResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(ProxyResource.class);

	private Client client;

	private Response clientResponse;

	private static List<String> whitelistAnon;

	private final static List<Pattern> blacklistRegEx;

	static {
		blacklistRegEx = Arrays.asList(
				Pattern.compile("localhost"),		// localhost
				Pattern.compile("(.+)\\.local"),	// any local domains
				Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"),	// IPv4
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
			if (whitelistAnon.size() > 0) {
				log.info("Proxy whitelist for guest users initialized with following domains: " +
						Joiner.on(", ").join(whitelistAnon)+
						"; Requests to other domains require authentication");
			} else {
				log.info("No domains provided for proxy whitelist; only authenticated users are allowed to perform proxy requests");
			}
		}
	}

	@Get
	public Representation represent() {
		String extResourceURL = null;
		if (parameters.containsKey("url")) {
			extResourceURL = URLDecoder.decode(parameters.get("url"), StandardCharsets.UTF_8);
		}

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
			// otherwise access is restricted to logged in users.
			// If the host is blacklisted, nobody is allowed to fetch the URL via the proxy
			String host = null;
			try {
				host = new URI(extResourceURL).getHost().toLowerCase();
			} catch (URISyntaxException | NullPointerException e) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return null;
			}
			if ((!whitelistAnon.contains(host) && getPM().getGuestUser().getURI().equals(getPM().getAuthenticatedUserURI()))
					|| isBlacklisted(host)) {
				getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
				return null;
			}
		}

		log.info("Received proxy request for " + extResourceURL);

		clientResponse = getResourceFromURL(extResourceURL, 0);
		Representation representation = null;
		if (clientResponse != null) {
			representation = clientResponse.getEntity();
			getResponse().getHeaders().set("Content-Security-Policy", "script-src 'none';"); // XSS protection
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
		}

		if (representation != null && representation.isAvailable()) {
			if (parameters.containsKey("fromFormat")) {
				String fromFormat = parameters.get("fromFormat");
				log.info("Conversion of format \"" + fromFormat + "\" to RDF/JSON requested");
				String pageContent = null;
				try {
					pageContent = representation.getText();
				} catch (IOException e) {
					log.error(e.getMessage());
				}
				if (pageContent != null) {
					if ("html".equalsIgnoreCase(fromFormat)) {
						Graph g = new GraphImpl();
						ValueFactory vf = g.getValueFactory();
						String title = getTitle(pageContent);
						if (title != null) {
							g.add(vf.createURI(extResourceURL), vf.createURI(NS.dc, "title"), vf.createLiteral(title));
						}
						String description = getDescription(pageContent);
						if (description != null) {
							g.add(vf.createURI(extResourceURL), vf.createURI(NS.dc, "description"), vf.createLiteral(description));
						}
						Set<String> keywords = getKeywords(pageContent);
						for (String k : keywords) {
							g.add(vf.createURI(extResourceURL), vf.createURI(NS.dc, "subject"), vf.createLiteral(k));
						}
						return new JsonRepresentation(RDFJSON.graphToRdfJsonObject(g));
					} else {
						MediaType mediaType = MediaType.valueOf(fromFormat);
						Graph deserializedGraph = null;
						if (MediaType.APPLICATION_RDF_XML.equals(mediaType)) {
							deserializedGraph = org.entrystore.rest.util.GraphUtil.deserializeGraph(pageContent, new RDFXMLParser());
						} else if (MediaType.TEXT_RDF_N3.equals(mediaType)) {
							deserializedGraph = org.entrystore.rest.util.GraphUtil.deserializeGraph(pageContent, new N3ParserFactory().getParser());
						} else if (mediaType.getName().equals(RDFFormat.TURTLE.getDefaultMIMEType())) {
							deserializedGraph = org.entrystore.rest.util.GraphUtil.deserializeGraph(pageContent, new TurtleParser());
						} else if (mediaType.getName().equals(RDFFormat.TRIX.getDefaultMIMEType())) {
							deserializedGraph = org.entrystore.rest.util.GraphUtil.deserializeGraph(pageContent, new TriXParser());
						} else if (mediaType.getName().equals(RDFFormat.NTRIPLES.getDefaultMIMEType())) {
							deserializedGraph = org.entrystore.rest.util.GraphUtil.deserializeGraph(pageContent, new NTriplesParser());
						} else if (mediaType.getName().equals(RDFFormat.TRIG.getDefaultMIMEType())) {
							deserializedGraph = org.entrystore.rest.util.GraphUtil.deserializeGraph(pageContent, new TriGParser());
						}
						if (deserializedGraph != null) {
							return new JsonRepresentation(RDFJSON.graphToRdfJsonObject(deserializedGraph));
						}
					}
				}
			} else if (parameters.containsKey("validate")) {
				String validateMime = parameters.get("validate");
				if (validateMime == null || validateMime.isEmpty()) {
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
					return new EmptyRepresentation();
				} else if (!GraphUtil.isSupported(new MediaType(validateMime))) {
					getResponse().setStatus(Status.CLIENT_ERROR_NOT_ACCEPTABLE);
					return new EmptyRepresentation();
				}
				MediaType origMT = representation.getMediaType();
				String payload = null;
				try {
					payload = representation.getText();
				} catch (IOException e) {
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
					return new StringRepresentation(e.getMessage());
				}
				String validationError = GraphUtil.validateRdf(payload, new MediaType(validateMime));
				if (validationError != null) {
					getResponse().setStatus(Status.CLIENT_ERROR_UNPROCESSABLE_ENTITY);
					return new StringRepresentation(validationError, origMT);
				} else {
					return new StringRepresentation(payload, origMT);
				}
			}
		}
		
		if (representation != null) {
			return representation;
		}
		
		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
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
	        log.info("Initialized HTTP client for proxy request");
		}

		Request request = new Request(Method.GET, url);
		request.getClientInfo().setAcceptedMediaTypes(getRequest().getClientInfo().getAcceptedMediaTypes());
		Response response = client.handle(request);
		
		if (response.getStatus().isRedirection()) {
			Reference ref = response.getLocationRef();
			response.getEntity().release();
			if (ref != null) {
				String refURL = ref.getIdentifier();
				log.info("Request redirected to " + refURL);
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
		Set<String> result = new HashSet<String>();
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

	private boolean isBlacklisted(String host) {
		for (Pattern p : blacklistRegEx) {
			if (p.matcher(host).find()) {
				return true;
			}
		}
		return false;
	}

}