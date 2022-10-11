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

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResultHandler;
import org.eclipse.rdf4j.query.TupleQueryResultHandlerException;
import org.eclipse.rdf4j.query.impl.DatasetImpl;
import org.eclipse.rdf4j.query.resultio.binary.BinaryQueryResultWriter;
import org.eclipse.rdf4j.query.resultio.sparqljson.SPARQLResultsJSONWriter;
import org.eclipse.rdf4j.query.resultio.sparqlxml.SPARQLResultsXMLWriter;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.rest.util.HttpUtil;
import org.restlet.data.Form;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.InputRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;


/**
 * Provides a SPARQL interface to contexts.
 * 
 * @author Hannes Ebner
 */
public class SparqlResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(SparqlResource.class);
	
	List<MediaType> supportedMediaTypes = new ArrayList<MediaType>();

	@Override
	public void doInit() {
		supportedMediaTypes.add(MediaType.APPLICATION_XML);
		supportedMediaTypes.add(MediaType.APPLICATION_JSON);
		supportedMediaTypes.add(MediaType.ALL);
	}

	@Get
	public Representation represent() throws ResourceException {
		try {
			if (this.getRM().getPublicRepository() == null) {
				getResponse().setStatus(Status.SERVER_ERROR_NOT_IMPLEMENTED);
				return null;
			}
			
			if (format == null) {
				format = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
				if (format == null) {
					format = MediaType.ALL;
				}
			}
			
			String queryString = null;
			if (parameters.containsKey("query")) {
				try {
					queryString = URLDecoder.decode(parameters.get("query"), "UTF-8");
				} catch (UnsupportedEncodingException uee) {
					log.error(uee.getMessage());
				}
			} else {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return null;
			}
			
			Representation result = getSparqlResponse(format, queryString); 
			if (result != null) {
				return result;
			} else {
				getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				return null;
			} 
		} catch (AuthorizationException ae) {
			return unauthorizedGET();
		}
	}
	
	@Post
	public void acceptRepresentation(Representation r) {
		if (HttpUtil.isLargerThan(r, 32768)) {
			log.warn("The size of the representation is larger than 32KB or unknown, similar requests may be blocked in future versions");
		}

		try {
			if (this.getRM().getPublicRepository() == null) {
				getResponse().setStatus(Status.SERVER_ERROR_NOT_IMPLEMENTED);
				return;
			}

			Form form = new Form(getRequest().getEntity());
			String format = form.getFirstValue("output", true, "json");;
			if (format.equals("json")) {
				this.format = MediaType.APPLICATION_JSON;
			} else if (format.equals("xml")) {
				this.format = MediaType.APPLICATION_XML;
			}
			String query = form.getFirstValue("query", true);
			if (query == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Query must not be empty");
				return;
			}

			Representation result = getSparqlResponse(this.format, query);
			if (result == null) {
				getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				return;
			} else {
				getResponse().setEntity(result);
			}
		} catch (AuthorizationException ae) {
			unauthorizedPOST();
		}
	}
	
	private Representation getSparqlResponse(MediaType format, String queryString) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		boolean success = false;
		try {
			if (MediaType.APPLICATION_JSON.equals(format)) {
				success = runSparqlQuery(queryString, context, new SPARQLResultsJSONWriter(baos));
			} else if (MediaType.APPLICATION_XML.equals(format)) {
				success = runSparqlQuery(queryString, context, new SPARQLResultsXMLWriter(baos));
			} else {
				success = runSparqlQuery(queryString, context, new BinaryQueryResultWriter(baos));
			}
			if (success) {
				return new InputRepresentation(new ByteArrayInputStream(baos.toByteArray()), format);
			} else {
				return null;
			}
		} catch (Exception e) {
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, e.getMessage());
			log.error(e.getMessage());
			return null;
		}
	}
	
	private boolean runSparqlQuery(String queryString, Context context, TupleQueryResultHandler resultHandler) throws RepositoryException, MalformedQueryException, QueryEvaluationException, TupleQueryResultHandlerException {
		RepositoryConnection rc = null;
		try {
			rc = this.getRM().getPublicRepository().getConnection();
			if (rc == null) {
				return false;
			}

			TupleQuery query = rc.prepareTupleQuery(QueryLanguage.SPARQL, queryString);
			query.setMaxQueryTime(600); // setting a max query time of 10 minutes
			if (context != null) {
				IRI contextURI = rc.getValueFactory().createIRI(context.getURI().toString());
				DatasetImpl ds = new DatasetImpl();
				ds.addDefaultGraph(contextURI);
				ds.addNamedGraph(contextURI);
				// TODO for queries including named graphs to work properly, all
				// named graphs of the context should be added to the dataset.
				// This might not be feasible for large contexts and an own
				// repository per context might need to be considered...
				query.setDataset(ds);
			}

			query.evaluate(resultHandler);
		} finally {
			if (rc != null) {
				rc.close();
			}
		}
		return true;
	}

}