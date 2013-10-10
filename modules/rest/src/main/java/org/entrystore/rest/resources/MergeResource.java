/**
 * Copyright (c) 2007-2010
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

import java.io.IOException;

import org.entrystore.repository.AuthorizationException;
import org.entrystore.repository.PrincipalManager.AccessProperty;
import org.entrystore.repository.impl.converters.ConverterUtil;
import org.entrystore.repository.impl.converters.Graph2Entries;
import org.entrystore.rest.util.RDFJSON;
import org.openrdf.model.Graph;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.n3.N3ParserFactory;
import org.openrdf.rio.ntriples.NTriplesParser;
import org.openrdf.rio.rdfxml.RDFXMLParser;
import org.openrdf.rio.trig.TriGParser;
import org.openrdf.rio.trix.TriXParser;
import org.openrdf.rio.turtle.TurtleParser;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.resource.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class supports the import of RDF into several entries into a specified
 * contexts. Data will be imported into existing entries if possible, otherwise
 * new entries will be created.
 * 
 * @author Matthias Palmér
 */
public class MergeResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(MergeResource.class);
		
	@Post
	public void acceptRepresentation(Representation r) {
		try {
			if (!getPM().getAdminUser().getURI().equals(getPM().getAuthenticatedUserURI())) {
				throw new AuthorizationException(getPM().getUser(getPM().getAuthenticatedUserURI()), context.getEntry(), AccessProperty.Administer);
			}
			
			if (context == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return;
			}
			
			String graphString = null;
			try {
				graphString = getRequest().getEntity().getText();
			} catch (IOException e) {
				log.error(e.getMessage());
			}
			
			if (graphString != null) {
				MediaType mediaType = (format != null) ? format : getRequestEntity().getMediaType();

				Graph deserializedGraph = null;
				if (mediaType.equals(MediaType.APPLICATION_JSON)) {
					deserializedGraph = RDFJSON.rdfJsonToGraph(graphString);
				} else if (mediaType.equals(MediaType.APPLICATION_RDF_XML)) {
					deserializedGraph = ConverterUtil.deserializeGraph(graphString, new RDFXMLParser());
				} else if (mediaType.equals(MediaType.TEXT_RDF_N3)) {
					deserializedGraph = ConverterUtil.deserializeGraph(graphString, new N3ParserFactory().getParser());
				} else if (mediaType.getName().equals(RDFFormat.TURTLE.getDefaultMIMEType())) {
					deserializedGraph = ConverterUtil.deserializeGraph(graphString, new TurtleParser());
				} else if (mediaType.getName().equals(RDFFormat.TRIX.getDefaultMIMEType())) {
					deserializedGraph = ConverterUtil.deserializeGraph(graphString, new TriXParser());
				} else if (mediaType.getName().equals(RDFFormat.NTRIPLES.getDefaultMIMEType())) {
					deserializedGraph = ConverterUtil.deserializeGraph(graphString, new NTriplesParser());
				} else if (mediaType.getName().equals(RDFFormat.TRIG.getDefaultMIMEType())) {
					deserializedGraph = ConverterUtil.deserializeGraph(graphString, new TriGParser());
				} else {
					getResponse().setStatus(Status.CLIENT_ERROR_NOT_ACCEPTABLE);
					return;
				}
				
				if (deserializedGraph != null) {
					Graph2Entries g2e = new Graph2Entries(this.context);
					g2e.merge(deserializedGraph, this.parameters.get("resourceId"));
					getResponse().setStatus(Status.SUCCESS_OK);
					return;
				}
			}			
		} catch(AuthorizationException e) {
			unauthorizedPOST();
		}
	}
}