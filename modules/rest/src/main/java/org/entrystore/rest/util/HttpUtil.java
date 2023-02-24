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

package org.entrystore.rest.util;

import org.entrystore.rest.EntryStoreApplication;
import org.restlet.Client;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Preference;
import org.restlet.data.Protocol;
import org.restlet.data.Reference;
import org.restlet.representation.Representation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author Hannes Ebner
 */
public class HttpUtil {

	private static Logger log = LoggerFactory.getLogger(HttpUtil.class);

	private static Client client;

	private static String USERAGENT;

	static {
		Context clientContext = new Context();
		client = new Client(clientContext, Arrays.asList(Protocol.HTTP, Protocol.HTTPS));
		setTimeouts(10000);
		log.debug("Initialized HTTP client");
		USERAGENT = new StringBuffer().
				append("EntryStore/").append(EntryStoreApplication.getVersion().trim()).
				append(" (").
				append(System.getProperty("os.arch")).append("; ").
				append(System.getProperty("os.name")).append(" ").append(System.getProperty("os.version")).append("; ").
				append("Java; ").
				append(System.getProperty("java.vendor")).append(" ").append(System.getProperty("java.version")).
				append(")").
				toString();
		log.debug("User-Agent for HTTP requests set to \"" + USERAGENT + "\"");
	}

	public static Response getResourceFromURL(String url) {
		return getResourceFromURL(url, 0, 0, 0, null);
	}

	public static Response getResourceFromURL(String url, int loopCount, int retriesOnError, long timeBetweenRetries, List<MediaType> prefMediaType) {
		if (loopCount > 10) {
			log.warn("More than 10 redirect loops detected, aborting");
			return null;
		}

		Request request = new Request(Method.GET, url);
		if (prefMediaType == null) {
			prefMediaType = new ArrayList<MediaType>();
			prefMediaType.add(MediaType.ALL);
		}

		List<Preference<MediaType>> accMediaTypes = new ArrayList<>();
		for (MediaType mt : prefMediaType) {
			accMediaTypes.add(new Preference<MediaType>(mt));
		}
		request.getClientInfo().setAcceptedMediaTypes(accMediaTypes);

		request.getClientInfo().setAgent(USERAGENT);
		Response response = null;
		int tries = 0;
		do {
			response = client.handle(request);
			tries++;
			if (retriesOnError > 0 && response.getStatus().isError()) {
				try {
					log.info("Error when fetching <" + url + ">, retrying in " + timeBetweenRetries + " ms");
					Thread.sleep(timeBetweenRetries);
				} catch (InterruptedException e) {
					log.error(e.getMessage());
				}
			} else {
				break;
			}
		} while (tries < retriesOnError);

		// Alternative to calling the client directly:
		// HttpClientHelper helper = new HttpClientHelper(client);
		// Response response = new Response(request);
		// helper.handle(request, response);

		if (response.getStatus().isRedirection()) {
			Reference ref = response.getLocationRef();
			try {
				response.getEntity().exhaust();
			} catch (IOException e) {
				log.warn(e.getMessage());
			}
			response.getEntity().release();
			if (ref != null) {
				String refURL = ref.getIdentifier();
				log.debug("Request redirected from <" + url + "> to <" + refURL + ">");
				return getResourceFromURL(refURL, loopCount + 1, retriesOnError, timeBetweenRetries, prefMediaType);
			}
		}

		if (response.getEntity() != null && response.getEntity().getLocationRef() != null && response.getEntity().getLocationRef().getBaseRef() == null) {
			response.getEntity().getLocationRef().setBaseRef(url.substring(0, url.lastIndexOf("/") + 1));
		}
		return response;
	}

	public static void setTimeouts(long timeout) {
		String timeoutStr = Long.toString(timeout);
		client.getContext().getParameters().set("connectTimeout", timeoutStr);
		client.getContext().getParameters().set("socketTimeout", timeoutStr);
		client.getContext().getParameters().set("readTimeout", timeoutStr);
		client.getContext().getParameters().set("socketConnectTimeoutMs", timeoutStr);
	}

	public static String readFirstLine(URL url) {
		BufferedReader in = null;
		try {
			in = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8));
			return in.readLine();
		} catch (IOException ioe) {
			log.error(ioe.getMessage());
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (IOException e) {
					log.error(e.getMessage());
				}
			}
		}
		return null;
	}

	public static boolean isLargerThan(Representation r, long maxSize) {
		if (r == null || r.isEmpty()) {
			return false;
		}
		long repSize = r.getSize();
		if (repSize == Representation.UNKNOWN_SIZE) {
			log.warn("Size of representation is unknown");
			return true;
		} else return repSize > maxSize;
	}

}
