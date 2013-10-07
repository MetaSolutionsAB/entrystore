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

package org.entrystore.repository;

/**
 * A Resource is anything that can be identified by a URI whereas an <i>information resource</i>
 * is a resource whose <i>essential characteristics</i> can be conveyed in a <i>message</i>.
 * 
 * Examples are documents, images, videos etc. of various sorts which have representations, 
 * e.g. HTML, ODF, jpeg, mpeg etc. which can transferred in a message body which is the 
 * result of for instance an HTTP GET.
 * See discussion in <a href="http://www.w3.org/TR/webarch/#id-resources">W3C web architecture section 2.2</a>.
 *
 * If the HTTP protocol is used, see the
 * <a href="http://lists.w3.org/Archives/Public/www-tag/2005Jun/0039.html">reccomendation</a> from the
 * W3C Technical Architecture Group. For further inspiration see the discussion in 
 * <a href="http://www.dfki.uni-kl.de/~sauermann/2006/11/cooluris/">Cool URIs for the Semantic Web</a>.
 * 
 * @author matthias
 */
public enum RepresentationType {
	/**
	 * The resource has a representation, in the repository or elsewhere.
	 */
	InformationResource,

	/**
	 * The resource is an information resource but requires a resolvable step, 
	 *  e.g. through a lookup procedure that might be protocol specific such as urn:path or doi.
	 */
	ResolvableInformationResource,
	
	/**
	 * The representation type of the resource is unknown. 
	 */
	Unknown,
	
	/**
	 * The resource is not an information resource, the resource can be refferred to in communication but
	 * not transferred in a message. 
	 */
	NamedResource
}
