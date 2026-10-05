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
 * {@code datasetURL} comes from the pipeline, which any context writer can edit, so it must have the origin of
 * {@code entrystore.rowstore.url}; this keeps the server from sending requests to arbitrary hosts.
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

	private static final String APPLICATION_JSON = "application/json";

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
		String rowstoreUrl = rowStoreUrl(pipeline);
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
		URI datasetUri;
		try {
			datasetUri = URI.create(datasetURL);
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException("Invalid datasetURL " + datasetURL, e);
		}
		if (!isSameOrigin(datasetUri, URI.create(rowStoreUrl(pipeline)))) {
			log.warn("Rejected rowstore pipeline {}: datasetURL {} is not on the origin of {}",
					pipeline.getEntry().getEntryURI(), datasetURL, Settings.ROWSTORE_URL);
			throw new IllegalStateException("datasetURL " + datasetURL + " is not on the RowStore origin");
		}
		Set<Entry> datasetEntries = pipeline.getEntry().getContext().getByResourceURI(datasetUri);
		if (datasetEntries.size() != 1) {
			throw new IllegalStateException("Expected one result entry for dataset " + datasetURL + ", found "
					+ datasetEntries.size() + "; aborting update");
		}
		Entry datasetEntry = datasetEntries.iterator().next();

		RowStoreClient.Response response = switch (action) {
			case "replace" -> client.send("PUT", datasetUri, csvBody(sourceEntry), TEXT_CSV);
			case "append" -> client.send("POST", datasetUri, csvBody(sourceEntry), TEXT_CSV);
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
		return client.send("PUT", aliasesUri, BodyPublishers.ofString(toJsonArray(alias)), APPLICATION_JSON);
	}

	private static String rowStoreUrl(Pipeline pipeline) {
		String rowstoreUrl = pipeline.getEntry().getRepositoryManager().getConfiguration()
				.getString(Settings.ROWSTORE_URL);
		if (rowstoreUrl == null || rowstoreUrl.isBlank()) {
			throw new IllegalStateException("CSV2RowStoreTransform requires " + Settings.ROWSTORE_URL);
		}
		return rowstoreUrl;
	}

	/**
	 * Compares scheme and host case-insensitively and treats an omitted port as the scheme's default port.
	 */
	static boolean isSameOrigin(URI uri, URI other) {
		return uri.getScheme() != null && uri.getScheme().equalsIgnoreCase(other.getScheme())
				&& uri.getHost() != null && uri.getHost().equalsIgnoreCase(other.getHost())
				&& effectivePort(uri) == effectivePort(other);
	}

	private static int effectivePort(URI uri) {
		if (uri.getPort() != -1) {
			return uri.getPort();
		}
		return switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
			case "http" -> 80;
			case "https" -> 443;
			default -> -1;
		};
	}

	/**
	 * @return a JSON array with the value as its only string element
	 */
	static String toJsonArray(String value) {
		StringBuilder json = new StringBuilder("[\"");
		for (char c : value.toCharArray()) {
			switch (c) {
				case '"' -> json.append("\\\"");
				case '\\' -> json.append("\\\\");
				default -> {
					if (c < 0x20) {
						json.append("\\u%04x".formatted((int) c));
					} else {
						json.append(c);
					}
				}
			}
		}
		return json.append("\"]").toString();
	}

	private static BodyPublisher csvBody(Entry sourceEntry) {
		String message = "Source entry " + sourceEntry.getEntryURI() + " has no data";
		File file = ((Data) sourceEntry.getResource()).getDataFile();
		if (file == null) {
			throw new IllegalStateException(message);
		}
		try {
			return BodyPublishers.ofFile(file.toPath());
		} catch (FileNotFoundException e) {
			throw new IllegalStateException(message, e);
		}
	}
}
