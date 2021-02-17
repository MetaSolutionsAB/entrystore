/*
 * Copyright (c) 2007-2021 MetaSolutions AB
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

package org.entrystore.rest.auth;

/**
 * @author Hannes Ebner
 */
public class CustomCookieSettings {

    private final boolean secure;

    private final boolean httpOnly;

    private final SameSite sameSite;

    private String stringValue;

    public enum SameSite {
        None,
        Lax,
        Strict
    }

    public CustomCookieSettings(boolean secure, boolean httpOnly, SameSite sameSite) {
        this.httpOnly = httpOnly;
        this.secure = secure;
        this.sameSite = sameSite;
        buildStringValue();
    }

    public boolean isSecure() {
        return secure;
    }

    public boolean isHttpOnly() {
        return httpOnly;
    }

    public SameSite getSameSite() {
        return sameSite;
    }

    public void buildStringValue() {
        StringBuilder sb = new StringBuilder();
        sb.append("SameSite=").append(sameSite.name());
        if (httpOnly) {
            sb.append("; HttpOnly");
        }
        if (secure || (sameSite == SameSite.None)) {
            // SameSite=None implies Secure, otherwise Cookie will be rejected
            sb.append("; Secure");
        }
        stringValue = sb.toString();
    }

    public String toString() {
        return stringValue;
    }

}