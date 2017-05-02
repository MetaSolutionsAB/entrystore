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

package org.entrystore.impl.converters;

import java.net.URI;
import java.util.HashMap;

import org.entrystore.Converter;


/**
 * 
 * @author Eric Johansson (eric.johansson@educ.umu.se)
 *
 */
public class ConverterManagerImpl {

	public static HashMap<String, Converter> converters = new HashMap<String, Converter>(); 
	
	public static void register(String type, Converter converter) {
		converters.put(type, converter); 
	}
	
	public static Object convert(String type, Object from, URI resourceURI, URI metadataURI) {
		if (!converters.containsKey(type)) {
			return null;
		}
		return converters.get(type).convert(from , resourceURI, metadataURI);
	}

}
