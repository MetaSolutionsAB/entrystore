/*
 * Copyright (c) 2007-2014
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

package org.entrystore.transforms.pcaxis;

import com.mysema.stat.pcaxis.DatasetHandler;
import com.mysema.stat.pcaxis.PCAxisParser;
import org.entrystore.transforms.Transform;
import org.entrystore.transforms.TransformParameters;
import org.openrdf.model.Graph;
import org.openrdf.model.Model;
import org.openrdf.model.impl.LinkedHashModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Transforms a PC-Axis file into a SCOVO/RDF-graph.
 *
 * @author Hannes Ebner
 */
@TransformParameters(type="pcaxis", extensions={"px"})
public class PcAxis2ScovoTransform extends Transform {

	private static Logger log = LoggerFactory.getLogger(PcAxis2ScovoTransform.class);

	public Graph transform(InputStream data, String mimetype) {
		String baseURI = getArguments().get("baseuri");
		String datasetName = getArguments().get("dataset");

		if (baseURI == null || datasetName == null) {
			log.error("Parameters missing, aborting transform");
			return null;
		}

		Model model = new LinkedHashModel();
		DatasetHandler handler = new ScovoHandler(model, baseURI, false);
		PCAxisParser parser = new PCAxisParser(handler);
		try {
			parser.parse(datasetName, data);
		} catch (IOException e) {
			log.error(e.getMessage());
			return null;
		}

		return model;
	}

}