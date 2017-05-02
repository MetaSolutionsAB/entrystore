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

package org.entrystore;


import org.openrdf.model.Graph;

/**
 * A graph entity contains, in addition to when and who,
 * also the actual graph of the revision.
 * Access to this graph is provided via the same methods
 * as for other metadata objects.
 *
 * Note that modifying the history is not allowed,
 * hence the setGraph methods from the Metadata interface will
 * throw an UnsupportedOperationException.
 * @author Matthias Palmér
 */
public interface GraphEntity extends Entity, Metadata {
}