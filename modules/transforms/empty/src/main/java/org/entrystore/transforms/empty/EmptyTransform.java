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

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.ResourceType;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.repository.util.NS;
import org.entrystore.transforms.Pipeline;
import org.entrystore.transforms.Transform;
import org.entrystore.transforms.TransformParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

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
		ValueFactory vf = SimpleValueFactory.getInstance();
		String pipelineURI = pipeline.getEntry().getEntryURI().toString();

		Entry newEntry = pipeline.getEntry().getContext().createResource(null, GraphType.PipelineResult, ResourceType.InformationResource, null);
		newEntry.setStatus(URI.create(RepositoryProperties.Pending.toString()));
		String newEntryURI = newEntry.getEntryURI().toString();
		Model newEntryGraph = newEntry.getGraph();
		newEntryGraph.add(vf.createIRI(newEntryURI), RepositoryProperties.pipeline, vf.createIRI(pipelineURI));
		if (sourceEntry != null) {
			String sourceURI = sourceEntry.getEntryURI().toString();
			newEntryGraph.add(vf.createIRI(newEntryURI), RepositoryProperties.pipelineData, vf.createIRI(sourceURI));
		}
		newEntry.setGraph(newEntryGraph);

		Model pipelineMd = pipeline.getEntry().getMetadataGraph();
		Model pipelineResultMd = new LinkedHashModel();

		//Copy over title, to make presentation nicer from the start (already when pending)
		for (Statement titleStmnt : pipelineMd.filter(null, vf.createIRI(NS.dcterms + "title"), null)) {
			pipelineResultMd.add(vf.createIRI(newEntry.getResourceURI().toString()), titleStmnt.getPredicate(), titleStmnt.getObject());
		}
		//Copy over tags, to make it easier to find specific pipelineresults
		for (Statement tagStmnt : pipelineMd.filter(null, vf.createIRI(NS.dcterms + "subject"), null)) {
			pipelineResultMd.add(vf.createIRI(newEntry.getResourceURI().toString()), tagStmnt.getPredicate(), tagStmnt.getObject());
		}
		if (!pipelineResultMd.isEmpty()) {
			newEntry.getLocalMetadata().setGraph(pipelineResultMd);
		}

		return newEntry;
	}
}