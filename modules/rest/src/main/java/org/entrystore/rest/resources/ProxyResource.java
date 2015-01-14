/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.lang.StringEscapeUtils;
import org.entrystore.impl.converters.ConverterUtil;
import org.entrystore.repository.util.NS;
import org.entrystore.rest.util.RDFJSON;
import org.openrdf.model.Graph;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
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
import org.restlet.Uniform;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
	
	Representation representation;

	@Override
	public void doInit() {
	}

	@Get
	public Representation represent() {
		String extResourceURL = null;
		if (parameters.containsKey("url")) {
			try {
				extResourceURL = URLDecoder.decode(parameters.get("url"), "UTF-8");
			} catch (UnsupportedEncodingException e) {
				log.error(e.getMessage());
			}
		}
		
		if (extResourceURL == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return null;
		}

		URI authUser = getPM().getAuthenticatedUserURI();
		if (authUser == null || getPM().getGuestUser().getURI().equals(authUser)) {
			getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
			return null;
		}
		
		log.info("Received proxy request for " + extResourceURL);
		
		clientResponse = getResourceFromURL(extResourceURL, 0);
		representation = null;
		if (clientResponse != null) {
			representation = clientResponse.getEntity();
			getResponse().setStatus(clientResponse.getStatus());
			getResponse().setOnSent(new Uniform() {
				public void handle(Request request, Response response) {
					try {
						clientResponse.release();
						client.stop();
						client = null;
					} catch (Exception e) {
						log.error(e.getMessage());
					}
				}
			});
		}

		if (representation != null && representation.isAvailable()) {
			if (parameters.containsKey("fromFormat")) {
				String fromFormat = parameters.get("fromFormat");
				log.info("Conversion of format \"" + fromFormat + "\" to RDF/JSON requested");
				URL xsltURL = getXSLT(fromFormat);
				if (xsltURL != null) {
					try {
						byte[] converted = transformWithXSLT(representation.getStream(), xsltURL.openStream());
						Graph graph = org.entrystore.rest.util.GraphUtil.deserializeGraph(new String(converted), new RDFXMLParser());
						Graph fixedGraph = fixCorrectEuropeanaRootURI(graph);
						return new JsonRepresentation(RDFJSON.graphToRdfJsonObject(fixedGraph));
					} catch (IOException e) {
						representation = null;
						log.error(e.getMessage());
					}
				} else {
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
							// FIXME what is actually done below? conversion of anything to RDF/JSON? if so, why?
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
							} else if (mediaType.getName().equals("application/lom+xml")) {
								URI resURI = null;
								try {
									resURI = URI.create(extResourceURL);
								} catch (IllegalArgumentException iae) {
									log.warn(iae.getMessage());
								}
								if (resURI != null) {
									deserializedGraph = ConverterUtil.convertLOMtoGraph(pageContent, resURI);
								}
							}
							if (deserializedGraph != null) {
								return new JsonRepresentation(RDFJSON.graphToRdfJsonObject(deserializedGraph));
							}
						}
					}
				}
			}
		}
		
		if (representation != null) {
			return representation;
		}
		
		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		return null;
	}
	
	private Response getResourceFromURL(String url, int loopCount) {
		if (loopCount > 15) {
			log.warn("More than 15 redirect loops detected, aborting");
			return null;
		}

		if (client == null) {
			client = new Client(Protocol.HTTP);
			client.setContext(new Context());
	        client.getContext().getParameters().add("connectTimeout", "10000");
	        client.getContext().getParameters().add("readTimeout", "10000");
			client.getContext().getParameters().set("socketTimeout", "10000");
			client.getContext().getParameters().set("socketConnectTimeoutMs", "10000");
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

		if (response.getEntity().getLocationRef() != null && response.getEntity().getLocationRef().getBaseRef() == null) {
			response.getEntity().getLocationRef().setBaseRef(url.substring(0, url.lastIndexOf("/")+1));
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
		String lines[] = htmlString.split("\\r?\\n");
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
		String lines[] = htmlString.split("\\r?\\n");
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
	
	private byte[] transformWithXSLT(InputStream input, InputStream xslt) {
		Source xmlSource = new StreamSource(input);
		Source xsltSource = new StreamSource(xslt);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
	    Result result = new StreamResult(baos);
	    TransformerFactory transFact = TransformerFactory.newInstance();
	    Transformer trans;
		try {
			trans = transFact.newTransformer(xsltSource);
			trans.transform(xmlSource, result);
		} catch (TransformerConfigurationException e) {
			log.error(e.getMessage());
			return null;
		} catch (TransformerException e) {
			log.error(e.getMessage());
			return null;
		}
		return baos.toByteArray();
	}
	
	private URL getXSLT(String conversion) {
		if ("europeana".equalsIgnoreCase(conversion)) {
			URL ese2edm = ConverterUtil.findResource("ese2edm.xsl");
			if (ese2edm == null) {
				log.error("Unable to find Europeana XSLT");
			}
			return ese2edm;
		} else if ("rdfa".equalsIgnoreCase(conversion)) {
			URL rdfa = ConverterUtil.findResource("rdfa2rdfxml.xsl");
			if (rdfa == null) {
				log.error("Unable to find RDFa XSLT");
			}
		}
		return null;
	}
	
	private Graph fixCorrectEuropeanaRootURI(Graph graph) {
		ValueFactory vf = graph.getValueFactory();
		Graph result = new GraphImpl();
		Resource oldRoot = null;
		Resource newRoot = null;
		Iterator<Statement> titleStmnt = graph.match(null, vf.createURI(NS.dc, "title"), null);
		if (titleStmnt.hasNext()) {
			Statement stmnt = titleStmnt.next();
			oldRoot = stmnt.getSubject();
		}
		Iterator<Statement> shownByStmnt = graph.match(null, vf.createURI("http://www.europeana.eu/schemas/edm/isShownBy"), null);
		if (shownByStmnt.hasNext()) {
			Statement stmnt = shownByStmnt.next();
			if (stmnt.getObject() instanceof Resource) {
				newRoot = (Resource) stmnt.getObject();
			}
		} else {
			Iterator<Statement> shownAtStmnt = graph.match(null, vf.createURI("http://www.europeana.eu/schemas/edm/isShownAt"), null);
			if (shownAtStmnt.hasNext()) {
				Statement stmnt = shownAtStmnt.next();
				if (stmnt.getObject() instanceof Resource) {
					newRoot = (Resource) stmnt.getObject();
				}
			}
		}
		if (oldRoot != null && newRoot != null) {
			Iterator<Statement> allStmnts = graph.iterator();
			while (allStmnts.hasNext()) {
				Statement stmnt = allStmnts.next();
				if (oldRoot.equals(stmnt.getSubject())) {
					result.add(newRoot, stmnt.getPredicate(), stmnt.getObject());
				} else {
					result.add(stmnt);
				}
			}
		} else {
			return graph;
		}
		return result;
	}

}