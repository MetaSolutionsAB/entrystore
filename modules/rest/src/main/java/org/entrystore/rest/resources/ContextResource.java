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
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Set;

import org.entrystore.repository.ResourceType;
import org.entrystore.repository.Entry;
import org.entrystore.repository.Group;
import org.entrystore.repository.List;
import org.entrystore.repository.EntryType;
import org.entrystore.repository.RepresentationType;
import org.entrystore.repository.User;
import org.entrystore.repository.PrincipalManager.AccessProperty;
import org.entrystore.repository.impl.ContextImpl;
import org.entrystore.repository.impl.RDFResource;
import org.entrystore.repository.impl.StringResource;
import org.entrystore.repository.impl.converters.NS;
import org.entrystore.repository.security.AuthorizationException;
import org.entrystore.rest.util.JSONErrorMessages;
import org.entrystore.rest.util.RDFJSON;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.repository.RepositoryException;
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
	 * <li>GET {base-uri}/{portfolio-id}</li>
	 * </ul>
	 * 
	 * return {@link Representation}
	 */
	@Get
	public Representation represent() throws ResourceException {	
		if (context == null) {
			log.info("The given context id does not exist."); 
			getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND); 
			return new JsonRepresentation(JSONErrorMessages.errorWrongContextIDmsg); 
		}
		
		if (parameters.containsKey("reindex")) {
			if (!getPM().getAdminUser().getURI().equals(getPM().getAuthenticatedUserURI())) {
				return unauthorizedGET();
			}
			context.reIndex();
		}

		JSONArray array = new JSONArray();

		if (parameters.containsKey("deleted")) {
			Set<URI> deletedURIs = context.getDeletedEntries().keySet();
			for (URI uri : deletedURIs) {
				String delURI = uri.toString();
				String delID = delURI.substring(delURI.lastIndexOf("/") + 1);
				array.put(delID);
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
	 * <li>POST {base-uri}/{portfolio-id}?locationType=local&resourceType={resourceType}[&listURI={uri}]</li>
	 * <li>POST {base-uri}/{portfolio-id}?locationType=link&resource={resource-uri}[&listURI={listURI}]</li>
	 * <li>POST {base-uri}/{portfolio-id}?locationType=reference&resource={resource-uri}&metadata={metadata-uri}[&listURI={uri}]</li>
	 * <li>POST {base-uri}/{portfolio-id}?locationType=linkreference&resource={resource-uri}&metadata={metadata-uri}[&listURI={uri}]</li>
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
				log.info("The given context id doesn't exist."); 
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST); 
				getResponse().setEntity(JSONErrorMessages.errorWrongContextIDmsg, MediaType.APPLICATION_JSON);
			}

			String entryId = parameters.get("id");
			if (entryId != null) {
				Entry preExistingEntry = context.get(entryId);
				if (preExistingEntry != null) {
					log.warn("Entry with that id already exists"); 
					getResponse().setStatus(Status.CLIENT_ERROR_CONFLICT); 
					getResponse().setLocationRef(context.get(parameters.get("id")).getEntryURI().toString());
					getResponse().setEntity(JSONErrorMessages.errorEntryWithGivenIDExists, MediaType.APPLICATION_JSON);
					return;
				}
			}
			
			Entry entry = null; // A variable to store the new entry in.

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
			
			if (entry != null) {
				RepresentationType rt = getRepresentationType(parameters.get("informationresource"));
				entry.setRepresentationType(rt);

				
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
							org.openrdf.model.URI oldResURI = vf.createURI(templateEntry.getResourceURI().toString());
							org.openrdf.model.URI newResURI = vf.createURI(entry.getResourceURI().toString());
							
							java.util.List<org.openrdf.model.URI> predicateBlackList = new ArrayList<org.openrdf.model.URI>();
							predicateBlackList.add(vf.createURI(NS.dc, "title"));
							predicateBlackList.add(vf.createURI(NS.dcterms, "title"));
							predicateBlackList.add(vf.createURI(NS.dc, "description"));
							predicateBlackList.add(vf.createURI(NS.dcterms, "description"));
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
			
			// Error: 
			if (entry == null) {
				log.warn("Can not create an Entry with that JSON"); 
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST); 
				getResponse().setEntity(JSONErrorMessages.errorCantCreateEntry, MediaType.APPLICATION_JSON);
			} else {
				// Success, return 201 and the new entry id in the response.
				getResponse().setStatus(Status.SUCCESS_CREATED);
				getResponse().setLocationRef(entry.getEntryURI().toString());
				getResponse().setEntity("{\"entryId\":"+entry.getId()+"}", MediaType.APPLICATION_JSON);
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
		try {
			if (parameters.get("resource") != null
					&& "linkreference".equalsIgnoreCase(parameters.get("entrytype"))) {
				URI resourceURI = null;
				URI metadataURI = null;
				try {
					resourceURI = URI.create(URLDecoder.decode(parameters.get("resource"), "UTF-8"));
					metadataURI = URI.create(URLDecoder.decode(parameters.get("cached-external-metadata"), "UTF-8"));
				} catch (UnsupportedEncodingException e) {
					log.error(e.getMessage());
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
					if (parameters.containsKey("resourcetype")) {
						ResourceType bt = getResourceType(parameters.get("resourcetype"));
						entry.setResourceType(bt);
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
					log.error(e.getMessage());
					return null;
				}

				if (parameters.containsKey("list")) {
					entry = context.createReference(parameters.get("id"), resourceURI, metadataURI, new URI(((String) parameters.get("list"))));
				} else {
					entry = context.createReference(parameters.get("id"), resourceURI, metadataURI, null);
				}
				RepresentationType rt = getRepresentationType(parameters.get("informationresource"));
				entry.setRepresentationType(rt);

				if (entry != null) {
					setCachedMetadataGraph(entry);
					setEntryGraph(entry);
					if (parameters.containsKey("resourcetype")) {
						ResourceType bt = getResourceType(parameters.get("resourcetype"));
						entry.setResourceType(bt);
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
			log.error(e.getMessage());
		} 

		return null; 
	}

	private RepresentationType getRepresentationType(String rt) {
		if (rt == null || !rt.equals("false")) {
			return RepresentationType.InformationResource;
		} else {
			return RepresentationType.NamedResource;
		}
	}
	
	
	private ResourceType getResourceType(String bt) {
		if (bt == null || "".equals(bt)) {
			return ResourceType.None;
		}
		if (bt.equalsIgnoreCase("list")) {
			return ResourceType.List;
		}
		if (bt.equalsIgnoreCase("resultlist")) {
			return ResourceType.ResultList;
		}
		if (bt.equalsIgnoreCase("context")) {
			return ResourceType.Context;
		}		
		if (bt.equalsIgnoreCase("user")) {
			return ResourceType.User;
		}
		if (bt.equalsIgnoreCase("group")) {
			return ResourceType.Group;
		}
		if (bt.equalsIgnoreCase("systemcontext")) {
			return ResourceType.SystemContext;
		}
		if (bt.equalsIgnoreCase("string")) {
			return ResourceType.String;
		}
		if (bt.equalsIgnoreCase("graph")) {
			return ResourceType.Graph;
		}
		if (bt.equalsIgnoreCase("pipeline")) {
			return ResourceType.Pipeline;
		}
		return ResourceType.None;
	}

	/**
	 * Creates a local entry
	 * @param entry a reference to a entry
	 * @return the new created entry
	 */
	private Entry createLocalEntry(Entry entry) {	
		URI listURI = null;
		if (parameters.containsKey("list")) {
			try {
				listURI = new URI((parameters.get("list")));
			} catch (URISyntaxException e) {
				log.warn(e.getMessage());
			}
		}

		ResourceType bt = getResourceType(parameters.get("resourcetype"));
		entry = context.createResource(parameters.get("id"), bt, null, listURI);
		try {
			if (setResource(entry, bt)) {
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
	 * @return the entry with the resource.
	 * @throws JSONException 
	 */
	private boolean setResource(Entry entry, ResourceType bt) throws JSONException {
		JSONObject jsonObj = new JSONObject();
		if (requestText != null) {
			jsonObj = new JSONObject(requestText);
		}

		if (jsonObj.has("resource")) {
			jsonObj = jsonObj.getJSONObject("resource");
		}
		switch (bt) {
		case User:
			User user = (User) entry.getResource();
			
			if (jsonObj.has("name")) {
				if(user.setName(jsonObj.getString("name")) == false) {
						return false; 
				}
			} else {
				return false; 
			}
			if (jsonObj.has("homecontext")) {
				Entry homeContextEntry = getCM().get(jsonObj.getString("homecontext"));
				if (homeContextEntry != null) {
					user.setHomeContext((org.entrystore.repository.Context) homeContextEntry.getResource());
				}
			}
			if (jsonObj.has("password")) {
				user.setSecret(jsonObj.getString("password"));					
			}

			if(parameters.containsKey("groupURI")) {
				Entry groupEntry;
				try {
					groupEntry = getCM().getEntry(new URI(parameters.get("groupURI")));
					Group group = (Group) groupEntry.getResource();
					group.addMember(user); 
				} catch (URISyntaxException e) {
					e.printStackTrace();
				} 
			}
			break;
		case Group:
			Group group = (Group) entry.getResource();
			if (jsonObj.has("name")) {
				group.setName(jsonObj.getString("name"));
			}
			break;
		case List:
			if (jsonObj.has("resource")) {
				List list = (List)entry.getResource();
				JSONObject res = (JSONObject) jsonObj.get("resource"); 
				if(res != null) {
					JSONArray childrenArray = (JSONArray) res.get("children");
					if (childrenArray != null) {
						for(int i = 0; i < childrenArray.length(); i++) {
							Entry child = context.get(childrenArray.getString(i));
							if(child != null) {
								list.addChild(child.getEntryURI());
							}
						}
					}
				}
			}
			break;
		case Context:
			org.entrystore.repository.Context cont = (org.entrystore.repository.Context) entry.getResource();
			if (jsonObj.has("alias")) {
				getCM().setContextAlias(cont.getURI(), jsonObj.getString("alias"));
			}
			if (jsonObj.has("quota")) {
				try {
					cont.setQuota(jsonObj.getLong("quota"));
				} catch (JSONException jsone) {
					log.error("Unable to parse new quota value: " + jsone.getMessage());
				}
			}
			break;
		case String:
			StringResource stringRes = (StringResource) entry.getResource();
			if (jsonObj.has("sc:body")){
				
				JSONObject resObj = jsonObj.getJSONObject("sc:body"); 
				if (resObj.has("@value") && resObj.has("@language")) {
					stringRes.setString(resObj.getString("@value"), resObj.getString("@language")); 
				} else if (resObj.has("resource") && jsonObj.has("@value")) {
					stringRes.setString(resObj.getString("@value"), null); 
				}
			}
			break;
		case Graph:
			RDFResource RDFRes = (RDFResource) entry.getResource();
			Graph g = RDFJSON.rdfJsonToGraph(jsonObj);
			RDFRes.setGraph(g);
			break;
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
		//check the request
		URI resourceURI = null;
		try {
			resourceURI = URI.create(URLDecoder.decode(parameters.get("resource"), "UTF-8"));
		} catch (UnsupportedEncodingException e) {
			log.error("The resource parameter must be encoded using UTF-8");
			return null;
		}

		if(parameters.containsKey("list")) {
			entry = context.createLink(parameters.get("id"), resourceURI, URI.create(parameters.get("list")));
		} else {
			entry = context.createLink(parameters.get("id"), resourceURI, null);
		}

		if (entry != null) {
			setLocalMetadataGraph(entry);
			setEntryGraph(entry);
			if (parameters.containsKey("resourcetype")) {
				ResourceType bt = getResourceType(parameters.get("resourcetype"));
				entry.setResourceType(bt);
			}
			if (parameters.containsKey("list")) {
				try {
					URI listURI = new URI((parameters.get("list")));
					((ContextImpl) context).copyACL(listURI, entry);
				} catch (URISyntaxException e) {
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
			JSONObject obj =(JSONObject) mdObj.get("metadata");
			Graph graph = null;
			if ((graph = RDFJSON.rdfJsonToGraph(obj)) != null) {
				entry.getLocalMetadata().setGraph(graph);
			}
		} catch (JSONException e) {
			log.error(e.getMessage());
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
				JSONObject obj = (JSONObject) mdObj.get("cached-external-metadata");
				Graph graph = null;
				if ((graph = RDFJSON.rdfJsonToGraph(obj)) != null) {
					entry.getCachedExternalMetadata().setGraph(graph);
				}
			} catch (JSONException e) {
				log.error(e.getMessage());
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
			JSONObject obj =(JSONObject) mdObj.get("info");
			Graph graph = null;
			if ((graph = RDFJSON.rdfJsonToGraph(obj)) != null) {
				entry.setGraph(graph);
			}
		} catch (JSONException e) {
			log.error(e.getMessage());
		}
	}

	@Delete
	public void removeRepresentations() throws ResourceException {
		try {
			if (context == null) {
				log.error("Unable to find context with that ID"); 
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND); 
				return;
			}
			
			if (!getPM().getAdminUser().getURI().equals(getPM().getAuthenticatedUserURI())) {
				throw new AuthorizationException(getPM().getUser(getPM().getAuthenticatedUserURI()), context.getEntry(), AccessProperty.Administer);
			}
			
			try {
				getCM().deleteContext(context.getURI());
				this.context = null;
			} catch (RepositoryException re) {
				getResponse().setStatus(Status.SERVER_ERROR_INTERNAL, re.getMessage());
				log.error(re.getMessage(), re);
			}
		} catch(AuthorizationException e) {
			unauthorizedDELETE();
		}
	}

}