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


package org.entrystore.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.rdf4j.common.iteration.Iterations;
import org.eclipse.rdf4j.model.Graph;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.GraphImpl;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.URIImpl;
import org.eclipse.rdf4j.model.util.ModelUtil;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.Provenance;
import org.entrystore.Resource;
import org.entrystore.ResourceType;
import org.entrystore.User;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.util.URISplit;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.net.URI;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;


//TODO change expression to match paper.
public class EntryImpl implements Entry {
	protected volatile String id;
	protected volatile Resource resource;
	protected volatile MetadataImpl localMetadata;
	protected volatile Metadata cachedExternalMetadata;
	protected volatile IRI localMdURI;
	protected volatile IRI relationURI;

	protected volatile IRI externalMdURI;
	protected volatile IRI cachedExternalMdURI;
	protected ContextImpl context;
	protected final Repository repository;
	protected RepositoryManagerImpl repositoryManager;

	protected volatile IRI entryURI;
	protected volatile IRI resURI;
	protected volatile EntryType locType = EntryType.Local;
	protected volatile ResourceType repType = ResourceType.InformationResource;
	protected volatile GraphType graphType = GraphType.None;
	protected volatile XMLGregorianCalendar created;
	protected volatile XMLGregorianCalendar modified;
	protected volatile XMLGregorianCalendar cachedAt;
	protected volatile IRI creator;
	//protected Set<URI> referredIn = new HashSet<URI>();
	protected volatile Set<IRI> contributors = new HashSet<>();
	protected volatile URI status;

	Log log = LogFactory.getLog(EntryImpl.class);
	private volatile Set<URI> administerPrincipals;
	private volatile Set<URI> readMetadataPrincipals;
	private volatile Set<URI> writeMetadataPrincipals;
	private volatile Set<URI> writeResourcePrincipals;
	private volatile Set<URI> readResourcePrincipals;
	private volatile List<Statement> relations;
    protected boolean invRelations = false;
	private volatile String format;
	private volatile long fileSize = -1;
	private volatile String filename;
	private Boolean readOrWrite;
	private String originalList;
	private ProvenanceImpl provenance;
	private volatile boolean deleted = false;

	//A ugly hack to be able to initialize the ContextManager itself.
	EntryImpl(RepositoryManagerImpl repositoryManager, Repository repository) {
		this.repositoryManager = repositoryManager;
		this.repository = repository;
		if (repositoryManager.getProvenanceRepository() != null) {
			this.provenance = new ProvenanceImpl(this);
		}
	}

	public EntryImpl(String id, ContextImpl context, RepositoryManagerImpl repositoryManager, Repository repository) {
		this.id = id;
		String base = repositoryManager.getRepositoryURL().toString();
		ValueFactory vf = repository.getValueFactory();
		this.entryURI = vf.createIRI(URISplit.fabricateURI(base, context.id, RepositoryProperties.ENTRY_PATH, id).toString());
		this.relationURI = vf.createIRI(URISplit.fabricateURI(base, context.id, RepositoryProperties.RELATION, id).toString());
		this.localMdURI = vf.createIRI(URISplit.fabricateURI(base, context.id, RepositoryProperties.MD_PATH, this.id).toString());
		this.context = context;
		this.repositoryManager = repositoryManager;
		this.repository = repository;
		if (repositoryManager.getProvenanceRepository() != null) {
			this.provenance = new ProvenanceImpl(this);
		}
	}

	public String getId() {
		return id;
	}

