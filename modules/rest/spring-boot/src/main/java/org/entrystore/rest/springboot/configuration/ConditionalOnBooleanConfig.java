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

package org.entrystore.rest.springboot.configuration;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gates EntryStore beans on a boolean setting, accepting true/on/yes/1 and false/off/no/0 case-insensitively,
 * as their configuration consumers do. {@code @ConditionalOnProperty(havingValue = "true")} would omit beans
 * for {@code on}, {@code yes} or {@code 1}. A blank value counts as missing; an unrecognised value fails startup.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(BooleanConfigCondition.class)
public @interface ConditionalOnBooleanConfig {

	/** Full property name, not a prefix. */
	String value();

	boolean matchIfMissing() default false;
}
