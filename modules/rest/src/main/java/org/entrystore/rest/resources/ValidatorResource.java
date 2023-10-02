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

import org.apache.commons.io.FileUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.ValidatingValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.RDFParseException;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.entrystore.rest.util.GraphUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * @author Hannes Ebner
 */
public class ValidatorResource extends BaseResource  {

	static Logger log = LoggerFactory.getLogger(ValidatorResource.class);
	
	List<MediaType> supportedMediaTypes = new ArrayList<>();

	ValueFactory vf = new ValidatingValueFactory();

	@Override
	public void doInit() {
		supportedMediaTypes.add(MediaType.APPLICATION_RDF_XML);
		supportedMediaTypes.add(MediaType.APPLICATION_JSON);
		supportedMediaTypes.add(MediaType.TEXT_RDF_N3);
		supportedMediaTypes.add(new MediaType(RDFFormat.TURTLE.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.TRIX.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.NTRIPLES.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.TRIG.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.JSONLD.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType("application/rdf+json"));
	}
	
	@Get
	public Representation represent() throws ResourceException {
		getResponse().setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);
		return new EmptyRepresentation();
	}

	@Post
	public void acceptRepresentation(Representation r) {
		if (getPM().getGuestUser().getURI().equals(getPM().getAuthenticatedUserURI())) {
			getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
			return;
		}

		MediaType mt = (format != null) ? format : getRequestEntity().getMediaType();
		String graphString = null;
		try {
			graphString = r.getText();
		} catch (IOException e) {
			log.warn(e.getMessage());
			setRepresentation(getResponse(), Status.CLIENT_ERROR_BAD_REQUEST, e.getMessage());
			return;
		}

		if (graphString != null) {
			// Test 1: can it be deserialized?
			Model deserializedGraph = null;
			try {
				deserializedGraph = GraphUtil.deserializeGraphUnsafe(graphString, mt);
			} catch (RDFHandlerException | RDFParseException | IOException e) {
				setRepresentation(getResponse(), Status.CLIENT_ERROR_BAD_REQUEST, e.getMessage());
				return;
			}

			if (deserializedGraph == null) {
				setRepresentation(getResponse(), Status.CLIENT_ERROR_BAD_REQUEST, "An error occured when trying to deserialize the input graph");
				return;
			}
			// Test 1 end

			// Test 2: does it work as IRI?
			List<String> errors = new ArrayList<>();
			for (Statement s : deserializedGraph) {
				testIRI(s.getSubject(), errors);
				testIRI(s.getPredicate(), errors);
				testIRI(s.getObject(), errors);
				testIRI(s.getContext(), errors);
			}
			if (!errors.isEmpty()) {
				setRepresentation(getResponse(), Status.CLIENT_ERROR_BAD_REQUEST, errors);
				return;
			}
			// Test 2 end

			// Test 3: can it be added to a Native Store?
			Path tmpPath = null;
			try {
				tmpPath = Files.createTempDirectory( "entrystore");
			} catch (IOException e) {
				log.error(e.getMessage());
				setRepresentation(getResponse(), Status.CLIENT_ERROR_BAD_REQUEST, e.getMessage());
				return;
			}

			Repository repo = null;
			RepositoryConnection rc = null;
			try {
				repo = new SailRepository(new NativeStore(tmpPath.toFile()));
				repo.init();
				rc = repo.getConnection();
				rc.add(deserializedGraph);
			} catch (RepositoryException e) {
				log.warn(e.getMessage());
				setRepresentation(getResponse(), Status.CLIENT_ERROR_BAD_REQUEST, e.getMessage());
				return;
			} finally {
				try {
					if (rc != null) {
						rc.close();
					}
				} catch (RepositoryException e) {
					log.error(e.getMessage());
				}

				try {
					if (repo != null) {
						repo.shutDown();
					}
				} catch (RepositoryException e) {
					log.error(e.getMessage());
				}

				if (tmpPath != null && tmpPath.toFile().isDirectory()) {
					try {
						FileUtils.deleteDirectory(tmpPath.toFile());
					} catch (IOException e) {
						log.error(e.getMessage());
					}
				}
			}
			// Test 3 end
		}

		setRepresentation(getResponse(), Status.SUCCESS_OK);
	}

	private void setRepresentation(Response response, Status status, List<String> errors) {
		JSONObject result = new JSONObject();
		result.put("errors", new JSONArray(errors));
		response.setEntity(new JsonRepresentation(result));
		response.setStatus(status);
	}

	private void setRepresentation(Response response, Status status, String error) {
		setRepresentation(response, status, Collections.singletonList(error));
	}

	private void setRepresentation(Response response, Status status) {
		setRepresentation(response, status, new ArrayList<>());
	}

	private void testIRI(Value value, List<String> errors) {
		if (value != null) {
			try {
				String valStr = value.stringValue();
				if (value instanceof IRI) {
					vf.createIRI(valStr);
				} else if (value instanceof Literal) {
					if (valStr.startsWith("http://") || valStr.startsWith("https://")) {
						vf.createIRI(valStr);
					}
				}
			} catch (IllegalArgumentException e) {
				errors.add(e.getMessage());
			}
		}
	}

}