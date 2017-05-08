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

package org.entrystore.transforms.rowstore;

import org.apache.commons.io.IOUtils;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.ResourceType;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.repository.config.Settings;
import org.entrystore.transforms.Pipeline;
import org.entrystore.transforms.Transform;
import org.entrystore.transforms.TransformParameters;
import org.openrdf.model.Graph;
import org.openrdf.model.Model;
import org.openrdf.model.impl.LinkedHashModel;
import org.openrdf.model.impl.URIImpl;
import org.restlet.Client;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.representation.InputRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.util.Set;

/**
 * Transforms a CSV file into a RowStore dataset.
 *
 * @author Hannes Ebner
 */
@TransformParameters(type="rowstore", extensions={"csv"})
public class CSV2RowStoreTransform extends Transform {

	private static Logger log = LoggerFactory.getLogger(CSV2RowStoreTransform.class);

	@Override
	public Object transform(Pipeline pipeline, Entry sourceEntry) {
		if (sourceEntry == null) {
			throw new IllegalStateException("CSV2RowStoreTransform requires a sourceEntry");
		}
        InputStream data = ((Data) sourceEntry.getResource()).getData();
		String pipelineURI = pipeline.getEntry().getEntryURI().toString();
		String sourceURI = sourceEntry.getEntryURI().toString();
		Config conf = pipeline.getEntry().getRepositoryManager().getConfiguration();

		String action = getArguments().getOrDefault("action", "create");

		Entry result = null;
		Response httpResponse = null;
		try {
			if ("create".equalsIgnoreCase(action)) {
				String datasetsUrl = conf.getString(Settings.ROWSTORE_URL);
				if (!datasetsUrl.endsWith("/")) {
					datasetsUrl += "/";
				}
				datasetsUrl += "datasets";
				httpResponse = sendData(Method.POST, datasetsUrl, data, MediaType.TEXT_CSV);
				if (!Status.SUCCESS_ACCEPTED.equals(httpResponse.getStatus())) {
					log.error("Dataset could not be created in RowStore");
					return null;
				}
				String datasetURL = httpResponse.getLocationRef().toString();
				String datasetInfoURL = datasetURL + "/info";

				Entry newEntry = pipeline.getEntry().getContext().createReference(null, URI.create(datasetURL), URI.create(datasetInfoURL), null);
				newEntry.setGraphType(GraphType.PipelineResult);
				newEntry.setResourceType(ResourceType.InformationResource);
				String newEntryURI = newEntry.getEntryURI().toString();
				Graph newEntryGraph = newEntry.getGraph();
				newEntryGraph.add(new URIImpl(newEntryURI), RepositoryProperties.pipeline, new URIImpl(pipelineURI));
				newEntryGraph.add(new URIImpl(newEntryURI), RepositoryProperties.pipelineData, new URIImpl(sourceURI));
				newEntry.setGraph(newEntryGraph);

				result = newEntry;
			} else if ("replace".equalsIgnoreCase(action) || "append".equalsIgnoreCase(action) || "setalias".equalsIgnoreCase(action)) {
				String datasetURL = getArguments().get("datasetURL");
				if (datasetURL == null) {
					throw new IllegalStateException("CSV2RowStoreTransform action " + action + " requires a datasetURL parameter");
				}
				Set<Entry> datasetEntries = pipeline.getEntry().getContext().getByResourceURI(URI.create(datasetURL));
				Entry datasetEntry = null;
				if (datasetEntries.size() == 1) {
					datasetEntry = datasetEntries.iterator().next();
				} else {
					throw new IllegalStateException("Found multiple result entries for same dataset, aborting update");
				}

				Model datasetEntryGraph = new LinkedHashModel(datasetEntry.getGraph());

				if ("replace".equalsIgnoreCase(action)) {
					httpResponse = sendData(Method.PUT, datasetURL, data, MediaType.TEXT_CSV);
					datasetEntryGraph.remove(null, RepositoryProperties.pipelineData, null);
					datasetEntryGraph.add(new URIImpl(datasetEntry.getEntryURI().toString()), RepositoryProperties.pipelineData, new URIImpl(sourceURI));
					datasetEntry.setGraph(datasetEntryGraph);
				} else if ("append".equalsIgnoreCase(action)) {
					httpResponse = sendData(Method.POST, datasetURL, data, MediaType.TEXT_CSV);
					datasetEntryGraph.add(new URIImpl(datasetEntry.getEntryURI().toString()), RepositoryProperties.pipelineData, new URIImpl(sourceURI));
					datasetEntry.setGraph(datasetEntryGraph);
				} else if ("setalias".equalsIgnoreCase(action)) {
					String datasetAliasURL = datasetURL + (datasetURL.endsWith("/") ? "" : "/") + "aliases";
					String alias = getArguments().get("alias");
					if (alias == null || alias.length() == 0) {
						httpResponse = sendData(Method.DELETE, datasetAliasURL, null, null);
					} else {
						String jsonArray = "[\"" + alias + "\"]";
						httpResponse = sendData(Method.PUT, datasetAliasURL, IOUtils.toInputStream(jsonArray), MediaType.APPLICATION_JSON);
					}
				}
				if (!Status.SUCCESS_ACCEPTED.equals(httpResponse.getStatus()) && !Status.SUCCESS_OK.equals(httpResponse.getStatus())) {
					log.error("Dataset could not modified");
					return null;
				}
				Set<Entry> resultEntries = pipeline.getEntry().getContext().getByResourceURI(URI.create(datasetURL));
				if (resultEntries.size() == 0) {
					log.warn("No existing result entry found after updating RowStore dataset");
				} else if (resultEntries.size() > 1) {
					log.warn("Multiple result entries found after updating RowStore dataset, only returning one");
				}
				result = resultEntries.iterator().next();
			} else {
				log.warn("Unable to process unknown action " + action);
			}
		} finally {
			if (httpResponse != null) {
				httpResponse.release();
			}
		}

		return result;
	}

	private Response sendData(Method method, String url, InputStream data, MediaType mediaType) {
		if (method == null || url == null || data == null) {
			throw new IllegalArgumentException("Arguments must not be null");
		}

		Client client = new Client(Protocol.HTTP);
		client.setContext(new org.restlet.Context());
		client.getContext().getParameters().add("connectTimeout", "10000");
		client.getContext().getParameters().add("readTimeout", "10000");
		client.getContext().getParameters().set("socketTimeout", "10000");
		client.getContext().getParameters().set("socketConnectTimeoutMs", "10000");
		log.info("Initialized HTTP client for RowStore Transform");

		Request request = new Request(method, url);
		if (data != null) {
			request.setEntity(new InputRepresentation(data, mediaType));
		}

		return client.handle(request);
	}

}