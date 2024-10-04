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

package org.entrystore.rest.util;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class containing JSON error messages for the resources.
 */
public class JSONErrorMessages {

	private static Logger log = LoggerFactory.getLogger(JSONErrorMessages.class);

	public static String errorWrongContextIDmsg = new String("{\"error\":\"The requested context ID does not exist\"}");
	public static String errorCantCreateEntry = new String("{\"error\":\"Cannot create entry\"}");
	public static String errorEntryWithGivenIDExists = new String("{\"error\":\"Entry with provided ID already exists\"}");
	public static String errorEntryNotFound = new String("{\"error\":\"Entry not found\"}");
	public static String errorResourceNotFound = new String("{\"error\":\"Resource not found\"}");
	public static String errorMetadataNotFound = new String("{\"error\":\"Metadata not found\"}");
	public static String errorCachedMetadataNotFound = new String("{\"error\":\"Cached external metadata not found\"}");
	public static String errorJSONSyntax = new String("{\"error\":\"Error in JSON syntax\"}");
	public static String errorUnknownKind = new String("{\"error\":\"Unknown kind\"}");
	public static String errorNotAContext = new String("{\"error\":\"Not a context\"}");
	public static String errorCannotSerialize = new String("{\"error\":\"Requested object cannot be serialized\"}");
	public static String errorCannotDeserialize = new String("{\"error\":\"Requested object cannot be deserialized\"}");
	public static String errorUnknownFormat = new String("{\"error\":\"Unknown requested format\"}");
	public static JSONObject errorChildExistsInList = constructMessage("An entry cannot be added multiple times", "IllegalListMemberDuplicate");

	public static String unauthorizedGETContext = new String("{\"error\":\"Not authorized\"}");
	public static String unauthorizedGET = new String("{\"error\":\"Not authorized\"}");
	public static String unauthorizedDELETE = new String("{\"error\":\"Not authorized\"}");
	public static String unauthorizedPOST = new String("{\"error\":\"Not authorized\"}");
	public static String unauthorizedPUT = new String("{\"error\":\"Not authorized\"}");

	public static String syndicationFormat = new String("{\"error\":\"Syndication format is not supported\"}");

	static JSONObject constructMessage(String error, String code) {
		JSONObject result = new JSONObject();
		try {
			result.put("error", error);
			result.put("code", code);
		} catch (JSONException e) {
			log.error(e.getMessage(), e);
		}
		return result;
	}

}