	/**
	 * Loads an MetaMetadataImpl by listing all statements for the named graph given by uri.
	 */
	protected boolean load() {
		try {
			RepositoryConnection rc = repository.getConnection();
			try {
				if (loadFromStatements(rc.getStatements(null, null, null, false, this.entryURI).asList())) {
					initMetadataObjects();
					return true;
				}
				return false;
			} catch (Exception e) {
				log.error(e.getMessage());
				throw new org.entrystore.repository.RepositoryException("Error in repository connection.", e);
			} finally {
				rc.close();
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
		}
	}

	/**
	 * Loads an entry information from existing list of statements.
	 * @throws RepositoryException 
	 */
	protected boolean load(RepositoryConnection rc) throws RepositoryException {
		if (loadFromStatements(rc.getStatements(null, null, null, false, this.entryURI).asList())) {
			initMetadataObjects();
			return true;
		}
		return false;
	}

	protected void initMetadataObjects() {
		if (locType == EntryType.Local || locType == EntryType.Link) {
			this.localMetadata = new MetadataImpl(this, localMdURI, resURI, false);
		}

		if (locType == EntryType.LinkReference) {
			this.localMetadata = new MetadataImpl(this, localMdURI, resURI, false);
			if (externalMdURI.stringValue().startsWith(this.repositoryManager.getRepositoryURL().toString())) {
				this.cachedExternalMetadata = new LocalMetadataWrapper(this);
			} else {
				this.cachedExternalMetadata = new MetadataImpl(this, cachedExternalMdURI, resURI, true);
			}
		}

		if (locType == EntryType.Reference) {
			if (externalMdURI.stringValue().startsWith(this.repositoryManager.getRepositoryURL().toString())) {
				this.cachedExternalMetadata = new LocalMetadataWrapper(this);
			} else {
				this.cachedExternalMetadata = new MetadataImpl(this, cachedExternalMdURI, resURI, true);
			}
		}
	}

	/**
	 * Use when a new entry information object are to be created within an existing transaction.
	 * @throws DatatypeConfigurationException 
	 * @throws RepositoryException 
	 */
	protected void create(IRI resURI, IRI externalMetadataURI, GraphType bType, EntryType lType, ResourceType rType, RepositoryConnection rc) throws RepositoryException, DatatypeConfigurationException {
		String base = repositoryManager.getRepositoryURL().toString();
		ValueFactory vf = repository.getValueFactory();
		this.resURI = resURI;

		if (lType == EntryType.LinkReference) {
			this.cachedExternalMdURI = vf.createIRI(URISplit.fabricateURI(base, context.id, RepositoryProperties.EXTERNAL_MD_PATH, this.id).toString());
			this.externalMdURI = externalMetadataURI;
		}

		if (lType == EntryType.Reference) {
			this.cachedExternalMdURI = vf.createIRI(URISplit.fabricateURI(base, context.id, RepositoryProperties.EXTERNAL_MD_PATH, this.id).toString());
			this.externalMdURI = externalMetadataURI;
		}

		initialize(bType, lType, rType, rc);
		initMetadataObjects();
	}

	private void initialize(GraphType bt, EntryType locT, ResourceType repT, RepositoryConnection rc) throws RepositoryException, DatatypeConfigurationException {
		ValueFactory vf = rc.getRepository().getValueFactory();
		if (bt != null) {
			setGraphType(bt, rc);
		}
		if (repT != null && graphType == GraphType.None) {
			setResourceType(repT, rc);
		}
		setLocationType(locT, rc);
		rc.add(entryURI, RepositoryProperties.resource, this.resURI, entryURI);
		created = DatatypeFactory.newInstance().newXMLGregorianCalendar(new GregorianCalendar());
		rc.add(entryURI, RepositoryProperties.Created, vf.createLiteral(created), entryURI);

		registerEntryModified(rc, vf);

		/* 
		 * Adds a statement with the entry URI as subject, 
		 * relation as predicate, and the relation URI as 
		 * an object to the scam repository, the named context is the entry.
		 */ 
		rc.add(entryURI, RepositoryProperties.relation, this.relationURI, entryURI);

		if (locT != EntryType.Reference) {
			rc.add(entryURI, RepositoryProperties.metadata, this.localMdURI, entryURI);
		}

		if (locT == EntryType.Reference || locT == EntryType.LinkReference) {
			rc.add(entryURI, RepositoryProperties.externalMetadata, this.externalMdURI, entryURI);
			rc.add(entryURI, RepositoryProperties.cachedExternalMetadata, this.cachedExternalMdURI, entryURI);			
		}

		if (!this.id.startsWith("_")) { //System entries don't have creators, because they're created by the system.
			PrincipalManager pm = this.repositoryManager.getPrincipalManager();
			if (pm != null) {
				URI userURI = pm.getAuthenticatedUserURI();
				if (userURI != null) {
					this.creator = vf.createIRI(userURI.toString());
					rc.add(entryURI, RepositoryProperties.Creator, this.creator, entryURI);				
				}
			}
		}

		//referredIn = new HashSet<URI>();
		//TODO Check so that not a Context is a NamedResource and other restrictions.
	}

	protected void refreshFromRepository(RepositoryConnection rc) throws RepositoryException {
		loadFromStatements(rc.getStatements(null, null, null, false, entryURI).asList());
	}

	private boolean loadFromStatements(List<Statement> existingStatements) throws RepositoryException {
		if (existingStatements.isEmpty()) {
			return false;
		}

		Set<URI> administerPrincipals = null;
		Set<URI> readMetadataPrincipals = null;
		Set<URI> writeMetadataPrincipals = null;
		Set<URI> readResourcePrincipals = null;
		Set<URI> writeResourcePrincipals = null;
		Set<IRI> contributors = new HashSet<>();
		IRI entryURI = null;
		IRI resURI = null;
		IRI localMdURI = null;
		IRI cachedExternalMdURI = null;
		IRI relationURI = null;
		XMLGregorianCalendar created = null;
		IRI creator = null;
		XMLGregorianCalendar modified = null;
		IRI externalMdURI = null;
		XMLGregorianCalendar cachedAt = null;
		EntryType locType = EntryType.Local;
		ResourceType repType = ResourceType.InformationResource;
		GraphType graphType = GraphType.None;

		//Following are cached on request. (Move more values here if possible)
		String format = null;
		long fileSize = -1;
		String filename = null;
		IRI status = null;
		boolean invRelations = false;

		RepositoryConnection rc = null;

		try {
			String base = repositoryManager.getRepositoryURL().toString();
			rc = this.repository.getConnection();
//			referredIn = new HashSet<URI>();
			for (Statement statement : existingStatements) {
				IRI predicate = statement.getPredicate();
				if (predicate.equals(RepositoryProperties.resource)) {
					entryURI = (IRI) statement.getSubject();
					resURI = (IRI) statement.getObject();
				} else if (predicate.equals(RepositoryProperties.metadata)) {
					localMdURI = ((IRI) statement.getObject());
				} else if (predicate.equals(RepositoryProperties.externalMetadata)) {
					externalMdURI = ((IRI) statement.getObject());
				} else if (predicate.equals(RepositoryProperties.cachedExternalMetadata)) {
					cachedExternalMdURI = ((IRI) statement.getObject());
				} else if (predicate.equals(RepositoryProperties.relation)) {
					relationURI = ((IRI) statement.getObject());
				} else if (predicate.equals(RepositoryProperties.cached)) {
					// TODO: also wrong
					cachedAt = ((Literal) statement.getObject()).calendarValue();
//				} else if (predicate.equals(RepositoryProperties.referredIn)) {
//					referredIn.add(URI.create(statement.getObject().stringValue()));
				} else if (predicate.equals(RepositoryProperties.Created)) {
					created = ((Literal) statement.getObject()).calendarValue();
				} else if (predicate.equals(RepositoryProperties.Creator)) {
					creator = ((IRI) statement.getObject());
				} else if (predicate.equals(RepositoryProperties.Contributor)) {
					contributors.add((IRI) statement.getObject());
				} else if (predicate.equals(RepositoryProperties.Modified)) {
					try {
						//log.info(statement.getObject().stringValue());
						modified = ((Literal) statement.getObject()).calendarValue();
					} catch (NullPointerException e) {
						log.error(e);
					}
				} else {
					//Check if statement refer other entries that affect their inv-rel cache.
					if (!predicate.equals(RepositoryProperties.Read)
							&& !predicate.equals(RepositoryProperties.Write)
							&& !predicate.equals(RepositoryProperties.Pipeline)
							&& !predicate.equals(RepositoryProperties.originallyCreatedIn)) {
						Value obj = statement.getObject();
						org.eclipse.rdf4j.model.Resource subj = statement.getSubject();
						//Check for relations between this resource and another entry (resourceURI (has to be a repository resource), metadataURI, or entryURI)
						if (obj instanceof IRI
								&& obj.stringValue().startsWith(base)
								&& subj.stringValue().startsWith(base)) {
							invRelations = true;
						}
					}
				}
			}

			//Detect types.
			for (Statement statement : existingStatements) {
				org.eclipse.rdf4j.model.Resource subject = statement.getSubject();
				if (statement.getPredicate().equals(RDF.TYPE)) {
					if (resURI.equals(subject)) {
						GraphType gt = getGraphType(statement.getObject());
						if (gt != null) {
							graphType = gt;
						} else {
							ResourceType rt = getResourceType(statement.getObject());
							if (rt != null) {
								repType = rt;
							}
						}
					} else if (entryURI.equals(subject)) {
						EntryType lt = getEntryType(statement.getObject());
						if (lt != null) {
							locType = lt;
						}
					}
				}
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			throw e;
		} finally {
			rc.close();
		}

		// We set all values at once to avoid any delays and possible
		// inconsistencies that could occur when setting in the loop above
		this.administerPrincipals = administerPrincipals;
		this.readMetadataPrincipals = readMetadataPrincipals;
		this.writeMetadataPrincipals = writeMetadataPrincipals;
		this.readResourcePrincipals = readResourcePrincipals;
		this.writeResourcePrincipals = writeResourcePrincipals;
		this.contributors = contributors;
		this.entryURI = entryURI;
		this.resURI = resURI;
		this.localMdURI = localMdURI;
		this.cachedExternalMdURI = cachedExternalMdURI;
		this.relationURI = relationURI;
		this.created = created;
		this.creator = creator;
		this.modified = modified;
		this.externalMdURI = externalMdURI;
		this.cachedAt = cachedAt;
		this.locType = locType;
		this.repType = repType;
		this.graphType = graphType;
		this.format = format;
		this.fileSize = fileSize;
		this.filename = filename;
		this.invRelations = invRelations;

		return true;
	}

	private ResourceType getResourceType(Value rt) {
		if (rt.equals(RepositoryProperties.NamedResource)) {
			return ResourceType.NamedResource;
		} else if (rt.equals(RepositoryProperties.ResolvableInformationResource)) {
			return ResourceType.ResolvableInformationResource;
		} else if (rt.equals(RepositoryProperties.Unknown)) {
			return ResourceType.Unknown;
		} else if (rt.equals(RepositoryProperties.InformationResource)) {  //Default, unneccessary expression.
			return ResourceType.InformationResource;
		}
		return null;
	}

	private GraphType getGraphType(Value bt) {
		if (bt.equals(RepositoryProperties.Context)) {
			return GraphType.Context;
		} else if (bt.equals(RepositoryProperties.SystemContext)) {
			return GraphType.SystemContext;
		} else if (bt.equals(RepositoryProperties.List)) {
			return GraphType.List;
		} else if (bt.equals(RepositoryProperties.ResultList)) {
			return GraphType.ResultList;
		} else if (bt.equals(RepositoryProperties.User)) {
			return GraphType.User;
		} else if (bt.equals(RepositoryProperties.Group)) {
			return GraphType.Group;
		} else if (bt.equals(RepositoryProperties.Pipeline)) {
			return GraphType.Pipeline;
		} else if (bt.equals(RepositoryProperties.PipelineResult)) {
			return GraphType.PipelineResult;
		} else if (bt.equals(RepositoryProperties.String)) {
			return GraphType.String;
		} else if (bt.equals(RepositoryProperties.Graph)) {
			return GraphType.Graph;
		} else if (bt.equals(RepositoryProperties.None)) {
			return GraphType.None;
		} 
		return null;
	}

	private EntryType getEntryType(Value rt) {
		if (rt.equals(RepositoryProperties.Reference)) {
			return EntryType.Reference;
		} else if (rt.equals(RepositoryProperties.Link)) {
			return EntryType.Link;
		} else if (rt.equals(RepositoryProperties.LinkReference)) {
			return EntryType.LinkReference;
		} else if (rt.equals(RepositoryProperties.Local)) {
			return EntryType.Local;
		} 
		return null;
	}

	public URI getEntryURI() {
		return URI.create(entryURI.toString());
	}

	public IRI getSesameEntryURI() {
		return entryURI;
	}

	public IRI getSesameResourceURI() {
		return resURI;
	}

	public URI getResourceURI() {
		return URI.create(resURI.stringValue());
	}

	public URI getLocalMetadataURI() {
		if (localMdURI != null) {
			return URI.create(getSesameLocalMetadataURI().stringValue());
		}
		return null;
	}

	public IRI getSesameLocalMetadataURI() {
		return localMdURI;
	}

	public URI getExternalMetadataURI() {
		if (externalMdURI != null) {
			return URI.create(externalMdURI.stringValue());
		}
		return null;
	}

	public IRI getSesameExternalMetadataURI() {
		return externalMdURI;
	}

	public URI getCachedExternalMetadataURI() {
		if (cachedExternalMdURI != null) {
			return URI.create(cachedExternalMdURI.stringValue());
		}
		return null;
	}

	public IRI getSesameCachedExternalMetadataURI() {
		return cachedExternalMdURI;
	}

	public Date getExternalMetadataCacheDate() {
		if(cachedExternalMdURI == null) {
			return null; 
		}
		return cachedAt != null ? cachedAt.toGregorianCalendar().getTime() : null;
	}

	public URI getCreator() {
		if (this.creator != null) {
			return URI.create(this.creator.stringValue());
		}
		return null;
	}

	public void setCreator(URI userURI) {
		if (userURI == null) {
			throw new IllegalArgumentException("User URI must not be null");
		}
		checkAdministerRights();
		try {
			synchronized (this.repository) {
				RepositoryConnection rc = this.repository.getConnection();
				rc.begin();
				try {
					IRI creatorURI = rc.getValueFactory().createIRI(userURI.toString());
					rc.remove(rc.getStatements(entryURI, RepositoryProperties.Creator, null, false, entryURI), entryURI);
					rc.add(entryURI, RepositoryProperties.Creator, creatorURI, entryURI);
					registerEntryModified(rc, this.repository.getValueFactory());
					rc.commit();
					this.creator = creatorURI;
				} catch (Exception e) {
					rc.rollback();
					log.error(e.getMessage());
					throw new org.entrystore.repository.RepositoryException("Error in repository connection", e);
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			throw new org.entrystore.repository.RepositoryException("Failed to connect to repository", e);
		}
	}
	
	public Set<URI> getContributors() {
		Set<URI> result = new HashSet<URI>();
		for (IRI contribURI : this.contributors) {
			result.add(URI.create(contribURI.stringValue()));
		}
		return result;
	}

	public Date getCreationDate() {
		return created != null ? created.toGregorianCalendar().getTime() : null;
	}

	public Date getModifiedDate() {
		return modified != null ? modified.toGregorianCalendar().getTime() : null;
	}

	public GraphType getGraphType() {
		return graphType;
	}

	public ResourceType getResourceType() {
		return repType;
	}

	public EntryType getEntryType() {
		return locType;
	}

	public Graph getGraph() {
		//ACL check not necessary as the prerequisite for accessing the MetaMetadata object at all is
		//AccessProperty.readMetadata rights. It is supposed that the object is not delegated
		//to principals with less rights.
		RepositoryConnection rc = null; 
		try {
			rc = this.repository.getConnection();
			Graph graph = Iterations.addAll(rc.getStatements(null, null, null, false, entryURI), new LinkedHashModel());
			//TODO following is a fix for backwards compatability where homeContext is set on user object rather than in the entryinfo.
			if (this.resource instanceof User && ((User) this.resource).getHomeContext() != null) {
				Context context = ((User) this.resource).getHomeContext();
				graph.add(this.getSesameResourceURI(), RepositoryProperties.homeContext, ((ContextImpl) context).getSesameURI());
			}
			//End of fix.
			return graph;
		} catch (RepositoryException e) {
			log.error(e.getMessage(), e);
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
		} finally {
			try {
				rc.close();
			} catch (RepositoryException e) {
				log.error(e.getMessage());
			}
		}
	}

    public Set<URI> getReferringListsInSameContext() {
		HashSet<URI> set = new HashSet<URI>();
		List<Statement> relations = getRelations();
		for (Statement statement : relations) {
			if (statement.getPredicate().equals(RepositoryProperties.hasListMember) ||
				statement.getPredicate().equals(RepositoryProperties.hasGroupMember)) {
				set.add(URI.create(statement.getSubject().toString()));
			}
		}
		return set;
	}

	protected void addReferringList(ResourceImpl resource, RepositoryConnection rc) throws RepositoryException {
		synchronized (this.repository) {
			// rc.add(entryURI, RepositoryProperties.referredIn, listResource, entryURI);
			// referredIn.add(URI.create(listResource.stringValue()));
			ValueFactory vf = this.repository.getValueFactory();
			this.addRelationSynchronized(vf.createStatement(resource.resourceURI, 
					resource instanceof Group ? RepositoryProperties.hasGroupMember : RepositoryProperties.hasListMember, this.getSesameEntryURI()), rc, vf);
		}
	}

	protected void removeReferringList(ResourceImpl resource, RepositoryConnection rc) throws RepositoryException {
		synchronized (this.repository) {
			// rc.remove(entryURI, RepositoryProperties.referredIn, listResource, entryURI);
			// referredIn.remove(URI.create(listResource.stringValue()));
			ValueFactory vf = this.repository.getValueFactory();
			this.removeRelationSynchronized(vf.createStatement(resource.resourceURI, 
					resource instanceof Group ? RepositoryProperties.hasGroupMember : RepositoryProperties.hasListMember, this.getSesameEntryURI()), rc, vf);
		}
	}

	public void setEntryType(EntryType entryType) {
		checkAdministerRights();
		EntryType oldLT = locType;
		try {
			synchronized (this.repository) {
				RepositoryConnection rc = this.repository.getConnection();
				rc.setAutoCommit(false);
				try {
					// we add an MD triple of we convert from Reference to LinkReference
					if (EntryType.Reference.equals(locType) && EntryType.LinkReference.equals(entryType)) {
						rc.add(entryURI, RepositoryProperties.metadata, this.localMdURI, entryURI);
					}
					setLocationType(entryType, rc); // this also sets locType, therefore the assignment in the catch clause
					registerEntryModified(rc, this.repository.getValueFactory());
					rc.commit();
				} catch (Exception e) {
					rc.rollback();
					locType = oldLT;
					log.error(e.getMessage());
					throw new org.entrystore.repository.RepositoryException("Error in repository connection.", e);
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
		}
	}

	public void setResourceURI(URI resourceURI) {
		if (resourceURI.toString().equals(this.resURI.toString())) {
			return;
		}
		
		checkAdministerRights();

		ValueFactory vf = new GraphImpl().getValueFactory();
		IRI oldResourceURI = vf.createIRI(getResourceURI().toString());
		IRI newResourceURI = vf.createIRI(resourceURI.toString());

		// update entry graph
//		Graph entryGraph = getGraph();
//		Graph newEntryGraph = new GraphImpl();
//		for (Statement statement : entryGraph) {
//			if (statement.getSubject().equals(oldResourceURI)) {
//				// replace subject URI
//				newEntryGraph.add(newResourceURI, statement.getPredicate(), statement.getObject());
//			} else if (statement.getObject().equals(oldResourceURI)) {
//				// replace object URI
//				newEntryGraph.add(statement.getSubject(), statement.getPredicate(), newResourceURI);
//			} else {
//				// leave everything else untouched
//				newEntryGraph.add(statement);
//			}
//		}
		Graph entryGraph = getGraph();
		Graph newEntryGraph = new GraphImpl();
		for (Statement stmnt : entryGraph) {
			if (RepositoryProperties.resource.equals(stmnt.getPredicate())) {
				newEntryGraph.add(stmnt.getSubject(), stmnt.getPredicate(), newResourceURI);
			} else {
				newEntryGraph.add(stmnt);
			}
		}

		// update metadata graph
		Graph newMetadataGraph = null;
		if (getLocalMetadata() != null) {
			Graph metadataGraph = getLocalMetadata().getGraph();
			if (metadataGraph != null && metadataGraph.size() != 0) {
				newMetadataGraph = new GraphImpl();
				for (Statement statement : metadataGraph) {
					if (statement.getSubject().equals(oldResourceURI)) {
						// replace subject URI
						newMetadataGraph.add(newResourceURI, statement.getPredicate(), statement.getObject());
					} else {
						// leave everything else untouched
						newMetadataGraph.add(statement);
					}
				}
			}
		}
		
		// update cached external metadata graph
		Graph newCachedExternalMetadataGraph = null;
		if (getCachedExternalMetadata() != null) {
			Graph metadataGraph = getCachedExternalMetadata().getGraph();
			if (metadataGraph != null && metadataGraph.size() != 0) {
				newCachedExternalMetadataGraph = new GraphImpl();
				for (Statement statement : metadataGraph) {
					if (statement.getSubject().equals(oldResourceURI)) {
						// replace subject URI
						newCachedExternalMetadataGraph.add(newResourceURI, statement.getPredicate(), statement.getObject());
					} else {
						// leave everything else untouched
						newCachedExternalMetadataGraph.add(statement);
					}
				}
			}
		}

		// update resource graph if resource is builtin
		Graph newResourceGraph = null;
		if (!getGraphType().equals(GraphType.None)) {
			Graph resourceGraph = getResource().getEntry().getGraph();
			if (resourceGraph != null && resourceGraph.size() != 0) {
				newResourceGraph = new GraphImpl();
				for (Statement statement : resourceGraph) {
					if (statement.getSubject().equals(oldResourceURI)) {
						// replace subject URI
						newResourceGraph.add(newResourceURI, statement.getPredicate(), statement.getObject());
					} else {
						// leave everything else untouched
						newResourceGraph.add(statement);
					}
				}
			}
		}

		// update all graphs
		setGraphRaw(newEntryGraph);
		if (newMetadataGraph != null) {
			getLocalMetadata().setGraph(newMetadataGraph);
		}
		if (newCachedExternalMetadataGraph != null) {
			getCachedExternalMetadata().setGraph(newCachedExternalMetadataGraph);
		}
		if (newResourceGraph != null) {
			getResource().getEntry().setGraph(newResourceGraph);
		}
		
		// update index of context with new triple
		try {
			synchronized (this.repository) {
				RepositoryConnection rc = this.repository.getConnection();
				try {
					rc.setAutoCommit(false);
					IRI contextURI = vf.createIRI(this.getContext().getEntry().getResourceURI().toString());
					IRI entryURI = vf.createIRI(this.getEntryURI().toString());
					rc.remove(vf.createStatement(oldResourceURI, RepositoryProperties.resHasEntry, entryURI, contextURI));
					rc.add(vf.createStatement(newResourceURI, RepositoryProperties.resHasEntry, entryURI, contextURI));
					rc.commit();
				} catch (RepositoryException e) {
					rc.rollback();
					log.error(e.getMessage(), e);
				} finally {
					if (rc != null) {
						rc.close();
					}
				}
			}
		} catch (RepositoryException re) {
			log.error(re.getMessage(), re);
		}

		// update local variable with new URI
		this.resURI = newResourceURI;

		// update index
		this.context.updateResource2EntryIndex(
				URI.create(oldResourceURI.stringValue()),
				URI.create(this.resURI.stringValue()),
				URI.create(this.entryURI.stringValue())
		);
	}

	public void setExternalMetadataURI(URI externalMetadataURI) {
		if (this.externalMdURI != null && externalMetadataURI.toString().equals(this.externalMdURI.toString())) {
			return;
		}

		checkAdministerRights();

		ValueFactory vf = new GraphImpl().getValueFactory();
		IRI oldExternalMetadataURI = vf.createIRI(getExternalMetadataURI().toString());
		IRI newExternalMetadataURI = vf.createIRI(externalMetadataURI.toString());

		// update entry graph
		Graph entryGraph = getGraph();
		Graph newEntryGraph = new GraphImpl();
		for (Statement statement : entryGraph) {
			if (statement.getSubject().equals(oldExternalMetadataURI)) {
				// we don't want to take the cached date into the new graph,
				// so we don't to anything here
			} else if (statement.getObject().equals(oldExternalMetadataURI)) {
				// replace object URI
				newEntryGraph.add(statement.getSubject(), statement.getPredicate(), newExternalMetadataURI);
			} else {
				// leave everything else untouched
				newEntryGraph.add(statement);
			}
		}

		// set entry graph
		setGraphRaw(newEntryGraph);
		
		// update index of context with new triple
		try {
			synchronized (this.repository) {
				RepositoryConnection rc = this.repository.getConnection();
				try {
					rc.setAutoCommit(false);
					IRI contextURI = vf.createIRI(this.getContext().getEntry().getResourceURI().toString());
					IRI entryURI = vf.createIRI(this.getEntryURI().toString());
					rc.remove(vf.createStatement(oldExternalMetadataURI, RepositoryProperties.mdHasEntry, entryURI, contextURI));
					rc.add(vf.createStatement(newExternalMetadataURI, RepositoryProperties.mdHasEntry, entryURI, contextURI));
					rc.commit();
				} catch (RepositoryException e) {
					rc.rollback();
					log.error(e.getMessage(), e);
				} finally {
					if (rc != null) {
						rc.close();
					}
				}
			}
		} catch (RepositoryException re) {
			log.error(re.getMessage(), re);
		}

		// update local variable with new URI
		this.externalMdURI = newExternalMetadataURI;

		// update index
		this.context.updateExternalMetadata2EntryIndex(
				URI.create(oldExternalMetadataURI.stringValue()),
				URI.create(this.externalMdURI.stringValue()),
				URI.create(this.entryURI.stringValue())
		);
	}

	/**
	 * Sets a location to the entry. If the the entryType is Local no location is set.
	 * @param entryType
	 * @param rc
	 * @throws RepositoryException
	 * @throws DatatypeConfigurationException
	 */
	protected void setLocationType(EntryType entryType, RepositoryConnection rc) throws RepositoryException, DatatypeConfigurationException {
		rc.remove(rc.getStatements(entryURI, RDF.TYPE, null,false, entryURI), entryURI);
		switch (entryType) {
		case Reference:
			rc.add(entryURI, RDF.TYPE, RepositoryProperties.Reference, entryURI);
			break;
		case LinkReference:
			rc.add(entryURI, RDF.TYPE, RepositoryProperties.LinkReference, entryURI);
			break;
		case Link:
			rc.add(entryURI, RDF.TYPE, RepositoryProperties.Link, entryURI);
			break;
		}
		locType = entryType;
	}

	private Set<URI> getCachedAllowedPrincipalsFor(AccessProperty prop) {
		switch (prop) {
		case Administer:
			return administerPrincipals;
		case ReadMetadata:
			return readMetadataPrincipals;
		case WriteMetadata:
			return writeMetadataPrincipals;
		case ReadResource:
			return readResourcePrincipals;
		case WriteResource:
			return writeResourcePrincipals;
		}
		return null;
	}

	private void setCachedAllowedPrincipalsFor(AccessProperty prop, Set<URI> set) {
		switch (prop) {
		case Administer:
			administerPrincipals = set;
			break;
		case ReadMetadata:
			readMetadataPrincipals = set;
			break;
		case WriteMetadata:
			writeMetadataPrincipals = set;
			break;
		case ReadResource:
			readResourcePrincipals = set;
			break;
		case WriteResource:
			writeResourcePrincipals = set;
			break;
		}
	}

	public Set<URI> getAllowedPrincipalsFor(AccessProperty prop) {
		// No access check since everyone, even guest should have access to read entry information.
		Set<URI> set = getCachedAllowedPrincipalsFor(prop);
		if (set != null) {
			return set;
		}

		RepositoryConnection rc = null;
		try {
			rc = this.repository.getConnection();
			IRI subject = getAccessSubject(prop);
			IRI predicate = getAccessPredicate(prop);
			List<Statement> statements = rc.getStatements(subject, predicate, null, false, entryURI).asList();
			set = new HashSet<URI>();
			for (Statement statement : statements) {
				if (statement.getObject() instanceof IRI) {
					set.add(URI.create(((IRI) statement.getObject()).stringValue()));
				}
			}
			setCachedAllowedPrincipalsFor(prop, set);
			return set;
		} catch (RepositoryException e) {
			e.printStackTrace();
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
		} finally {
			try {
				rc.close();
			} catch (RepositoryException e) {
				e.printStackTrace();
			}
		}
	}

	public void setAllowedPrincipalsFor(AccessProperty prop, Set<URI> principals) {
		checkAdministerRights();
		updateAllowedPrincipalsFor(prop, principals, true, false);
	}

	public void addAllowedPrincipalsFor(AccessProperty prop, URI principal) {
		checkAdministerRights();
		HashSet<URI> principals = new HashSet<URI>();
		principals.add(principal);
		updateAllowedPrincipalsFor(prop, principals, false, true);
	}
	
	public boolean removeAllowedPrincipalsFor(AccessProperty prop, URI principal) {
		checkAdministerRights();
		HashSet<URI> principals = new HashSet<URI>();
		principals.add(principal);
		return updateAllowedPrincipalsFor(prop, principals, false, false);
	}

	public boolean updateAllowedPrincipalsFor(AccessProperty prop, Set<URI> principals, boolean replace, boolean append) {
		this.readOrWrite = null;
		try {
			synchronized (this.repository) {
				RepositoryConnection rc = this.repository.getConnection();
				rc.setAutoCommit(false);
				try {
					IRI subject = getAccessSubject(prop);
					IRI predicate = getAccessPredicate(prop);

					if (replace) {
						rc.remove(subject, predicate, null, entryURI);
					}

					for (URI principal : principals) {
						IRI principalURI = this.repository.getValueFactory().createIRI(principal.toString());
						if (replace || append) {
							rc.add(subject, predicate, principalURI, entryURI);						
						} else {
							rc.remove(subject, predicate, principalURI, entryURI);						
						}
					}
					rc.commit();
					if (replace) {
						setCachedAllowedPrincipalsFor(prop, principals);
					} else {
						setCachedAllowedPrincipalsFor(prop, null);						
					}
				} catch (Exception e) {
					rc.rollback();
					e.printStackTrace();
					throw new org.entrystore.repository.RepositoryException("Error in repository connection.", e);
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage(), e);
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
		}
		return false;
	}

	public boolean hasAllowedPrincipals() {
		if (this.readOrWrite == null) {
			try {
				RepositoryConnection rc = this.repository.getConnection();
				try {
					if (rc.hasStatement(null, RepositoryProperties.Write, null, false, entryURI) ||
							rc.hasStatement(null, RepositoryProperties.Read, null, false, entryURI)) {
						this.readOrWrite = Boolean.TRUE;
					} else {
						this.readOrWrite = Boolean.FALSE;
					}
				} finally {
					rc.close();
				}
			} catch (RepositoryException e) {
				log.error(e.getMessage());
			}
		}
		return this.readOrWrite.booleanValue();
	}
	
	private IRI getAccessSubject(AccessProperty prop) {
		switch (prop) {
		case Administer:
			return entryURI;
		case ReadMetadata:
		case WriteMetadata:
			return localMdURI;
		case ReadResource:
		case WriteResource:
			return resURI;
		}
		return null;
	}

	private IRI getAccessPredicate(AccessProperty prop) {
		switch (prop) {
		case Administer:
		case WriteMetadata:
		case WriteResource:
			return RepositoryProperties.Write;
		case ReadResource:
		case ReadMetadata:
			return RepositoryProperties.Read;
		}
		return null;
	}

	public void setGraphType(GraphType gt) {
		checkAdministerRights();
		if (this.graphType != gt && this.locType == EntryType.Local) {
			throw new org.entrystore.repository.RepositoryException("Cannot change the graph type of a local resource");
		}
		try {
			synchronized (this.repository) {
				RepositoryConnection rc = this.repository.getConnection();
				rc.setAutoCommit(false);
				try {
					setGraphType(gt, rc);
					registerEntryModified(rc, this.repository.getValueFactory());
					rc.commit();
				} catch (Exception e) {
					rc.rollback();
					throw new org.entrystore.repository.RepositoryException("Error in repository connection.", e);
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
		}

	}

	/**
	 * If this method is called successfully (no Exception thrown), and for some reason 
	 * the transaction is rolled back, than the cached built-in type will be wrong, 
	 * call {@link #refreshFromRepository(RepositoryConnection)} to correct this.
	 * 
	 * @param gt the new {@link org.entrystore.GraphType}
	 * @param rc a RepositoryConnection 
	 * @throws RepositoryException
	 * @throws DatatypeConfigurationException
	 */
	protected void setGraphType(GraphType gt, RepositoryConnection rc) throws RepositoryException, DatatypeConfigurationException {
		List<Statement> statements = rc.getStatements(resURI, RDF.TYPE, null, false, entryURI).asList();
		for (Statement statement : statements) {
			if (getGraphType(statement.getObject()) != null) {
				rc.remove(statement, entryURI);
			}
		}
		switch (gt) {
		case Context:
			rc.add(resURI, RDF.TYPE, RepositoryProperties.Context, entryURI);
			break;
		case SystemContext:
			rc.add(resURI, RDF.TYPE, RepositoryProperties.SystemContext, entryURI);
			break;
		case List:
			rc.add(resURI, RDF.TYPE, RepositoryProperties.List, entryURI);
			break;
		case ResultList:
			rc.add(resURI, RDF.TYPE, RepositoryProperties.ResultList, entryURI);
			break;
		case User:
			rc.add(resURI, RDF.TYPE, RepositoryProperties.User, entryURI);
			break;
		case Group:
			rc.add(resURI, RDF.TYPE, RepositoryProperties.Group, entryURI);
			break;
		case Pipeline:
			rc.add(resURI, RDF.TYPE, RepositoryProperties.Pipeline, entryURI);
			break;
		case PipelineResult:
			rc.add(resURI, RDF.TYPE, RepositoryProperties.PipelineResult, entryURI);
			break;
		case String:
			rc.add(resURI, RDF.TYPE, RepositoryProperties.String, entryURI); 
			break;
		case Graph:
			rc.add(resURI, RDF.TYPE, RepositoryProperties.Graph, entryURI);
		}
		
		graphType = gt;
	}

	public void setResourceType(ResourceType resType) {
		checkAdministerRights();
		if (this.repType != resType && this.locType == EntryType.Local && (this.graphType != GraphType.None && this.graphType != GraphType.Pipeline && this.graphType != GraphType.PipelineResult)) {
			throw new org.entrystore.repository.RepositoryException("Cannot change the resource type of a local and/or built-in resource");
		}
		try {
			synchronized (this.repository) {
				RepositoryConnection rc = this.repository.getConnection();
				rc.setAutoCommit(false);
				try {
					setResourceType(resType, rc);
					registerEntryModified(rc, this.repository.getValueFactory());
					rc.commit();
				} catch (Exception e) {
					rc.rollback();
					throw new org.entrystore.repository.RepositoryException("Error in repository connection.", e);
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage(), e);
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository", e);
		}		
	}

	protected void setResourceType(ResourceType resType, RepositoryConnection rc) throws RepositoryException, DatatypeConfigurationException {
		List<Statement> statements = rc.getStatements(resURI, RDF.TYPE, null, false, entryURI).asList();
		for (Statement statement : statements) {
			if (getResourceType(statement.getObject()) != null) {
				rc.remove(statement, entryURI);
			}
		}

		switch (resType) {
		case ResolvableInformationResource:
			rc.add(resURI, RDF.TYPE, RepositoryProperties.ResolvableInformationResource, entryURI);
			break;
		case Unknown:
			rc.add(resURI, RDF.TYPE, RepositoryProperties.Unknown, entryURI);
			break;
		case NamedResource:
			rc.add(resURI, RDF.TYPE, RepositoryProperties.NamedResource, entryURI);
			break;
		}
		repType = resType;
	}

	protected void updateModifiedDateSynchronized(RepositoryConnection rc, ValueFactory vf) throws RepositoryException, DatatypeConfigurationException {
		synchronized (this.repository) {
			registerEntryModified(rc, vf);
		}
	}

	protected void registerEntryModified(RepositoryConnection rc, ValueFactory vf) throws RepositoryException {
		try {
			modified = DatatypeFactory.newInstance().newXMLGregorianCalendar(new GregorianCalendar());
		} catch (DatatypeConfigurationException e) {
			log.error(e.getMessage());
			throw new RepositoryException(e.getMessage(), e);
		}
		rc.remove(rc.getStatements(entryURI, RepositoryProperties.Modified, null, false, entryURI), entryURI);
		rc.add(entryURI, RepositoryProperties.Modified, vf.createLiteral(modified), entryURI);
		
		//Also adding the one who update using dcterms:contributor
		if (this.repositoryManager != null &&
				this.repositoryManager.getPrincipalManager() != null &&
				this.repositoryManager.getPrincipalManager().getAuthenticatedUserURI() != null){
			URI contrib = this.repositoryManager.getPrincipalManager().getAuthenticatedUserURI();
			String contributor = contrib.toString();
			IRI contributorURI = vf.createIRI(contributor);
		
		    //Do not add if the contributor is the same as the creator
		    if(contrib != null && !contrib.equals(this.getCreator()) && contributors != null && !contributors.contains(contributorURI)) {
		    	rc.add(this.entryURI,RepositoryProperties.Contributor,contributorURI, this.entryURI);
		    	contributors.add(contributorURI);
		    }
		}
	}

	public void updateCachedExternalMetadataDateSynchronized(RepositoryConnection rc, ValueFactory vf) throws RepositoryException, DatatypeConfigurationException {
		synchronized (this.repository) {
			if (this.getEntryType() == EntryType.Reference || this.getEntryType() == EntryType.LinkReference) {
				cachedAt = DatatypeFactory.newInstance().newXMLGregorianCalendar(new GregorianCalendar());
				rc.remove(rc.getStatements(cachedExternalMdURI, RepositoryProperties.cached, null, false, entryURI), entryURI);
				// TODO: maybe entryUri as context last parameter..
				rc.add(cachedExternalMdURI, RepositoryProperties.cached, vf.createLiteral(cachedAt), entryURI);
			}
			registerEntryModified(rc, vf);
		}
	}

	/**
	 * WARNING, do not use unless you are very certain that you know what you are doing.
	 * Replaces the entryinformation without questions asked.
     * The new graph should not change anything that affects inverse-relation-cache on other entries, if so, use setGraph instead.
     *
	 * @param metametadata
	 */
	protected void setGraphRaw(Graph metametadata) {
		checkAdministerRights();
		try {
			synchronized (this.repository) {
				RepositoryConnection rc = this.repository.getConnection();
				ValueFactory vf = this.repository.getValueFactory();
				rc.setAutoCommit(false);
				try {
					rc.clear(entryURI);
					rc.add(metametadata, entryURI);
					registerEntryModified(rc, vf);
					rc.commit();

					// we reload the internal cache
					loadFromStatements(rc.getStatements(null, null, null, false, entryURI).asList());
					initMetadataObjects();

					getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(this, RepositoryEvent.EntryUpdated));
				} catch (Exception e) {
					rc.rollback();
					// Reset to previous saved values, just in case we saved the types above halfway through.
					loadFromStatements(rc.getStatements(null, null, null, false, entryURI).asList());
					rc.close();
					throw new org.entrystore.repository.RepositoryException("Error in repository connection.", e);
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
		}		
	}

	public void setGraph(Graph metametadata) {
		checkAdministerRights();

		Graph oldGraph = getGraph();
		
		Iterator<Statement> resourceURIStmnts = metametadata.match(this.entryURI, RepositoryProperties.resource, null);
		if (resourceURIStmnts.hasNext()) {
			Value newResourceURI = resourceURIStmnts.next().getObject();
			if (newResourceURI instanceof IRI) {
				setResourceURI(URI.create(newResourceURI.toString()));
			}
		}
		
		Iterator<Statement> externalMdURIStmnts = metametadata.match(this.entryURI, RepositoryProperties.externalMetadata, null);
		if (externalMdURIStmnts.hasNext()) {
			Value newResourceURI = externalMdURIStmnts.next().getObject();
			if (newResourceURI instanceof IRI) {
				setExternalMetadataURI(URI.create(newResourceURI.toString()));
			}
		}
		String originalList = this.getOriginalList();
		
		try {
			synchronized (this.repository) {
				ValueFactory vf = this.repository.getValueFactory();
				RepositoryConnection rc = this.repository.getConnection();
				rc.setAutoCommit(false);
				Graph minimalProvenanceGraph = null;
				if (this.provenance != null) {
					minimalProvenanceGraph = this.provenance.getMinimalGraph(rc);
				}

				try {
                    removeInverseRelations(rc);
					rc.clear(entryURI);

					for (Statement statement : metametadata) {
						IRI predicate = statement.getPredicate();
						if (predicate.stringValue().startsWith(RepositoryProperties.NSbase)) {
							//Ignore basic structure subgraph, will be added below.
							if (!(predicate.equals(RepositoryProperties.resource) 
									|| predicate.equals(RepositoryProperties.metadata)
									|| predicate.equals(RepositoryProperties.externalMetadata)
									|| predicate.equals(RepositoryProperties.cachedExternalMetadata)
									|| predicate.equals(RepositoryProperties.cached)
									|| predicate.equals(RepositoryProperties.relation)
									//|| predicate.equals(RepositoryProperties.referredIn)
									)) {
								rc.add(statement, entryURI);
							}
						} else if (this.entryURI.equals(statement.getSubject())
								&& (predicate.equals(RepositoryProperties.Created)
										|| predicate.equals(RepositoryProperties.Modified)
										|| predicate.equals(RepositoryProperties.Creator)
										|| predicate.equals(RepositoryProperties.Contributor))) {
							//In basic structure below.
						} else if (predicate.equals(RDF.TYPE)) {
							if (this.entryURI.equals(statement.getSubject())) {
								EntryType lt = getEntryType(statement.getObject());
								if (lt != null && locType == EntryType.Reference && lt == EntryType.LinkReference) { //Only allowed to change from Reference to LinkReference
									locType = lt;
									localMdURI = vf.createIRI(URISplit.fabricateURI(repositoryManager.getRepositoryURL().toString(), context.id, RepositoryProperties.MD_PATH, this.id).toString());
								}
							} else {
								GraphType gt = getGraphType(statement.getObject());
								if (gt != null) {
									if (locType != EntryType.Local) { //Only allowed to change builtintype for non local resources.
										this.graphType = gt;
									}
								} else {
									ResourceType rt = getResourceType(statement.getObject());
									if (rt != null) {
										if (locType != EntryType.Local) { //Only allowed to change representationtype for non local resources.
											repType = rt;
										}
									} else { //Some other rdf:type, just add it.
										rc.add(statement, entryURI);										
									}
								}
							}
						} else if (this.provenance != null
								&& this.provenance.hasProvenanceCharacter(statement)) {
							//Filter out provenance as it will be added back in a controlled manner below
						} else {
							rc.add(statement, entryURI);
						}
					}
					
					//----------Start basic structure:
					//Since we cleared the previous graph, we set the types again, they might have been updated in the loop above.
					setLocationType(this.locType, rc);
					setGraphType(this.graphType, rc);
					setResourceType(this.repType, rc);

					rc.add(entryURI, RepositoryProperties.resource, getSesameResourceURI(), entryURI);
					rc.add(entryURI, RepositoryProperties.relation, getSesameRelationURI(), entryURI);
					if (localMdURI != null) {
						rc.add(entryURI, RepositoryProperties.metadata, localMdURI, entryURI);
					}

					if (externalMdURI != null) {
						rc.add(entryURI, RepositoryProperties.externalMetadata, externalMdURI, entryURI);
					}
					if (cachedExternalMdURI != null) {
						rc.add(entryURI, RepositoryProperties.cachedExternalMetadata, cachedExternalMdURI, entryURI);
						if (cachedAt != null) {
							rc.add(cachedExternalMdURI, RepositoryProperties.cached, vf.createLiteral(cachedAt), entryURI);
						}
					}
/*					for (URI refIn : this.referredIn) { //Adds the referredIn relations to the graph.
						rc.add(entryURI, RepositoryProperties.referredIn, vf.createIRI(refIn.toString()), entryURI);
					}*/

					if (created != null) {
						rc.add(entryURI, RepositoryProperties.Created, vf.createLiteral(created), entryURI);
					}
					
					if (creator != null) {
						rc.add(entryURI, RepositoryProperties.Creator, creator, entryURI);
					}
					
					if (contributors != null && contributors.size()>0){
						for (Iterator<IRI> iter = contributors.iterator(); iter.hasNext();) {
							IRI contrib =  iter.next();
							rc.add(entryURI, RepositoryProperties.Contributor, contrib, entryURI);
						}
					}
					
					if (originalList !=null) {
						rc.add(entryURI, RepositoryProperties.originallyCreatedIn, vf.createIRI(originalList), entryURI);
					}

					modified = DatatypeFactory.newInstance().newXMLGregorianCalendar(new GregorianCalendar());
					rc.add(entryURI, RepositoryProperties.Modified, vf.createLiteral(modified), entryURI);
					if (minimalProvenanceGraph != null) {
						rc.add(minimalProvenanceGraph, entryURI);
					}
					//------------End basic structure

                    addInverseRelations(rc, metametadata);
					rc.commit();
					administerPrincipals = null;
					readMetadataPrincipals = null;
					writeMetadataPrincipals = null;
					readResourcePrincipals = null;
					writeResourcePrincipals = null;
					readOrWrite = null;
					format = null;
					status = null;

					// we reload the internal cache
					loadFromStatements(rc.getStatements(null, null, null, false, entryURI).asList());
					initMetadataObjects();
					
					getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(this, RepositoryEvent.EntryUpdated));
					if (hasAclChangedForGuest(oldGraph, metametadata)) {
						getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(this, RepositoryEvent.EntryAclGuestUpdated, metametadata));
					}
				} catch (Exception e) {
					rc.rollback();
					// Reset to previous saved values, just in case we saved the types above halfway through.
					loadFromStatements(rc.getStatements(null, null, null, false, entryURI).asList());
					throw new org.entrystore.repository.RepositoryException("Error in repository connection.", e);
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
		}
	}

	private boolean hasAclChangedForGuest(Graph oldGraph, Graph newGraph) {
		if (oldGraph == null || newGraph == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}

		Model oldModel = new LinkedHashModel(oldGraph);
		Model newModel = new LinkedHashModel(newGraph);

		IRI guestURI = new URIImpl(getRepositoryManager().getPrincipalManager().getGuestUser().getURI().toString());

		Model oldAcl = new LinkedHashModel();
		oldAcl.addAll(oldModel.filter(null, RepositoryProperties.Read, guestURI));
		oldAcl.addAll(oldModel.filter(null, RepositoryProperties.Write, guestURI));

		Model newAcl = new LinkedHashModel();
		newAcl.addAll(newModel.filter(null, RepositoryProperties.Read, guestURI));
		newAcl.addAll(newModel.filter(null, RepositoryProperties.Write, guestURI));

		return !ModelUtil.equals(oldAcl, newAcl);
	}

    private boolean isStatementInvRelationCandidate(Statement statement, String base) {
		IRI predicate = statement.getPredicate();
        if (!predicate.equals(RepositoryProperties.resource)
                && !predicate.equals(RepositoryProperties.metadata)
                && !predicate.equals(RepositoryProperties.externalMetadata)
                && !predicate.equals(RepositoryProperties.cachedExternalMetadata)
                && !predicate.equals(RepositoryProperties.relation)
                && !predicate.equals(RepositoryProperties.cached)
                && !predicate.equals(RepositoryProperties.Creator)
                && !predicate.equals(RepositoryProperties.Contributor)
                && !predicate.equals(RepositoryProperties.Read)
                && !predicate.equals(RepositoryProperties.Write)
//                && !predicate.equals(RepositoryProperties.Pipeline)
                && !predicate.equals(RepositoryProperties.originallyCreatedIn)) {
            Value obj = statement.getObject();
            org.eclipse.rdf4j.model.Resource subj = statement.getSubject();
            //Check for relations between this resource and another entry (resourceURI (has to be a repository resource), metadataURI, or entryURI)
            if (obj instanceof IRI
                    && obj.stringValue().startsWith(base)
                    && subj.stringValue().startsWith(base)) {
                return true;
            }
        }
        return false;
    }

    private void removeInverseRelations(RepositoryConnection rc) throws RepositoryException {
        if (invRelations) {
            RepositoryResult<Statement> rr = rc.getStatements(null, null, null, false, entryURI);
            String base = repositoryManager.getRepositoryURL().toString();
            while (rr.hasNext()) {
                Statement statement = rr.next();
                if (isStatementInvRelationCandidate(statement, base)) {
                    URI entryURI = URI.create(statement.getObject().stringValue());
                    EntryImpl sourceEntry =  (EntryImpl) repositoryManager.getContextManager().getEntry(entryURI);
                    if (sourceEntry != null && sourceEntry != this) {
                        sourceEntry.removeRelationSynchronized(statement, rc, repository.getValueFactory());
                    }
                }
            }
            rr.close();
            invRelations = false;
        }
    }

    private void addInverseRelations(RepositoryConnection rc, Graph graph) {
        String base = repositoryManager.getRepositoryURL().toString();
        for (Statement statement : graph) {
            if (isStatementInvRelationCandidate(statement, base)) {
                URI entryURI = URI.create(statement.getObject().stringValue());
                EntryImpl sourceEntry =  (EntryImpl) repositoryManager.getContextManager().getEntry(entryURI);
                if (sourceEntry != null && sourceEntry != this) {
                    sourceEntry.addRelationSynchronized(statement, rc, repository.getValueFactory());
                }
            }
        }
    }

    private void checkAdministerRights() {
		PrincipalManager pm = this.getRepositoryManager().getPrincipalManager();
		pm.checkAuthenticatedUserAuthorized(this, AccessProperty.Administer);
	}

	public Context getContext() {
		return context;
	}

	public RepositoryManager getRepositoryManager() {
		return repositoryManager;
	}

	@Override
	public Provenance getProvenance() {
		return this.provenance;
	}

	public Resource getResource() {
		if(resource == null) {
			ContextImpl contextImpl = ((ContextImpl)this.getContext()); 
			try {
				contextImpl.initResource(this);
			} catch (RepositoryException e) {
				log.error(e.getMessage()); 
			} 
		}
		return resource;
	}

	public Repository getRepository() {
		return repository;
	}

	public void setResource(Resource resource) {
		this.resource = resource;
		
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof Entry) {
			return getEntryURI().equals(((Entry)obj).getEntryURI());
		}
		return false;
	}

	public Metadata getCachedExternalMetadata() {
		return cachedExternalMetadata;
	}

	public boolean isExternalMetadataCached() {
		return getExternalMetadataCacheDate() != null;
	}

	public Metadata getLocalMetadata() {
		if (localMetadata == null) {
			initMetadataObjects(); 
		}

		return localMetadata;
	}

	public Graph getMetadataGraph() {
		if (getEntryType().equals(EntryType.Local) || getEntryType().equals(EntryType.Link)) {
			if (getLocalMetadata() != null && getLocalMetadata().getGraph() != null) {
				return getLocalMetadata().getGraph();
			}
		} else if (getEntryType().equals(EntryType.Reference)) {
			if (getCachedExternalMetadata() != null && getCachedExternalMetadata().getGraph() != null) {
				return getCachedExternalMetadata().getGraph();
			}
		} else if (getEntryType().equals(EntryType.LinkReference)) {
			Graph mergedMd = new GraphImpl();
			if (getLocalMetadata() != null && getLocalMetadata().getGraph() != null) {
				mergedMd.addAll(getLocalMetadata().getGraph());
			}
			if (getCachedExternalMetadata() != null && getCachedExternalMetadata().getGraph() != null) {
				mergedMd.addAll(getCachedExternalMetadata().getGraph());
			}
			return mergedMd;
		}
		return new LinkedHashModel();
	}

	public void remove(RepositoryConnection rc) throws Exception {
		// TODO the handling of removal is non-atomic and should be rewritten to take
		//  failures (i.e. rollbacks of the ongoing transaction) into consideration
		deleted = true;

		log.debug("Removing entry " + entryURI);
        removeInverseRelations(rc);
		rc.clear(entryURI);
		if (locType == EntryType.Local || locType == EntryType.Link || locType == EntryType.LinkReference) {
			localMetadata.removeGraphSynchronized(rc);
		}
		if (locType == EntryType.LinkReference || locType == EntryType.Reference) {
            if (cachedExternalMetadata instanceof MetadataImpl) {
                ((MetadataImpl) cachedExternalMetadata).removeGraphSynchronized(rc);
            } else {
                rc.clear(cachedExternalMdURI);
            }
		}

		localMetadata = null;
		cachedExternalMetadata = null;

		if (resource != null && resource.isRemovable()) {
			resource.remove(rc);
			resource = null;
		}
	}

	public String getFilename() {
		if (this.filename == null) {
			Statement st = getStatement(resURI, RepositoryProperties.filename, null);
			if (st != null) {
				this.filename = st.getObject().stringValue();	
			}
		}
		return this.filename;
	}

	public IRI getSesameRelationURI() {
		return this.relationURI;
	}

	public URI getRelationURI() {
		return  URI.create(relationURI.toString());
	}

	public void setFilename(String name) {
		PrincipalManager pm = this.getRepositoryManager().getPrincipalManager();
		pm.checkAuthenticatedUserAuthorized(this, AccessProperty.WriteResource);

		ValueFactory vf = this.repository.getValueFactory();		
		if (replaceStatement(resURI, RepositoryProperties.filename, vf.createLiteral(name))) {
			this.filename = name;
		}
	}

	public long getFileSize() {
		if (this.fileSize > 0 ) {
			Statement st = getStatement(resURI, RepositoryProperties.fileSize, null);
			if (st != null) {
				this.fileSize = ((Literal) st.getObject()).longValue();	
			}
		}
		return this.fileSize;
	}

	public void setFileSize(long size) {
		PrincipalManager pm = this.getRepositoryManager().getPrincipalManager();
		pm.checkAuthenticatedUserAuthorized(this, AccessProperty.WriteResource);

		ValueFactory vf = this.repository.getValueFactory();		
		if (replaceStatement(resURI, RepositoryProperties.fileSize, vf.createLiteral(size))) {
			this.fileSize = size;
		}
	}
	public URI getStatus() {
		if (this.status == null) {
			// the mime type in the local MD overwrites the mime type in the entry graph
			Statement st = getStatement(entryURI, RepositoryProperties.status, null);
			if (st != null) {
				this.status = URI.create(st.getObject().stringValue());
			}
		}
		return this.status;
	}

	public void setStatus(URI newStatus) {
		checkAdministerRights();
		ValueFactory vf = this.repository.getValueFactory();
		if (replaceStatement(entryURI, RepositoryProperties.status, vf.createIRI(newStatus.toString()))) {
			this.status = newStatus;
		}
	}

	public String getMimetype() {
		if (this.format == null) {
			// the mime type in the local MD overwrites the mime type in the entry graph
			String mtMd = getMimetypeFromMetadata();
			if (mtMd != null) {
				this.format = mtMd;
				return this.format;
			}
			Statement st = getStatement(resURI, RepositoryProperties.format, null);
			if (st != null) {
				this.format = st.getObject().stringValue();				
			}
		}
		return this.format;
	}

	public void setMimetype(String mt) {
		PrincipalManager pm = this.getRepositoryManager().getPrincipalManager();
		pm.checkAuthenticatedUserAuthorized(this, AccessProperty.WriteResource);

		ValueFactory vf = this.repository.getValueFactory();		
		if (replaceStatement(resURI, RepositoryProperties.format, vf.createLiteral(mt))) {
			this.format = mt;
		}
	
		// if the mime-type is set (overwritten) in the metadata, we take that one instead
		String mtMd = getMimetypeFromMetadata();
		if (mtMd != null) {
			this.format = mtMd;
		}
	}

	public boolean isDeleted() {
		return deleted;
	}
	
	private String getMimetypeFromMetadata() {
		Statement st = getStatementFromLocalMetadata(resURI, RepositoryProperties.format, null);
		if (st != null) {
			return st.getObject().stringValue();
		}
		return null;
	}

	public boolean replaceStatementSynchronized(IRI subject, IRI predicate, Value object, RepositoryConnection rc, ValueFactory vf) throws RepositoryException {
		rc.remove(subject, predicate, null, entryURI);
		rc.add(subject, predicate, object, entryURI);
		registerEntryModified(rc, vf);
		return true;
	}
	
	public boolean replaceStatementSynchronized(IRI subject, IRI predicate, Value object) {
		try {
			RepositoryConnection rc = this.repository.getConnection();
			rc.setAutoCommit(false);
			try {
				boolean result = this.replaceStatementSynchronized(subject, predicate, object, rc, this.repository.getValueFactory());
				rc.commit();
				return result;
			} catch (Exception e) {
				rc.rollback();
				e.printStackTrace();
				throw new org.entrystore.repository.RepositoryException("Error in repository connection.", e);
			} finally {
				rc.close();
			}
		} catch (RepositoryException e) {
			e.printStackTrace();
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
		}		
	}
	
	public boolean replaceStatement(IRI subject, IRI predicate, Value object) {
		synchronized (this.repository) {
			return this.replaceStatementSynchronized(subject, predicate, object);
		}
	}

	public Statement getStatement(IRI subject, IRI predicate, Value object) {
		try {
			RepositoryConnection rc = this.repository.getConnection();
			try {
				RepositoryResult<Statement> matches = rc.getStatements(subject, predicate, object, false, entryURI);
				if (matches.hasNext()) {
					Statement result = matches.next();
					matches.close();
					return result;
				}
				if (!matches.isClosed()) {
					matches.close();
				}
				rc.close();				
				return null;
			} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
				rc.close();
				log.error(e.getMessage());
				throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
			} finally {
				rc.close();
			}
		} catch (RepositoryException e) {
			e.printStackTrace();
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
		}
	}

	public Statement getStatementFromLocalMetadata(IRI subject, IRI predicate, Value object) {
		if (localMdURI == null) {
			return null;
		}

		try {
			RepositoryConnection rc = this.repository.getConnection();
			try {
				RepositoryResult<Statement> matches = rc.getStatements(subject, predicate, object, false, localMdURI);
				if (matches.hasNext()) {
					Statement result = matches.next();
					matches.close();
					return result;
				}
				if (!matches.isClosed()) {
					matches.close();
				}
				rc.close();				
				return null;
			} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
				rc.close();
				log.error(e.getMessage());
				throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
			} finally {
				rc.close();
			}
		} catch (RepositoryException e) {
			e.printStackTrace();
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
		}
	}

	/**
	 * @return the original list where the entry was created, null if the creator was the owner of 
	 * the current context or has since added it to another list or removed it from all lists.
	 */
	public String getOriginalList () {
		if (this.originalList == null) {
			Statement st = this.getStatement(this.entryURI, RepositoryProperties.originallyCreatedIn, null);
			if (st != null &&  st.getObject() instanceof IRI) {
				this.originalList = st.getObject().stringValue();
			} else {
				this.originalList = "";
			}
		}
		if (this.originalList.equals("")) {
			return null;
		}
		return this.originalList;
	}

	public void setOriginalListSynchronized(String list, RepositoryConnection rc, ValueFactory vf) throws RepositoryException {
		rc.remove(this.entryURI, RepositoryProperties.originallyCreatedIn, null, this.entryURI);
		if (list != null) {
			rc.add(this.entryURI, RepositoryProperties.originallyCreatedIn, vf.createIRI(list), this.entryURI);
			this.originalList = list;
		} else {
			this.originalList = "";
		}
	}

	public void setOriginalListSynchronized(String list) {
		try {
			RepositoryConnection rc = this.repository.getConnection();
			rc.setAutoCommit(false);
			try {
				rc.remove(this.entryURI, RepositoryProperties.originallyCreatedIn, null, this.entryURI);
				if (list != null) {
					rc.add(this.entryURI, RepositoryProperties.originallyCreatedIn, this.repository.getValueFactory().createIRI(list), this.entryURI);
					this.originalList = list;
				} else {
					this.originalList = "";
				}
				rc.commit();
			} catch (Exception e) {
				rc.rollback();
				e.printStackTrace();
				throw new org.entrystore.repository.RepositoryException("Error in repository connection.", e);
			} finally {
				rc.close();
			}
		} catch (RepositoryException e) {
			e.printStackTrace();
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
		}		
	}
	
	public List<Statement> getRelations() {
		if (this.relations == null) {
			RepositoryConnection rc = null;
			try {
				rc = this.repository.getConnection();
				return Iterations.asList(rc.getStatements(null, null, null, false, this.relationURI));
			} catch (RepositoryException e) {
				log.error(e.getMessage());
				throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
			} finally {
				try {
					rc.close();
				} catch (RepositoryException e) {
					log.error(e.getMessage(), e);
				}
			}
		}
		return this.relations;
	}
	
	protected void addRelationSynchronized(Statement statement, RepositoryConnection rc, ValueFactory vf) {
		synchronized (this) {
			addRelation(statement, rc, vf);
		}
	}

	private void addRelation(Statement statement, RepositoryConnection rc, ValueFactory vf) {
		try {
			rc.add(statement, relationURI);
			if (this.relations != null) {
				this.relations.add(statement);
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			throw new org.entrystore.repository.RepositoryException("Failed to connect to repository", e);
		} 
	}
	
	protected void removeRelationSynchronized(Statement statement, RepositoryConnection rc, ValueFactory vf) {
		synchronized (this) {
			removeRelation(statement, rc, vf);
		}
	}

	private void removeRelation(Statement statement, RepositoryConnection rc, ValueFactory vf) {
		try {
			rc.remove(statement, relationURI);
			if (this.relations != null) {
				this.relations.remove(statement);
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			throw new org.entrystore.repository.RepositoryException("Failed to connect to repository", e);
		} 
	}
	
	@Override
	public String toString() {
		return new StringBuffer(entryURI.toString()).append(",").append(super.toString()).toString();
	}

}