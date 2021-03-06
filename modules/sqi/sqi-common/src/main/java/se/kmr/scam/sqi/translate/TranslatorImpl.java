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

package se.kmr.scam.sqi.translate;

import se.kmr.scam.sqi.query.QueryLanguageType;

public abstract class TranslatorImpl {
    private QueryLanguageType startQueryLanguage;
    private QueryLanguageType endQueryLanguage;

    protected TranslatorImpl(QueryLanguageType startQueryLanguage, QueryLanguageType endQueryLanguage) {
        this.startQueryLanguage = startQueryLanguage;
        this.endQueryLanguage = endQueryLanguage;
    }

    protected QueryLanguageType getEndQueryLanguage() {
        return endQueryLanguage;
    }

    protected QueryLanguageType getStartQueryLanguage() {
        return startQueryLanguage;
    }

    public abstract String translate(String query, int startResult, int resultsSetSize) throws TranslationException;
}