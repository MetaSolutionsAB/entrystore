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

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrException;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.entrystore.AuthorizationException;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.repository.util.QueryResult;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.util.GraphUtil;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;


/**
 * Performs a lookup based on the resource URI and returns metadata about the resource.
 * 
 * @author Hannes Ebner
 */
public class LookupResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(LookupResource.class);
	
	List<MediaType> supportedMediaTypes = new ArrayList<MediaType>();
	
	@Override
	public void doInit() {
		supportedMediaTypes.add(MediaType.APPLICATION_RDF_XML);
		supportedMediaTypes.add(MediaType.APPLICATION_JSON);
		supportedMediaTypes.add(MediaType.TEXT_RDF_N3);
		supportedMediaTypes.add(new MediaType(RDFFormat.TURTLE.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.TRIX.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.NTRIPLES.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.TRIG.getDefaultMIMEType()));
		entry = null;
	}

	@Get
	public Representation represent() {
		URI resourceURI = null;
		
		if (parameters.containsKey("uri")) {
			try {
				resourceURI = new URI(URLDecoder.decode(parameters.get("uri"), "UTF-8"));
			} catch (Exception e) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return null;
			}
		}
		
		String scope = "all";
		if (parameters.containsKey("scope")) {
			scope = parameters.get("scope").toLowerCase();
			if (!"all".equals(scope) && !"local".equals(scope) && !"external".equals(scope)) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return null;
			}
		}
		
		if (resourceURI == null) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return null;
		}

		Set<Entry> entries = null;
		if (context != null) {
			// get entry based on uri
			entries = context.getByResourceURI(resourceURI);
		} else {
			// we perform a global lookup using Solr instead
			if (getRM().getIndex() == null) {
				getResponse().setStatus(Status.SERVER_ERROR_SERVICE_UNAVAILABLE, "Solr search deactivated");
				return null;
			}

			String solrEscapedURI = ClientUtils.escapeQueryChars(resourceURI.toString());
			SolrQuery q = new SolrQuery("resource:" + solrEscapedURI + " AND public:true");
			q.setStart(0);
			q.setRows(1);

			try {
				QueryResult qResult = ((SolrSearchIndex) getRM().getIndex()).sendQuery(q);
				entries = qResult.getEntries();
			} catch (SolrException se) {
				log.warn(se.getMessage());
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return null;
			}
		}

		if (entries == null || entries.isEmpty()) {
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			return null;
		}

		if (entries.size() > 1) {
			log.info("Multiple matching entries for resource URI " + resourceURI);
		}

		// we take the first matching entry
		entry = entries.iterator().next();
		

		MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
		if (preferredMediaType == null) {
			preferredMediaType = MediaType.APPLICATION_RDF_XML;
		}
		
		Representation r;
		
		// try to get metadata
		r = getMetadata(entry, scope, (format != null) ? format : preferredMediaType);

		if (r != null) {
			// we also set the modification date on the response
			Date lastMod = entry.getModifiedDate();
			if (lastMod != null) {
				r.setModificationDate(lastMod);
			}
		}
		
		return r;
	}
	
	private Representation getMetadata(Entry e, String scope, MediaType mediaType) throws AuthorizationException {
		EntryType locType = entry.getEntryType();
		Model graph = new LinkedHashModel();
		if (EntryType.Local.equals(locType) || EntryType.Link.equals(locType)) {
			if ("all".equals(scope) || "local".equals(scope)) {
				graph.addAll(entry.getLocalMetadata().getGraph());
			}
		} else if (EntryType.Reference.equals(locType)) {
			if ("all".equals(scope) || "external".equals(scope)) {
				graph.addAll(entry.getCachedExternalMetadata().getGraph());
			}
		} else if (EntryType.LinkReference.equals(locType)) {
			if ("all".equals(scope) || "local".equals(scope)) {
				graph.addAll(entry.getLocalMetadata().getGraph());
			}
			if ("all".equals(scope) || "external".equals(scope)) {
				graph.addAll(entry.getCachedExternalMetadata().getGraph());
			}
		}

		if (graph != null) {
			String serializedGraph = GraphUtil.serializeGraph(graph, mediaType);
			if (serializedGraph != null) {
				getResponse().setStatus(Status.SUCCESS_OK);
				return new StringRepresentation(serializedGraph, mediaType);
			}
		}

		getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		return new EmptyRepresentation();
	}

}