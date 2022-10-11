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

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.entrystore.GraphType;

/**
 * This class sets static properties for the repository.
 * NS stands for namespace.
 * @author Mattias Palmer, Eric Johansson (eric.johansson@educ.umu.se)
 *
 */
public class RepositoryProperties {

	public static final String SYSTEM_CONTEXTS_ID = "_contexts";
	public static final String PRINCIPALS_ID = "_principals";
	public static final String BACKUP_ID = "_backup";
	public static final String MD_PATH = "metadata";
	public static final String EXTERNAL_MD_PATH = "cached-external-metadata";
	public static final String ENTRY_PATH = "entry";
	public static final String LIST_PATH = "resource";
	public static final String DATA_PATH = "resource";
	public static final String NAME_PATH = "resource";
	public static final String MD_PATH_STUB = "metadata_stub";
	public static final String RELATION = "relations";

	public static String getResourcePath(GraphType bt) {
		switch (bt) {
		case List:
		case ResultList:
			return LIST_PATH;
		default:
			return DATA_PATH;
		}
	}
		
	public static final String NSbase = "http://entrystore.org/terms/";
	
	// Old DC namespace.
	public static final String NSDC = "http://purl.org/dc/elements/1.1/";
	// Update DC terms namespace.
	public static final String NSDCTERMS = "http://purl.org/dc/terms/";
	// RDF namespace.
	public static final String NSRDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
	// Provenance namespace
	public static final String NSPROV = "http://www.w3.org/ns/prov#";


	public static final IRI counter;
	public static final IRI mdHasEntry;
	public static final IRI resHasEntry;

	public static final IRI resource;
	public static final IRI metadata;
	public static final IRI relation;
	public static final IRI externalMetadata;
	public static final IRI cachedExternalMetadata;
	public static final IRI cached;
	
	public static final IRI alias;
	
	public static final IRI InformationResource;
	public static final IRI ResolvableInformationResource;
	public static final IRI Unknown;
	public static final IRI NamedResource;

	public static final IRI referredIn;
	public static final IRI hasListMember;
	public static final IRI hasGroupMember;
	
	public static final IRI Local;
	public static final IRI Reference;
	public static final IRI Link;
	public static final IRI LinkReference;
	
	public static final IRI Context;
	public static final IRI SystemContext;
	public static final IRI List;
	public static final IRI ResultList;
	public static final IRI User;
	public static final IRI Group;
	public static final IRI Pipeline;
	public static final IRI PipelineResult;
	public static final IRI None;
	public static final IRI String;

	public static final IRI homeContext;
	public static final IRI secret;
	public static final IRI saltedHashedSecret;
	public static final IRI language;
	public static final IRI originallyCreatedIn;
	public static final IRI externalID;
	public static final IRI disabled;

	public static final IRI Graph;
	
	public static final IRI Created;
	public static final IRI Modified;
	
	public static final IRI Deleted;
	public static final IRI DeletedBy;
	
	public static final IRI CommentsOn;
	public static final IRI ReviewsOn;
	
	public static final IRI Creator;
	public static final IRI Contributor;
	public static final IRI format;
	public static final IRI fileSize;
	public static final IRI filename;
	
	public static final IRI Quota;
	public static final IRI QuotaFillLevel;
	
	public static final IRI Read;
	public static final IRI Write;

	public static final IRI pipeline;
	public static final IRI pipelineData;
	public static final IRI status;
	public static final IRI Pending;
	public static final IRI Failed;
	public static final IRI Succeeded;

	public static final IRI wasAttributedTo;
	public static final IRI generatedAtTime;
	public static final IRI wasRevisionOf;


