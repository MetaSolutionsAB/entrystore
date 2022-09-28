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

import org.eclipse.rdf4j.model.Graph;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.GraphImpl;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.List;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.ResourceType;
import org.entrystore.User;
import org.entrystore.impl.ContextImpl;
import org.entrystore.impl.EntryNamesContext;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.StringResource;
import org.entrystore.repository.util.NS;
import org.entrystore.rest.auth.CookieVerifier;
import org.entrystore.rest.auth.LoginTokenCache;
import org.entrystore.rest.util.Email;
import org.entrystore.rest.util.JSONErrorMessages;
import org.entrystore.rest.util.RDFJSON;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;


/**
 * This Resource contains information of all contexts. 
 * 
 * @author Hannes Ebner
 * @see BaseResource
 */
public class ContextResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(ContextResource.class); 
	
	private String requestText = null;

	@Override
	public void doInit() {
		try {
			requestText = getRequest().getEntity().getText();
		} catch (IOException e) {
			requestText = null;
		}
	}

	/**
	 * GET
	 * 
	 * List entries in a portfolio.
	 *
	 * This URL can be requested from a Web browser etc. This method will 
	 * execute a requests and deliver a response.
	 * <ul>
	 * <li>GET {base-uri}/{context-id}</li>
	 * </ul>
	 * 
	 * return {@link Representation}
	 */
	@Get
	public Representation represent() throws ResourceException {	
		if (context == null) {
			log.debug("The given context id does not exist.");
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
			return new JsonRepresentation(JSONErrorMessages.errorWrongContextIDmsg);
		}

        if (!getPM().isUserAdminOrAdminGroup(null)) {
            return unauthorizedGET();
        }

		JSONArray array = new JSONArray();

		if (parameters.containsKey("deleted")) {
			Set<URI> deletedURIs = context.getDeletedEntries().keySet();
			for (URI uri : deletedURIs) {
				String delURI = uri.toString();
				String delID = delURI.substring(delURI.lastIndexOf("/") + 1);
				array.put(delID);
			}
		} else if (context instanceof EntryNamesContext && parameters.containsKey("entryname")) {
            Entry matchedEntry  = ((EntryNamesContext) context).getEntryByName(parameters.get("entryname"));
            if (matchedEntry != null) {
                array.put(matchedEntry.getId());
            }
        } else {
			Set<URI> entriesURI = context.getEntries();
			for (URI u : entriesURI) {
				Entry entry = context.getByEntryURI(u);
				if (entry == null) {
					log.warn("No entry found for this referenced URI: " + u);
					continue;
				}
				String entryId = entry.getId();
				array.put(entryId);
			}
		}

		return new JsonRepresentation(array.toString());
	}

	/**
	 * POST
	 * 
	 * Creates new entries. 
	 * 
	 * These URL:s can be requested from a Web browser etc. This method will 
	 * execute these requests and deliver a response.
	 * <ul>
	 * <li>POST {base-uri}/{portfolio-id}?entryType=local&resourcetype={resourcetype}[&listURI={uri}]</li>
	 * <li>POST {base-uri}/{portfolio-id}?entryType=link&resource={resource-uri}[&list={listURI}]</li>
	 * <li>POST {base-uri}/{portfolio-id}?entryType=reference&resource={resource-uri}&metadata={metadata-uri}[&listURI={uri}]</li>
	 * <li>POST {base-uri}/{portfolio-id}?entryType=linkreference&resource={resource-uri}&metadata={metadata-uri}[&listURI={uri}]</li>
	 * </ul>
	 * Explanation:
	 * <ul>
	 * <li><i>base-uri</i> is a base URI that are specific for each portfolio installation.</li>
	 * <li><i>portfolio-id</i> is an integer that uniquely identifies a portfolio within a portfolio installation.</li>
	 * </ul>
	 */
	@Post
	public void acceptRepresentation(Representation r) throws ResourceException {
		try {
			if (context == null) {
				log.debug("The given context ID does not exist");
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				getResponse().setEntity(JSONErrorMessages.errorWrongContextIDmsg, MediaType.APPLICATION_JSON);
				return;
			}

			String entryId = parameters.get("id");
			if (entryId != null) {
				if (!isEntryIdValid(entryId)) {
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
					return;
				}
				Entry preExistingEntry = context.get(entryId);
				if (preExistingEntry != null) {
					log.debug("Entry with that ID already exists");
					getResponse().setStatus(Status.CLIENT_ERROR_CONFLICT);
					getResponse().setLocationRef(context.get(parameters.get("id")).getEntryURI().toString());
					getResponse().setEntity(JSONErrorMessages.errorEntryWithGivenIDExists, MediaType.APPLICATION_JSON);
					return;
				}
			}
			
			Entry entry = null; // A variable to store the new entry in.

			try {
				// Local
				if (!parameters.containsKey("entrytype") || parameters.get("entrytype").equalsIgnoreCase("local")) {
					entry = createLocalEntry(entry);
				} else {
					String lT = parameters.get("entrytype");
					// Link
					if (lT.equalsIgnoreCase("link") && parameters.containsKey("resource")) {
						entry = createLinkEntry(entry);
					}
					// Reference
					else if (lT.equalsIgnoreCase("reference") && parameters.containsKey("resource")
							&& parameters.containsKey("cached-external-metadata")) {
						entry = createReferenceEntry(entry);
					}
					// LinkReference
					else if (lT.equalsIgnoreCase("linkreference") && parameters.containsKey("resource")
							&& parameters.containsKey("cached-external-metadata")) {
						entry = createLinkReferenceEntry(entry);
					}
				}
			} catch (IllegalArgumentException iae) {
				log.debug(iae.getMessage());
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				getResponse().setEntity(new JsonRepresentation(new JSONObject().put("error", iae.getMessage())));
				return;
			}
			
			if (entry != null) {
				ResourceType rt = getResourceType(parameters.get("informationresource"));
				entry.setResourceType(rt);
				
				String template = parameters.get("template");
				if (template != null) {
					URI templateEntryURI = null;
					try {
						templateEntryURI = new URI(template);
					} catch (URISyntaxException e) {
						log.warn("Ignoring template, got invalid template URI: " + e.getMessage());
					}
					Entry templateEntry = null;
					if (templateEntryURI != null) {
						templateEntry = context.getByEntryURI(templateEntryURI);
					}
					if (templateEntry != null && templateEntry.getLocalMetadata() != null) {
						Graph templateMD = templateEntry.getLocalMetadata().getGraph();
						Graph inheritedMD = new GraphImpl();
						if (templateMD != null) {
							ValueFactory vf = inheritedMD.getValueFactory();
							IRI oldResURI = vf.createIRI(templateEntry.getResourceURI().toString());
							IRI newResURI = vf.createIRI(entry.getResourceURI().toString());
							
							java.util.List<IRI> predicateBlackList = new ArrayList<>();
							predicateBlackList.add(vf.createIRI(NS.dc, "title"));
							predicateBlackList.add(vf.createIRI(NS.dcterms, "title"));
							predicateBlackList.add(vf.createIRI(NS.dc, "description"));
							predicateBlackList.add(vf.createIRI(NS.dcterms, "description"));
							java.util.List<Value> subjectBlackList = new ArrayList<Value>();
							
							for (Statement statement : templateMD) {
								if (predicateBlackList.contains(statement.getPredicate())) {
									subjectBlackList.add(statement.getObject());
									continue;
								}
								if (subjectBlackList.contains(statement.getSubject())) {
									continue;
								}
								if (statement.getSubject().equals(oldResURI)) {
									inheritedMD.add(newResURI, statement.getPredicate(), statement.getObject(), statement.getContext());
								} else {
									inheritedMD.add(statement);
								}
							}
						}
						if (inheritedMD != null && !inheritedMD.isEmpty() && entry.getLocalMetadata() != null) {
							Graph mergedGraph = new GraphImpl();
							mergedGraph.addAll(entry.getLocalMetadata().getGraph());
							mergedGraph.addAll(inheritedMD);
							entry.getLocalMetadata().setGraph(mergedGraph);
						}
					}
				}
			}

			if (entry == null) {
				log.debug("Cannot create an entry with provided JSON");
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST); 
				getResponse().setEntity(JSONErrorMessages.errorCantCreateEntry, MediaType.APPLICATION_JSON);
			} else {
				// Success, return 201 and the new entry ID/URI in response and header
				getResponse().setStatus(Status.SUCCESS_CREATED);
				getResponse().setLocationRef(entry.getEntryURI().toString());
				getResponse().setEntity(new JsonRepresentation("{\"entryId\":\"" + entry.getId() + "\"}"));
				getResponse().getEntity().setModificationDate(entry.getModifiedDate());
			}
		} catch (AuthorizationException e) {
			unauthorizedPOST();
		}
	}

	/**
	 * Creates a LinkReference entry. 
	 * @param entry a reference to a entry object.
	 * @return the new created entry object.
	 */
	private Entry createLinkReferenceEntry(Entry entry) {
		if (isGraphTypeForbidden()) {
			return null;
		}

		try {
			if (parameters.get("resource") != null
					&& "linkreference".equalsIgnoreCase(parameters.get("entrytype"))) {
				URI resourceURI = null;
				URI metadataURI = null;
				try {
					resourceURI = URI.create(URLDecoder.decode(parameters.get("resource"), "UTF-8"));
					metadataURI = URI.create(URLDecoder.decode(parameters.get("cached-external-metadata"), "UTF-8"));
				} catch (UnsupportedEncodingException e) {
					log.warn(e.getMessage());
					return null;
				}
				
				if (parameters.containsKey("list")) {
					entry = context.createLinkReference(parameters.get("id"), resourceURI, metadataURI, new URI(((String) parameters.get("list"))));
				} else { 
					entry = context.createLinkReference(parameters.get("id"), resourceURI, metadataURI, null);
				}

				if (entry != null) {
					setLocalMetadataGraph(entry);
					setCachedMetadataGraph(entry);
					setEntryGraph(entry);
					if (parameters.containsKey("graphtype")) {
						GraphType gt = getGraphType(parameters.get("graphtype"));
						entry.setGraphType(gt);
					}
					if (parameters.containsKey("list")) {
						try {
							URI listURI = new URI((parameters.get("list")));
							((ContextImpl) context).copyACL(listURI, entry);
						} catch (URISyntaxException e) {
							log.warn(e.getMessage());
						}
					}
				}

				return entry;
			}
		} catch (Exception e) {
			log.warn(e.getMessage());
		}

		return null; 
	}

	/**
	 * Creates a Reference entry.
	 * @param entry a reference to a entry
	 * @return the new created entry
	 */
	private Entry createReferenceEntry(Entry entry) {
		if (isGraphTypeForbidden()) {
			return null;
		}

		try {
			if ((parameters.get("resource") != null) &&
					(parameters.get("cached-external-metadata") != null) &&
					("reference".equalsIgnoreCase(parameters.get("entrytype")))) {
				URI resourceURI = null;
				URI metadataURI = null;
				try {
					resourceURI = URI.create(URLDecoder.decode(parameters.get("resource"), "UTF-8"));
					metadataURI = URI.create(URLDecoder.decode(parameters.get("cached-external-metadata"), "UTF-8"));
				} catch (UnsupportedEncodingException e) {
					log.warn(e.getMessage());
					return null;
				}

				if (parameters.containsKey("list")) {
					entry = context.createReference(parameters.get("id"), resourceURI, metadataURI, new URI(((String) parameters.get("list"))));
				} else {
					entry = context.createReference(parameters.get("id"), resourceURI, metadataURI, null);
				}
				ResourceType rt = getResourceType(parameters.get("informationresource"));
				entry.setResourceType(rt);

				if (entry != null) {
					setCachedMetadataGraph(entry);
					setEntryGraph(entry);
					if (parameters.containsKey("graphtype")) {
						GraphType bt = getGraphType(parameters.get("graphtype"));
						entry.setGraphType(bt);
					}
					if (parameters.containsKey("list")) {
						try {
							URI listURI = new URI((parameters.get("list")));
							((ContextImpl) context).copyACL(listURI, entry);
						} catch (URISyntaxException e) {
							log.warn(e.getMessage());
						}
					}
				}

				return entry;
			}
		} catch (URISyntaxException e) {
			log.warn(e.getMessage());
		}

		return null;
	}

	private ResourceType getResourceType(String rt) {
		if (rt == null || !rt.equals("false")) {
			return ResourceType.InformationResource;
		} else {
			return ResourceType.NamedResource;
		}
	}
	
	
	private GraphType getGraphType(String gt) {
		if (gt == null || "".equals(gt)) {
			return GraphType.None;
		}
		if (gt.equalsIgnoreCase("list")) {
			return GraphType.List;
		}
		if (gt.equalsIgnoreCase("resultlist")) {
			return GraphType.ResultList;
		}
		if (gt.equalsIgnoreCase("context")) {
			return GraphType.Context;
		}		
		if (gt.equalsIgnoreCase("user")) {
			return GraphType.User;
		}
		if (gt.equalsIgnoreCase("group")) {
			return GraphType.Group;
		}
		if (gt.equalsIgnoreCase("systemcontext")) {
			return GraphType.SystemContext;
		}
		if (gt.equalsIgnoreCase("string")) {
			return GraphType.String;
		}
		if (gt.equalsIgnoreCase("graph")) {
			return GraphType.Graph;
		}
		if (gt.equalsIgnoreCase("pipeline")) {
			return GraphType.Pipeline;
		}
		if (gt.equalsIgnoreCase("pipelineresult")) {
			return GraphType.PipelineResult;
		}
		return GraphType.None;
	}

	/**
	 * Creates a local entry
	 * @param entry a reference to a entry
	 * @return the new created entry
	 */
	private Entry createLocalEntry(Entry entry) {
		if (isGraphTypeForbidden()) {
			return null;
		}

		URI listURI = null;
		if (parameters.containsKey("list")) {
			try {
				listURI = new URI((parameters.get("list")));
			} catch (URISyntaxException e) {
				log.warn(e.getMessage());
			}
		}

		GraphType bt = getGraphType(parameters.get("graphtype"));
		entry = context.createResource(parameters.get("id"), bt, null, listURI);
		try {
			if (setResource(entry)) {
				setLocalMetadataGraph(entry);
				setEntryGraph(entry);
				if (listURI != null) {
					((ContextImpl) context).copyACL(listURI, entry);
				}
				return entry; 
			} else {
				context.remove(entry.getEntryURI()); 
				return null;
			}
		} catch (JSONException e) {
			return null;
		}
	}

	/**
	 * Sets resource to an entry.
	 * @param entry a reference to a entry
	 * @return false if there is a resource provided but it cannot be interpreted.
	 * @throws JSONException Exception if payload is malformed
	 */
	private boolean setResource(Entry entry) throws JSONException {
		JSONObject jsonObj = new JSONObject();
		if (requestText != null && !"".equals(requestText)) {
			jsonObj = new JSONObject(requestText.replaceAll("_newId", entry.getId()));
		}

		//If there is no resource there is nothing to do yet.
		if (!jsonObj.has("resource")) {
			return true;
		}

		switch (entry.getGraphType()) {
		case User:
			jsonObj = jsonObj.getJSONObject("resource");
			User user = (User) entry.getResource();
			
			if (jsonObj.has("name")) {
				if (!user.setName(jsonObj.getString("name"))) {
					return false;
				}
			} else {
				return false; 
			}
			if (jsonObj.has("homecontext")) {
				Entry homeContextEntry = getCM().get(jsonObj.getString("homecontext"));
				if (homeContextEntry != null) {
					user.setHomeContext((Context) homeContextEntry.getResource());
				}
			}
			if (jsonObj.has("password")) {
				if (user.setSecret(jsonObj.getString("password"))) {
					LoginTokenCache.getInstance().removeTokensButOne(CookieVerifier.getAuthToken(getRequest()));
					Email.sendPasswordChangeConfirmation(getRM().getConfiguration(), entry);
				} else {
					log.warn("Password could not be set");
				}
			}

			if(parameters.containsKey("groupURI")) {
				Entry groupEntry;
				try {
					groupEntry = getCM().getEntry(new URI(parameters.get("groupURI")));
					Group group = (Group) groupEntry.getResource();
					group.addMember(user); 
				} catch (URISyntaxException e) {
					log.warn(e.getMessage());
				}
			}
			break;
		case Group:
			jsonObj = jsonObj.getJSONObject("resource");
			Group group = (Group) entry.getResource();
			if (jsonObj.has("name")) {
				group.setName(jsonObj.getString("name"));
			}
			break;
		case List:
			JSONArray childrenArray = (JSONArray) jsonObj.get("resource");
			List list = (List)entry.getResource();
			if (childrenArray != null) {
				for (int i = 0; i < childrenArray.length(); i++) {
					Entry child = context.get(childrenArray.getString(i));
					if (child != null) {
						list.addChild(child.getEntryURI());
					}
				}
			}
			break;
		case Context:
			jsonObj = jsonObj.getJSONObject("resource");
			Context cont = (Context) entry.getResource();
			if (jsonObj.has("name")) {
				getCM().setName(cont.getURI(), jsonObj.getString("name"));
			}
			if (jsonObj.has("quota")) {
				try {
					cont.setQuota(jsonObj.getLong("quota"));
				} catch (JSONException jsone) {
					log.warn("Unable to parse new quota value: " + jsone.getMessage());
				}
			}
			break;
		case String:
			StringResource stringRes = (StringResource) entry.getResource();
			stringRes.setString(jsonObj.getString("resource")); 
			break;
        case Graph:
        case Pipeline:
			RDFResource RDFRes = (RDFResource) entry.getResource();
			Graph g = RDFJSON.rdfJsonToGraph((JSONObject) jsonObj.get("resource"));
			RDFRes.setGraph(g);
			break;
		case PipelineResult:
		case None:
			break;
		}
		return true;
	}

	/**
	 * Creates a link entry.
	 * @param entry a reference to a entry
	 * @return the new created entry
	 */
	private Entry createLinkEntry(Entry entry) {
		if (isGraphTypeForbidden()) {
			return null;
		}

		//check the request
		URI resourceURI = null;
		resourceURI = URI.create(URLDecoder.decode(parameters.get("resource"), StandardCharsets.UTF_8));

		if (parameters.containsKey("list")) {
			entry = context.createLink(parameters.get("id"), resourceURI, URI.create(parameters.get("list")));
		} else {
			entry = context.createLink(parameters.get("id"), resourceURI, null);
		}

		if (entry != null) {
			setLocalMetadataGraph(entry);
			setEntryGraph(entry);
			if (parameters.containsKey("graphtype")) {
				GraphType gt = getGraphType(parameters.get("graphtype"));
				entry.setGraphType(gt);
			}
			if (parameters.containsKey("list")) {
				try {
					URI listURI = new URI((parameters.get("list")));
					((ContextImpl) context).copyACL(listURI, entry);
				} catch (URISyntaxException ignore) {
				}
			}
		}
		return entry;
	}

	/**
	 * Extracts metadata from the request and sets it as the entrys local metadata graph.
	 * @param entry The entry to set the metadata on.
	 */
	private void setLocalMetadataGraph(Entry entry) {
		if (requestText == null) {
			return; 
		}

		if (EntryType.Reference.equals(entry.getEntryType())) {
			return;
		}

		try {
			JSONObject mdObj = new JSONObject(requestText.replaceAll("_newId", entry.getId()));
			if (mdObj.has("metadata")) {
				JSONObject obj =(JSONObject) mdObj.get("metadata");
				Graph graph = null;
				if ((graph = RDFJSON.rdfJsonToGraph(obj)) != null) {
					entry.getLocalMetadata().setGraph(graph);
				}
			}
		} catch (JSONException e) {
			log.warn(e.getMessage());
		}
	}

	/**
	 * First caching of metadata.
	 * @param entry The entry to set the metadata on.
	 */
	private void setCachedMetadataGraph(Entry entry) {
		if (requestText == null) {
			return;
		}
		
		if (EntryType.Reference.equals(entry.getEntryType()) ||
				EntryType.LinkReference.equals(entry.getEntryType())) {
			try {
				JSONObject mdObj = new JSONObject(requestText.replaceAll("_newId", entry.getId()));
				if (mdObj.has("cached-external-metadata")) {
					JSONObject obj = (JSONObject) mdObj.get("cached-external-metadata");
					Graph graph = null;
					if ((graph = RDFJSON.rdfJsonToGraph(obj)) != null) {
						entry.getCachedExternalMetadata().setGraph(graph);
					}
				}
			} catch (JSONException e) {
				log.warn(e.getMessage());
			}
		}
	}

	/**
	 * Extracts entryinfo from the request and sets it as the entrys local metadata graph.
	 * Since it assumes this is the creation step, the Entries URIs was not available 
	 * on the client, hence the special "_newId" entryId has been used.
	 * Make sure this is replaced with the new entryId first.
	 * 
	 * @param entry The entry to set the metadata on.
	 */
	private void setEntryGraph(Entry entry) {
		if (requestText == null) {
			return;
		}

		try {
			JSONObject mdObj = new JSONObject(requestText.replaceAll("_newId", entry.getId()));
			if (mdObj.has("info")) {
				JSONObject obj = (JSONObject) mdObj.get("info");
				Graph graph = null;
				if ((graph = RDFJSON.rdfJsonToGraph(obj)) != null) {
					entry.setGraph(graph);
				}
			}
		} catch (JSONException e) {
			log.warn(e.getMessage());
		}
	}

	@Delete
	public void removeRepresentations() throws ResourceException {
		try {
			if (context == null) {
				log.debug("Unable to find context with ID " + contextId);
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND); 
				return;
			}

			getPM().checkAuthenticatedUserAuthorized(context.getEntry(), AccessProperty.Administer);

			getResponse().setStatus(Status.CLIENT_ERROR_METHOD_NOT_ALLOWED);

			// TODO implement the removal of all entries contained by
			//  this context, but not the context itself
		} catch(AuthorizationException e) {
			unauthorizedDELETE();
		}
	}

	/**
	 * Returns false if the Graph Type provided in the parameters
	 * cannot be used for manually created entries
	 *
	 * @return True if Graph Type is forbidden/blacklisted.
	 */
	private boolean isGraphTypeForbidden() {
		// Pipeline results may only be created by Pipelines
		if (GraphType.PipelineResult.equals(getGraphType(parameters.get("graphtype")))) {
			log.debug("Pipeline results may only be created by Pipelines");
			return true;
		}
		return false;
	}

	/**
	 * Checks whether the provided ID only contains allowed characters.
	 *
	 * @return True if supplied ID is valid.
	 */
	private boolean isEntryIdValid(String id) {
		return Pattern.compile("^[\\w\\-]+$").matcher(id).matches();
	}

}