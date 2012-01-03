/**
 * Copyright (c) 2007-2010
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

/*  $Id: $
*
*  Copyright (c) 2008, IML Lab group at UMU (Umeå University)
*  Licensed under the GNU LGPL. For full terms see the file COPYRIGHT.
*/
package se.kmr.scam.rest.util;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
* This class containing JSON error messages for the resources. 
* 
* @author Eric Johansson
*/
public class JDILErrorMessages {
	
	private static Logger log = LoggerFactory.getLogger(JDILErrorMessages.class);
	
	public static String errorWrongContextIDmsg = new String("{\"error\":\"The given context id does not exist.\"}");
	public static String errorCantCreateEntry = new String("{\"error\":\"Can not create the entry.\"}");
	public static String errorCantNotFindEntry = new String("{\"error\":\"Can not find an entry with that id.\"}");
	public static String errorCantFindResource = new String("{\"error\":\"Can not find the resource.\"}"); 
	public static String errorCantFindMetadata = new String("{\"error\":\"Can not find the metadata.\"}");
	public static String errorCantFindCachedMetadata = new String("{\"error\":\"Can not find the cached external metadata.\"}");
	public static String errorWrongKind = new String("{\"error\":\"Can not find that \"kind\" of the request.\"}");
	public static String errorJSONSyntax = new String("{\"error\":\"Wrong syntax in the JSON.\"}");
	public static String errorUnknownKind = new String("{\"error\":\"The kind is unknown.\"}");
	public static String errorNotAContext = new String("{\"error\":\"It is not a context.\"}");
	public static String errorCannotSerialize = new String("{\"error\":\"The requested object cannot be serialized.\"}");
	public static String errorCannotDeserialize = new String("{\"error\":\"The requested object cannot be deserialized.\"}");
	public static String errorUnknownFormat = new String("{\"error\":\"The requested format is not known.\"}");
	public static JSONObject errorChildExistsInList = constructMessage("An entry cannot be added multiple times", "IllegalListMemberDuplicate");

	public static String unauthorizedGETContext = new String("{\"error\":\"client tried to GET a resource without being authorized for it's context.\"}");
	public static String unauthorizedGET = new String("{\"error\":\"client tried to GET a resource without being authorized for it.\"}");
	public static String unauthorizedDELETE = new String("{\"error\":\"client tried to DELETE a resource without being authorized for it.\"}");
	public static String unauthorizedPOST = new String("{\"error\":\"client tried to POST a resource without being authorized for it.\"}");
	public static String unauthorizedPUT = new String("{\"error\":\"client tried to PUT a resource without being authorized for it.\"}");
	public static String syndicationFormat = new String("{\"error\":\"Syndication format is not supported.\"}");
	
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