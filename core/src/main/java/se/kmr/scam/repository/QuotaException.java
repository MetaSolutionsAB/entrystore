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

package se.kmr.scam.repository;


public class QuotaException extends Exception {
	
	public static final int QUOTA_EXCEEDED = 1;
	
	public static final int QUOTA_ERROR_UNKNOWN = 2;
	
	private int reason; 
	
	public QuotaException(int reason) {
		this.reason = reason;
	}
	
	public int getReason() {
		return this.reason;
	}
	
	public String getMessage() {
		switch (reason) {
		case QUOTA_EXCEEDED:
			return "Quota exceeded";

		case QUOTA_ERROR_UNKNOWN:
		default:
			return "Unknown error";
		}
	}

}