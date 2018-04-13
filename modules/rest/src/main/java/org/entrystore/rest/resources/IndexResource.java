/*
 * Copyright (c) 2007-2018 MetaSolutions AB
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
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrException;
import org.entrystore.AuthorizationException;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.PrincipalManager;
import org.entrystore.impl.converters.ConverterUtil;
import org.entrystore.repository.util.QueryResult;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.util.GraphUtil;
import org.entrystore.rest.util.JSONErrorMessages;
import org.json.JSONObject;
import org.openrdf.model.Graph;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.rio.RDFFormat;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;


/**
 * Returns the Solr Document of an indexed Entry. To be used for inspection purposes.
 * 
 * @author Hannes Ebner
 */
public class IndexResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(IndexResource.class);
	
	List<MediaType> supportedMediaTypes = new ArrayList<MediaType>();
	
	@Override
	public void doInit() {
		supportedMediaTypes.add(MediaType.APPLICATION_JSON);
	}

	@Get
	public Representation represent() {
		try {
			if (entry == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return new JsonRepresentation(JSONErrorMessages.errorEntryNotFound);
			}

			getPM().checkAuthenticatedUserAuthorized(entry, PrincipalManager.AccessProperty.Administer);

			SolrDocument doc = ((SolrSearchIndex) getRM().getIndex()).fetchDocument(entry.getEntryURI().toString());
			if (doc == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return new EmptyRepresentation();
			}

			JSONObject result = new JSONObject();
			for (String field: doc.getFieldValuesMap().keySet()) {
				Collection<Object> values = doc.getFieldValues(field);
				if (values.size() > 1) {
					result.put(field, values);
				} else if (values.size() == 1) {
					result.put(field, values.iterator().next());
				}
			}
			return new JsonRepresentation(result);
		} catch (AuthorizationException e) {
			return unauthorizedGET();
		}
	}

}