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

import org.entrystore.GraphType;
import org.openrdf.model.URI;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.model.vocabulary.RDFS;

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
	public static final String INFERRED = "inferred";


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


	public static final URI counter;
	public static final URI mdHasEntry;
	public static final URI resHasEntry;

	public static final URI resource;
	public static final URI metadata;
	public static final URI relation;
	public static final URI externalMetadata;
	public static final URI cachedExternalMetadata;
	public static final URI cached;
	
	public static final URI alias;
	
	public static final URI InformationResource;
	public static final URI ResolvableInformationResource;
	public static final URI Unknown;
	public static final URI NamedResource;

	public static final URI referredIn;
	public static final URI hasListMember;
	public static final URI hasGroupMember;
	
	public static final URI Local;
	public static final URI Reference;
	public static final URI Link;
	public static final URI LinkReference;
	
	public static final URI Context;
	public static final URI SystemContext;
	public static final URI List;
	public static final URI ResultList;
	public static final URI User;
	public static final URI Group;
	public static final URI Pipeline;
	public static final URI PipelineResult;
	public static final URI None;	
	public static final URI String;

	public static final URI reasoningFacts;

	public static final URI homeContext;
	public static final URI secret;
	public static final URI saltedHashedSecret;
	public static final URI language;
	public static final URI originallyCreatedIn;
	public static final URI externalID;

	public static final URI Graph;
	
	public static final URI Created;
	public static final URI Modified;
	
	public static final URI Deleted;
	public static final URI DeletedBy;
	
	public static final URI CommentsOn;
	public static final URI ReviewsOn;
	
	public static final URI Creator;
	public static final URI Contributor;
	public static final URI format;
	public static final URI fileSize;
	public static final URI filename;
	
	public static final URI Quota;
	public static final URI QuotaFillLevel;
	
	public static final URI Read;
	public static final URI Write;

	public static final URI pipeline;
	public static final URI pipelineData;
	public static final URI status;
	public static final URI Pending;
	public static final URI Failed;
	public static final URI Succeeded;

	public static final URI wasAttributedTo;
	public static final URI generatedAtTime;
	public static final URI wasRevisionOf;

	static {
		ValueFactory vf = ValueFactoryImpl.getInstance();
		counter = vf.createURI(NSbase + "counter");
		mdHasEntry = vf.createURI(NSbase + "mdHasMMd");
		resHasEntry = vf.createURI(NSbase + "resHasMMd");
		alias = vf.createURI(NSbase + "alias");
		
		metadata = vf.createURI(NSbase + "metadata");
		relation = vf.createURI(NSbase + "relation");
		externalMetadata = vf.createURI(NSbase + "externalMetadata");
		cachedExternalMetadata = vf.createURI(NSbase + "cachedExternalMetadata");
		cached = vf.createURI(NSbase + "cached");		

		Local = vf.createURI(NSbase + "Local");
		Reference = vf.createURI(NSbase + "Reference");
		Link = vf.createURI(NSbase + "Link");
		LinkReference = vf.createURI(NSbase + "LinkReference");
		resource = vf.createURI(NSbase + "resource");
		
		referredIn = vf.createURI(NSbase + "referredIn");
		hasListMember = vf.createURI(NSbase + "hasListMember");
		hasGroupMember = vf.createURI(NSbase + "hasGroupMember");

		InformationResource = vf.createURI(NSbase + "InformationResource");
		ResolvableInformationResource = vf.createURI(NSbase + "ResolvableInformationResource");
		Unknown = vf.createURI(NSbase + "Unknown");
		NamedResource = vf.createURI(NSbase + "NamedResource");
		Context = vf.createURI(NSbase + "Context");
		SystemContext = vf.createURI(NSbase + "SystemContext");
		List = vf.createURI(NSbase + "List");
		ResultList = vf.createURI(NSbase + "ResultList");
		User = vf.createURI(NSbase + "User");
		Group = vf.createURI(NSbase + "Group");
		Pipeline = vf.createURI(NSbase + "Pipeline");
		PipelineResult = vf.createURI(NSbase + "PipelineResult");
		None = vf.createURI(NSbase + "None");
		String = vf.createURI(NSbase + "String");
		Graph = vf.createURI(NSbase + "Graph");

		reasoningFacts = vf.createURI(NSbase + "reasoningFacts");

		secret = vf.createURI(NSbase + "secret");
		saltedHashedSecret = vf.createURI(NSbase + "saltedHashedSecret");
		homeContext = vf.createURI(NSbase + "homeContext");
		language = vf.createURI(NSbase + "language");
		externalID = vf.createURI(NSbase, "externalID");
		
		originallyCreatedIn = vf.createURI(NSbase + "originallyCreatedIn");

		Created = vf.createURI(NSDCTERMS + "created");
		Modified = vf.createURI(NSDCTERMS + "modified");
		
		Deleted = vf.createURI(NSbase, "deleted");
		DeletedBy = vf.createURI(NSbase, "deletedBy");
		
		CommentsOn = vf.createURI(NSbase + "commentsOn");
		ReviewsOn = vf.createURI(NSbase + "reviewsOn");
		format = vf.createURI(NSDCTERMS + "format");
		fileSize = vf.createURI(NSDCTERMS + "extent");
		filename = RDFS.LABEL;
		
		Creator = vf.createURI(NSDCTERMS + "creator");
		Contributor = vf.createURI(NSDCTERMS + "contributor");
		
		Read = vf.createURI(NSbase + "read");
		Write = vf.createURI(NSbase + "write");
		
		Quota = vf.createURI(NSbase, "hasQuota");
		QuotaFillLevel = vf.createURI(NSbase, "hasQuotaFillLevel");

		pipeline = vf.createURI(NSbase, "pipeline");
		pipelineData = vf.createURI(NSbase, "pipelineData");
		status = vf.createURI(NSbase, "status");
		Pending = vf.createURI(NSbase, "Pending");
		Failed = vf.createURI(NSbase, "Failed");
		Succeeded = vf.createURI(NSbase, "Succeeded");

		wasAttributedTo = vf.createURI(NSPROV, "wasAttributedTo");
		generatedAtTime = vf.createURI(NSPROV, "generatedAtTime");
		wasRevisionOf = vf.createURI(NSPROV, "wasRevisionOf");
	}
}