	static {
		ValueFactory vf = SimpleValueFactory.getInstance();
		counter = vf.createIRI(NSbase + "counter");
		mdHasEntry = vf.createIRI(NSbase + "mdHasMMd");
		resHasEntry = vf.createIRI(NSbase + "resHasMMd");
		alias = vf.createIRI(NSbase + "alias");
		
		metadata = vf.createIRI(NSbase + "metadata");
		relation = vf.createIRI(NSbase + "relation");
		externalMetadata = vf.createIRI(NSbase + "externalMetadata");
		cachedExternalMetadata = vf.createIRI(NSbase + "cachedExternalMetadata");
		cached = vf.createIRI(NSbase + "cached");

		Local = vf.createIRI(NSbase + "Local");
		Reference = vf.createIRI(NSbase + "Reference");
		Link = vf.createIRI(NSbase + "Link");
		LinkReference = vf.createIRI(NSbase + "LinkReference");
		resource = vf.createIRI(NSbase + "resource");
		
		referredIn = vf.createIRI(NSbase + "referredIn");
		hasListMember = vf.createIRI(NSbase + "hasListMember");
		hasGroupMember = vf.createIRI(NSbase + "hasGroupMember");

		InformationResource = vf.createIRI(NSbase + "InformationResource");
		ResolvableInformationResource = vf.createIRI(NSbase + "ResolvableInformationResource");
		Unknown = vf.createIRI(NSbase + "Unknown");
		NamedResource = vf.createIRI(NSbase + "NamedResource");
		Context = vf.createIRI(NSbase + "Context");
		SystemContext = vf.createIRI(NSbase + "SystemContext");
		List = vf.createIRI(NSbase + "List");
		ResultList = vf.createIRI(NSbase + "ResultList");
		User = vf.createIRI(NSbase + "User");
		Group = vf.createIRI(NSbase + "Group");
		Pipeline = vf.createIRI(NSbase + "Pipeline");
		PipelineResult = vf.createIRI(NSbase + "PipelineResult");
		None = vf.createIRI(NSbase + "None");
		String = vf.createIRI(NSbase + "String");
		Graph = vf.createIRI(NSbase + "Graph");
		
		secret = vf.createIRI(NSbase + "secret");
		saltedHashedSecret = vf.createIRI(NSbase + "saltedHashedSecret");
		homeContext = vf.createIRI(NSbase + "homeContext");
		language = vf.createIRI(NSbase + "language");
		externalID = vf.createIRI(NSbase, "externalID");
		disabled = vf.createIRI(NSbase, "disabled");
		
		originallyCreatedIn = vf.createIRI(NSbase + "originallyCreatedIn");

		Created = vf.createIRI(NSDCTERMS + "created");
		Modified = vf.createIRI(NSDCTERMS + "modified");
		
		Deleted = vf.createIRI(NSbase, "deleted");
		DeletedBy = vf.createIRI(NSbase, "deletedBy");
		
		CommentsOn = vf.createIRI(NSbase + "commentsOn");
		ReviewsOn = vf.createIRI(NSbase + "reviewsOn");
		format = vf.createIRI(NSDCTERMS + "format");
		fileSize = vf.createIRI(NSDCTERMS + "extent");
		filename = RDFS.LABEL;
		
		Creator = vf.createIRI(NSDCTERMS + "creator");
		Contributor = vf.createIRI(NSDCTERMS + "contributor");
		
		Read = vf.createIRI(NSbase + "read");
		Write = vf.createIRI(NSbase + "write");
		
		Quota = vf.createIRI(NSbase, "hasQuota");
		QuotaFillLevel = vf.createIRI(NSbase, "hasQuotaFillLevel");

		pipeline = vf.createIRI(NSbase, "pipeline");
		pipelineData = vf.createIRI(NSbase, "pipelineData");
		status = vf.createIRI(NSbase, "status");
		Pending = vf.createIRI(NSbase, "Pending");
		Failed = vf.createIRI(NSbase, "Failed");
		Succeeded = vf.createIRI(NSbase, "Succeeded");

		wasAttributedTo = vf.createIRI(NSPROV, "wasAttributedTo");
		generatedAtTime = vf.createIRI(NSPROV, "generatedAtTime");
		wasRevisionOf = vf.createIRI(NSPROV, "wasRevisionOf");
	}
}
