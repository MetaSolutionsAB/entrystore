/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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

import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.ResourceType;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.repository.config.Settings;
import org.entrystore.transforms.Pipeline;
import org.entrystore.transforms.Transform;
import org.entrystore.transforms.TransformException;
import org.entrystore.transforms.TransformParameters;
import org.openrdf.model.Graph;
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
		String datasetsUrl = conf.getString(Settings.ROWSTORE_URL);
		if (!datasetsUrl.endsWith("/")) {
			datasetsUrl += "/";
		}
		datasetsUrl += "datasets";

		Response postResponse = postData(datasetsUrl, data);
		String newDatasetURL;
		try {
			if (!Status.SUCCESS_ACCEPTED.equals(postResponse.getStatus())) {
				log.error("Dataset could not be created in RowStore");
				return null;
			}
			newDatasetURL = postResponse.getLocationRef().toString();
		} finally {
			postResponse.release();
		}
		String newDatasetInfoURL = newDatasetURL + "/info";

		Entry newEntry = pipeline.getEntry().getContext().createReference(null, URI.create(newDatasetURL), URI.create(newDatasetInfoURL), null);
		newEntry.setGraphType(GraphType.PipelineResult);
		newEntry.setResourceType(ResourceType.InformationResource);
		String newEntryURI = newEntry.getEntryURI().toString();
		Graph newEntryGraph = newEntry.getGraph();
		newEntryGraph.add(new URIImpl(newEntryURI), RepositoryProperties.pipeline, new URIImpl(pipelineURI));
		newEntryGraph.add(new URIImpl(newEntryURI), RepositoryProperties.pipelineData, new URIImpl(sourceURI));
		newEntry.setGraph(newEntryGraph);

		return newEntry;
	}

	private Response postData(String url, InputStream data) {
		Client client = new Client(Protocol.HTTP);
		client.setContext(new org.restlet.Context());
		client.getContext().getParameters().add("connectTimeout", "10000");
		client.getContext().getParameters().add("readTimeout", "10000");
		client.getContext().getParameters().set("socketTimeout", "10000");
		client.getContext().getParameters().set("socketConnectTimeoutMs", "10000");
		log.info("Initialized HTTP client for RowStore Transform");

		Request request = new Request(Method.POST, url);
		request.setEntity(new InputRepresentation(data, MediaType.TEXT_CSV));

		return client.handle(request);
	}

}