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

package org.entrystore.transforms.empty;

import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.ResourceType;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.repository.util.NS;
import org.entrystore.transforms.Pipeline;
import org.entrystore.transforms.Transform;
import org.entrystore.transforms.TransformParameters;
import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.model.impl.LinkedHashModel;
import org.openrdf.model.impl.URIImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

/**
 * Creates an empty pipeline result that can be further processed by external services.
 *
 * @author Hannes Ebner
 */
@TransformParameters(type="empty", extensions={})
public class EmptyTransform extends Transform {

	private static Logger log = LoggerFactory.getLogger(EmptyTransform.class);

	@Override
	public Object transform(Pipeline pipeline, Entry sourceEntry) {
		String pipelineURI = pipeline.getEntry().getEntryURI().toString();

		Entry newEntry = pipeline.getEntry().getContext().createResource(null, GraphType.PipelineResult, ResourceType.InformationResource, null);
		newEntry.setStatus(java.net.URI.create(RepositoryProperties.Pending.toString()));
		String newEntryURI = newEntry.getEntryURI().toString();
		Graph newEntryGraph = newEntry.getGraph();
		newEntryGraph.add(new URIImpl(newEntryURI), RepositoryProperties.pipeline, new URIImpl(pipelineURI));
		if (sourceEntry != null) {
			String sourceURI = sourceEntry.getEntryURI().toString();
			newEntryGraph.add(new URIImpl(newEntryURI), RepositoryProperties.pipelineData, new URIImpl(sourceURI));
		}
		newEntry.setGraph(newEntryGraph);

		Graph pipelineMd = pipeline.getEntry().getMetadataGraph();
		Graph pipelineResultMd = new LinkedHashModel();

		//Copy over title, to make presentation nicer from the start (already when pending)
		Iterator<Statement> titles = pipelineMd.match(null, new URIImpl(NS.dcterms + "title"), null);
		while (titles.hasNext()) {
			Statement titleStmnt = titles.next();
			pipelineResultMd.add(new URIImpl(newEntry.getResourceURI().toString()), titleStmnt.getPredicate(), titleStmnt.getObject());
		}
		//Copy over tags, to make it easier to find specific pipelineresults
		Iterator<Statement> tags = pipelineMd.match(null, new URIImpl(NS.dcterms + "subject"), null);
		while (tags.hasNext()) {
			Statement tagStmnt = tags.next();
			pipelineResultMd.add(new URIImpl(newEntry.getResourceURI().toString()), tagStmnt.getPredicate(), tagStmnt.getObject());
		}
		if (!pipelineResultMd.isEmpty()) {
			newEntry.getLocalMetadata().setGraph(pipelineResultMd);
		}

		return newEntry;
	}
}