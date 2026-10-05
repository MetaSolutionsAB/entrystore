/*
 * Copyright (c) 2007-2026 MetaSolutions AB
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

package org.entrystore.transforms.rowstore;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.ResourceType;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.repository.config.Settings;
import org.entrystore.transforms.Pipeline;
import org.entrystore.transforms.Transform;
import org.entrystore.transforms.TransformParameters;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URI;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.Locale;
import java.util.Set;

/**
 * Transforms a CSV file into a RowStore dataset.
 *
 * <p>The argument {@code action} selects what is done (default {@code create}):
 * <ul>
 *     <li>{@code create} uploads the source CSV as a new dataset and returns a new PipelineResult entry
 *     referencing it.</li>
 *     <li>{@code replace} and {@code append} upload the source CSV to the dataset at the argument
 *     {@code datasetURL}.</li>
 *     <li>{@code setalias} sets the argument {@code alias} on the dataset at {@code datasetURL}, or removes the
 *     aliases if {@code alias} is empty. It needs no source entry.</li>
 * </ul>
 * {@code replace}, {@code append} and {@code setalias} return the existing PipelineResult entry of the dataset.
 * If RowStore rejects a request the transform returns null.
 *
 * @author Hannes Ebner
 */
@Slf4j
@TransformParameters(type = "rowstore", extensions = {"csv"})
public class CSV2RowStoreTransform extends Transform {

	private static final RowStoreClient client = new RowStoreClient();

	private static final String TEXT_CSV = "text/csv";

	private static final ValueFactory vf = SimpleValueFactory.getInstance();

	@Override
	public Object transform(Pipeline pipeline, Entry sourceEntry) {
		String action = getArguments().getOrDefault("action", "create").toLowerCase(Locale.ROOT);
		if (sourceEntry == null && !"setalias".equals(action)) {
			throw new IllegalStateException("CSV2RowStoreTransform requires a sourceEntry for action " + action);
		}

		return switch (action) {
			case "create" -> createDataset(pipeline, sourceEntry);
			case "replace", "append", "setalias" -> updateDataset(pipeline, sourceEntry, action);
			default -> {
				log.warn("Unable to process unknown action {}", action);
				yield null;
			}
		};
	}

	private Entry createDataset(Pipeline pipeline, Entry sourceEntry) {
		String rowstoreUrl = pipeline.getEntry().getRepositoryManager().getConfiguration()
				.getString(Settings.ROWSTORE_URL);
		if (rowstoreUrl == null || rowstoreUrl.isBlank()) {
			throw new IllegalStateException("CSV2RowStoreTransform requires " + Settings.ROWSTORE_URL);
		}
		URI datasetsUri = URI.create(rowstoreUrl + (rowstoreUrl.endsWith("/") ? "" : "/") + "datasets");

		RowStoreClient.Response response = client.send("POST", datasetsUri, csvBody(sourceEntry), TEXT_CSV);
		if (response.status() != 202 || response.location() == null) {
			log.error("Dataset could not be created in RowStore, status {}, location {}",
					response.status(), response.location());
			return null;
		}
		String datasetURL = response.location().toString();

		Entry newEntry = pipeline.getEntry().getContext().createReference(null,
				URI.create(datasetURL), URI.create(datasetURL + "/info"), null);
		newEntry.setGraphType(GraphType.PipelineResult);
		newEntry.setResourceType(ResourceType.InformationResource);
		IRI newEntryIRI = vf.createIRI(newEntry.getEntryURI().toString());
		Model newEntryGraph = newEntry.getGraph();
		newEntryGraph.add(newEntryIRI, RepositoryProperties.pipeline,
				vf.createIRI(pipeline.getEntry().getEntryURI().toString()));
		newEntryGraph.add(newEntryIRI, RepositoryProperties.pipelineData,
				vf.createIRI(sourceEntry.getEntryURI().toString()));
		newEntry.setGraph(newEntryGraph);
		return newEntry;
	}

	private Entry updateDataset(Pipeline pipeline, Entry sourceEntry, String action) {
		String datasetURL = getArguments().get("dataseturl");
		if (datasetURL == null) {
			throw new IllegalStateException("CSV2RowStoreTransform action " + action + " requires a datasetURL parameter");
		}
		Set<Entry> datasetEntries = pipeline.getEntry().getContext().getByResourceURI(URI.create(datasetURL));
		if (datasetEntries.size() != 1) {
			throw new IllegalStateException("Expected one result entry for dataset " + datasetURL + ", found "
					+ datasetEntries.size() + "; aborting update");
		}
		Entry datasetEntry = datasetEntries.iterator().next();

		RowStoreClient.Response response = switch (action) {
			case "replace" -> client.send("PUT", URI.create(datasetURL), csvBody(sourceEntry), TEXT_CSV);
			case "append" -> client.send("POST", URI.create(datasetURL), csvBody(sourceEntry), TEXT_CSV);
			default -> setAlias(datasetURL, getArguments().get("alias"));
		};
		if (!response.isSuccess()) {
			log.error("Dataset {} could not be modified in RowStore, status {}", datasetURL, response.status());
			return null;
		}

		if (!"setalias".equals(action)) {
			IRI datasetEntryIRI = vf.createIRI(datasetEntry.getEntryURI().toString());
			Model datasetEntryGraph = new LinkedHashModel(datasetEntry.getGraph());
			if ("replace".equals(action)) {
				datasetEntryGraph.remove(null, RepositoryProperties.pipelineData, null);
			}
			datasetEntryGraph.add(datasetEntryIRI, RepositoryProperties.pipelineData,
					vf.createIRI(sourceEntry.getEntryURI().toString()));
			datasetEntry.setGraph(datasetEntryGraph);
		}
		return datasetEntry;
	}

	private RowStoreClient.Response setAlias(String datasetURL, String alias) {
		URI aliasesUri = URI.create(datasetURL + (datasetURL.endsWith("/") ? "" : "/") + "aliases");
		if (alias == null || alias.isEmpty()) {
			return client.send("DELETE", aliasesUri, null, null);
		}
		String jsonArray = "[\"" + alias.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
		return client.send("PUT", aliasesUri, BodyPublishers.ofString(jsonArray), "application/json");
	}

	/**
	 * Streams the data file when there is one, so the request carries a Content-Length.
	 */
	private static BodyPublisher csvBody(Entry sourceEntry) {
		Data data = (Data) sourceEntry.getResource();
		File file = data.getDataFile();
		if (file == null) {
			return BodyPublishers.ofInputStream(data::getData);
		}
		try {
			return BodyPublishers.ofFile(file.toPath());
		} catch (FileNotFoundException e) {
			throw new IllegalStateException("Data file of source entry " + sourceEntry.getEntryURI() + " is missing", e);
		}
	}
}
