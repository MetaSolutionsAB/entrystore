/*
 * Copyright (c) 2007-2024 MetaSolutions AB
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

package org.entrystore.impl.converters;

import org.eclipse.rdf4j.model.Model;
import org.entrystore.Converter;
import org.w3c.dom.Node;

import java.net.URI;
import java.util.HashMap;

/**
 * @author Eric Johansson (eric.johansson@educ.umu.se)
 */
public class ConverterManagerImpl {

	public static HashMap<String, Converter> converters = new HashMap<>();

	public static void register(String type, Converter converter) {
		converters.put(type, converter);
	}

	public static Model convert(String type, Node from, URI resourceURI) {

		if (converters.containsKey(type) && converters.get(type) instanceof OAI_DC2RDFGraphConverter) {
			return converters.get(type).convertToModel(from, resourceURI);
		}

		return null;
	}

}
